package com.entity303.entity;

import com.entity303.Entity303Mod;
import com.entity303.anim.Entity303Animations;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The brain of Entity 303: walks up to its target and plays one special attack at a time.
 * Every attack is a timeline (see tools/animations.py): the animation and the damage use the same tick numbers.
 * He keeps count of the players around him (within {@link #AWARE_RADIUS} blocks) and the more there are, the more
 * of them he attacks at the same time and the faster he goes from one attack to the next.
 *
 *  SWEEP     overhead chop, half-circle area hit in front of him
 *  SLASH     a beam of soul fire at EVERY player in range (a three-way fan on the main target from phase 2)
 *  STEP      teleports behind a player and chops
 *  SUMMON    calls vexes ("souls"), more with more players, and sends them after different players
 *  DRAIN     drains several players at once: slows, blinds and heals him
 *  WHIRL     REAPER'S DESCENT (phase 2+): flies up spinning, drags everybody to the floor under him, then crashes
 *            down; whoever is hit loses half of their maximum health, whatever their armor
 *  SLAM      marks the ground under every player, then explodes it (phase 2+)
 *  ROAR      phase change: invulnerable shockwave + summons
 */
public class Entity303AttackGoal extends Goal {
	/** Players closer than this (blocks) count as "around him". */
	public static final double AWARE_RADIUS = 40.0;
	/** Reaper's Descent: how far everybody is dragged from, how high he flies, how far the crash reaches. */
	private static final double PULL_RADIUS = 26.0;
	private static final double HOVER_HEIGHT = 7.0;
	private static final double IMPACT_RADIUS = 8.0;
	/** Reaper's Descent takes this fraction of the victim's MAXIMUM health, ignoring armor, enchantments, effects and shields. */
	private static final float DESCENT_FRACTION = 0.5F;
	/** ...and this fraction in the final phase. */
	private static final float DESCENT_FRACTION_FINAL = 0.75F;
	private static final ResourceKey<DamageType> DESCENT_DAMAGE =
		ResourceKey.create(Registries.DAMAGE_TYPE, Entity303Mod.id("reaper_descent"));

	private static final DustParticleOptions RED_DUST = new DustParticleOptions(0xFF2020, 1.4F);

	private final Entity303 boss;
	private int cooldown = 40;
	private int lastAttack = -1;
	private int forcedNext = -1;
	private final List<Vec3> slamPoints = new ArrayList<>();
	private final List<LivingEntity> drainVictims = new ArrayList<>();
	private LivingEntity stepTarget;

	private List<ServerPlayer> players = List.of();
	private int playersAge;
	private int retargetTimer = 100;
	private int minionTimer;

	// Reaper's Descent
	private double hoverBaseY;
	private double hoverTopY;

	// Reaper's Lunge
	private Vec3 lungeDir = new Vec3(0.0, 0.0, 1.0);
	// Death Leap
	private Vec3 leapFrom = Vec3.ZERO;
	private Vec3 leapTo = Vec3.ZERO;
	private double leapHeight;
	// Soul Hook
	private final List<LivingEntity> hookVictims = new ArrayList<>();
	private boolean hookLanded;
	// Soul Rings
	private final List<Ring> rings = new ArrayList<>();
	// Phantom Dance
	private LivingEntity danceLast;
	// Soul Rain
	private final List<Column> columns = new ArrayList<>();

	/** One expanding ring of Soul Rings: where it started, when, and who it has already hit. */
	private record Ring(int start, Vec3 center, Set<Integer> hit) {
	}

	/** One falling column of Soul Rain: where, and at which attack tick it strikes. */
	private record Column(Vec3 pos, int strike) {
	}

	public Entity303AttackGoal(Entity303 boss) {
		this.boss = boss;
		this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		LivingEntity target = this.boss.getTarget();
		return this.boss.isIntroPending() || (target != null && target.isAlive());
	}

	@Override
	public boolean canContinueToUse() {
		return this.boss.getAttack() != Entity303Animations.IDLE || this.canUse();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void stop() {
		this.boss.endAttack();
		this.boss.setNoGravity(false);
		this.boss.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (!(this.boss.level() instanceof ServerLevel level)) {
			return;
		}
		this.refreshPlayers(level);
		LivingEntity target = this.boss.getTarget();
		int attack = this.boss.getAttack();

		// freshly summoned: he climbs out of the ground first
		if (attack == Entity303Animations.IDLE && this.boss.consumeIntro()) {
			this.begin(Entity303Animations.SPAWN);
			attack = Entity303Animations.SPAWN;
		}

		// a phase change interrupts everything except the descent and the leap (they wait until he has landed)
		if (this.boss.isRoarRequested() && attack != Entity303Animations.WHIRL && attack != Entity303Animations.LEAP
			&& attack != Entity303Animations.SPAWN) {
			this.boss.consumeRoarRequest();
			this.begin(Entity303Animations.ROAR);
			attack = Entity303Animations.ROAR;
		}

		if (++this.minionTimer >= 20) {
			this.minionTimer = 0;
			this.assignMinionTargets(level);
		}

		if (attack == Entity303Animations.IDLE) {
			this.idle(level, target);
		} else {
			this.runAttack(level, attack, target);
		}
	}

	// ------------------------------------------------------- the crowd --------
	private void refreshPlayers(ServerLevel level) {
		if (this.playersAge++ % 10 != 0) {
			return;
		}
		List<ServerPlayer> found = new ArrayList<>(this.boss.nearbyPlayers(level, AWARE_RADIUS));
		found.sort(Comparator.comparingDouble(p -> p.distanceToSqr(this.boss)));
		this.players = found;
	}

	/** Players around him that are still alive. */
	private List<ServerPlayer> crowd() {
		List<ServerPlayer> alive = new ArrayList<>(this.players.size());
		for (ServerPlayer player : this.players) {
			if (player.isAlive() && !player.isCreative() && !player.isSpectator()) {
				alive.add(player);
			}
		}
		return alive;
	}

	private int crowdSize() {
		return Math.max(1, this.crowd().size());
	}

	private int maxMinions() {
		return Math.min(16, 6 + 2 * (this.crowdSize() - 1));
	}

	private double nearestPlayerDistance() {
		List<ServerPlayer> crowd = this.crowd();
		return crowd.isEmpty() ? 99.0 : this.boss.distanceTo(crowd.get(0));
	}

	private int playersWithin(double distance) {
		int count = 0;
		for (ServerPlayer player : this.crowd()) {
			if (this.boss.distanceTo(player) <= distance) {
				count++;
			}
		}
		return count;
	}

	/** Spread the attention of his minions over the different players. */
	private void assignMinionTargets(ServerLevel level) {
		List<ServerPlayer> crowd = this.crowd();
		if (crowd.size() < 2) {
			return;
		}
		AABB area = this.boss.getBoundingBox().inflate(AWARE_RADIUS);
		int i = 0;
		for (Vex vex : level.getEntitiesOfClass(Vex.class, area, v -> v.getOwner() == this.boss && v.isAlive())) {
			vex.setTarget(crowd.get(i++ % crowd.size()));
		}
	}

	// ------------------------------------------------------------ control ----
	private void begin(int attack) {
		this.boss.startAttack(attack);
		this.boss.setNoGravity(false);
		this.boss.getNavigation().stop();
		this.lastAttack = attack;
		this.slamPoints.clear();
		this.drainVictims.clear();
		this.stepTarget = null;
		this.hookVictims.clear();
		this.hookLanded = false;
		this.rings.clear();
		this.columns.clear();
		this.danceLast = null;
	}

	private void idle(ServerLevel level, LivingEntity target) {
		if (target == null || !target.isAlive()) {
			return;
		}
		this.maybeRetarget(target);
		target = this.boss.getTarget() != null ? this.boss.getTarget() : target;

		this.boss.getLookControl().setLookAt(target, 40.0F, 40.0F);
		double dist = this.boss.distanceTo(target);
		if (dist > 2.8) {
			this.boss.getNavigation().moveTo(target, 1.0);
		} else {
			this.boss.getNavigation().stop();
		}
		if (this.cooldown > 0) {
			this.cooldown--;
			return;
		}
		int next = this.forcedNext >= 0 ? this.forcedNext : this.pickAttack(level);
		this.forcedNext = -1;
		if (next != Entity303Animations.IDLE) {
			this.begin(next);
		}
	}

	/** With several players around him he does not stay glued to one of them. */
	private void maybeRetarget(LivingEntity current) {
		List<ServerPlayer> crowd = this.crowd();
		if (crowd.size() < 2) {
			return;
		}
		boolean far = this.boss.distanceTo(current) > 24.0 && this.boss.distanceTo(crowd.get(0)) < 14.0;
		if (--this.retargetTimer > 0 && !far) {
			return;
		}
		this.retargetTimer = 100 + this.boss.getRandom().nextInt(100);
		ServerPlayer pick = far ? crowd.get(0) : crowd.get(this.boss.getRandom().nextInt(crowd.size()));
		if (pick != current) {
			this.boss.setTarget(pick);
		}
	}

	private int pickAttack(ServerLevel level) {
		int phase = this.boss.getPhase();
		int n = this.crowdSize();
		double nearest = this.nearestPlayerDistance();
		int close = this.playersWithin(7.0);
		boolean fewMinions = this.countMinions(level) < this.maxMinions() / 2;

		int[] weight = new int[Entity303Animations.COUNT];
		if (nearest <= 6.0) {
			weight[Entity303Animations.SWEEP] = 6 + close;
		}
		if (nearest > 3.0) {
			weight[Entity303Animations.SLASH] = 4 + n / 2;
		}
		weight[Entity303Animations.STEP] = nearest > 9.0 ? 4 : 2;
		if (nearest <= 22.0) {
			weight[Entity303Animations.DRAIN] = 2 + n / 3;
		}
		if (fewMinions) {
			weight[Entity303Animations.SUMMON] = 2 + n / 3;
		}
		// the newer moves
		if (nearest <= 6.0) {
			weight[Entity303Animations.COMBO] = 5 + close;
		}
		if (nearest > 5.0 && nearest <= 16.0) {
			weight[Entity303Animations.LUNGE] = 4;
		}
		if (nearest > 8.0 && nearest <= 24.0 && this.headroom(level, 6.0) >= 3.5) {
			weight[Entity303Animations.LEAP] = 3;
		}
		if (nearest > 8.0 && nearest <= 30.0) {
			weight[Entity303Animations.HOOK] = 3 + (n >= 3 ? 1 : 0);
		}
		if (nearest <= 12.0) {
			weight[Entity303Animations.GUARD] = 2 + (phase >= 2 ? 1 : 0);
		}
		if (nearest > 12.0) {
			weight[Entity303Animations.TAUNT] = 1;
		}
		if (phase >= 2) {
			weight[Entity303Animations.SLAM] = 3 + n / 2;
			weight[Entity303Animations.RINGS] = 3 + n / 3;
			weight[Entity303Animations.DANCE] = 3 + (phase >= 3 ? 2 : 0) + (n >= 2 ? 1 : 0);
			weight[Entity303Animations.RAIN] = 3 + n / 2 + (phase >= 3 ? 2 : 0);
			if (this.headroom(level, HOVER_HEIGHT) >= 5.0) {
				weight[Entity303Animations.WHIRL] = 2 + (n >= 3 ? 2 : 0) + (close >= 2 ? 2 : 0);
			}
		}
		if (this.lastAttack >= 0 && this.lastAttack < weight.length) {
			weight[this.lastAttack] = weight[this.lastAttack] / 3;
		}

		int total = 0;
		for (int w : weight) {
			total += w;
		}
		if (total <= 0) {
			return Entity303Animations.SLASH;
		}
		int roll = this.boss.getRandom().nextInt(total);
		for (int attack = 0; attack < weight.length; attack++) {
			roll -= weight[attack];
			if (roll < 0) {
				return attack;
			}
		}
		return Entity303Animations.IDLE;
	}

	private void runAttack(ServerLevel level, int attack, LivingEntity target) {
		int t = this.boss.getAttackTick();
		boolean validTarget = target != null && target.isAlive();
		if (attack != Entity303Animations.WHIRL) {
			this.boss.getNavigation().stop();
			if (validTarget) {
				this.boss.getLookControl().setLookAt(target, 60.0F, 60.0F);
			}
		}
		LivingEntity aim = validTarget ? target : null;

		switch (attack) {
			case Entity303Animations.SWEEP -> this.sweep(level, t);
			case Entity303Animations.SLASH -> this.slash(level, t, aim);
			case Entity303Animations.STEP -> this.step(level, t, aim);
			case Entity303Animations.SUMMON -> this.summon(level, t, aim);
			case Entity303Animations.DRAIN -> this.drain(level, t, aim);
			case Entity303Animations.WHIRL -> this.descent(level, t);
			case Entity303Animations.SLAM -> this.slam(level, t, aim);
			case Entity303Animations.ROAR -> this.roar(level, t, aim);
			case Entity303Animations.COMBO -> this.combo(level, t);
			case Entity303Animations.LUNGE -> this.lunge(level, t, aim);
			case Entity303Animations.LEAP -> this.leap(level, t, aim);
			case Entity303Animations.HOOK -> this.hook(level, t, aim);
			case Entity303Animations.RINGS -> this.rings(level, t);
			case Entity303Animations.DANCE -> this.dance(level, t, aim);
			case Entity303Animations.GUARD -> this.guard(level, t);
			case Entity303Animations.RAIN -> this.rain(level, t);
			case Entity303Animations.SPAWN -> this.spawn(level, t);
			case Entity303Animations.TAUNT -> this.taunt(level, t);
			default -> {
			}
		}

		if (t >= Entity303Animations.duration(attack)) {
			this.finish(attack);
		} else {
			this.boss.setAttackTick(t + 1);
		}
	}

	private void finish(int attack) {
		this.boss.endAttack();
		this.boss.setNoGravity(false);
		int base = switch (attack) {
			case Entity303Animations.SWEEP -> 14;
			case Entity303Animations.SLASH -> 26;
			case Entity303Animations.STEP -> 2;
			case Entity303Animations.SUMMON -> 70;
			case Entity303Animations.DRAIN -> 50;
			case Entity303Animations.WHIRL -> 80;
			case Entity303Animations.SLAM -> 44;
			case Entity303Animations.COMBO -> 16;
			case Entity303Animations.LUNGE -> 18;
			case Entity303Animations.LEAP -> 36;
			case Entity303Animations.HOOK -> 6;
			case Entity303Animations.RINGS -> 40;
			case Entity303Animations.DANCE -> 36;
			case Entity303Animations.GUARD -> 30;
			case Entity303Animations.RAIN -> 44;
			case Entity303Animations.SPAWN -> 40;
			case Entity303Animations.TAUNT -> 10;
			default -> 20;
		};
		// more players around him -> shorter pauses between the attacks
		float pressure = 1.0F / (1.0F + 0.12F * Math.min(this.crowdSize() - 1, 6));
		this.cooldown = Math.max(2, Math.round(base * this.boss.cooldownScale() * pressure));
		if (attack == Entity303Animations.STEP) {
			this.forcedNext = Entity303Animations.SWEEP;
		}
		if (attack == Entity303Animations.HOOK && this.hookLanded) {
			this.forcedNext = Entity303Animations.SWEEP; // whoever he dragged in gets chopped
		}
	}

	// ------------------------------------------------------------- attacks ---
	private void sweep(ServerLevel level, int t) {
		if (t == 0) {
			this.sound(level, SoundEvents.PLAYER_ATTACK_STRONG, 1.6F, 0.6F);
		}
		if (t < Entity303Animations.SWEEP_HIT) {
			this.chargeParticles(level);
		}
		if (t != Entity303Animations.SWEEP_HIT) {
			return;
		}

		this.arcHit(level, 4.4, 14.0F * this.boss.damageMultiplier(), 1.1, 0.45, -0.35);
		this.arcEffects(level, 3.4);
		this.sound(level, SoundEvents.PLAYER_ATTACK_SWEEP, 2.0F, 0.6F);
	}

	/** Everybody in the half circle in front of him (within `radius`, not behind `minDot`) takes a hit. */
	private void arcHit(ServerLevel level, double radius, float damage, double knockback, double lift, double minDot) {
		Vec3 look = this.flatLook();
		AABB box = this.boss.getBoundingBox().inflate(radius, 2.0, radius);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, this::validVictim)) {
			Vec3 d = victim.position().subtract(this.boss.position());
			double flat = Math.sqrt(d.x * d.x + d.z * d.z);
			if (flat > radius + victim.getBbWidth() * 0.5) {
				continue;
			}
			Vec3 dir = flat > 1.0E-4 ? new Vec3(d.x / flat, 0.0, d.z / flat) : look;
			if (dir.dot(look) < minDot) {
				continue;
			}
			this.hit(level, victim, damage, knockback, lift);
		}
	}

	private void arcEffects(ServerLevel level, double reach) {
		Vec3 look = this.flatLook();
		for (int i = -4; i <= 4; i++) {
			Vec3 p = this.boss.position().add(look.yRot((float) (i * 0.38)).scale(reach)).add(0.0, 1.1, 0.0);
			level.sendParticles(ParticleTypes.SWEEP_ATTACK, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 4, 0.25, 0.25, 0.25, 0.04);
		}
	}

	/** Shockwave on the ground around `c`: everybody within `radius` is hit, thrown away and slowed. */
	private void groundBlast(ServerLevel level, Vec3 c, double radius, float damage, double knockback, double lift, int slowTicks) {
		AABB box = new AABB(c.x - radius, c.y - 3.0, c.z - radius, c.x + radius, c.y + 4.0, c.z + radius);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, this::validVictim)) {
			double dx = victim.getX() - c.x;
			double dz = victim.getZ() - c.z;
			double flat = Math.sqrt(dx * dx + dz * dz);
			if (flat > radius) {
				continue;
			}
			if (this.damage(level, victim, level.damageSources().mobAttack(this.boss), damage)) {
				double nx = flat > 1.0E-3 ? dx / flat : 0.0;
				double nz = flat > 1.0E-3 ? dz / flat : 0.0;
				victim.setDeltaMovement(victim.getDeltaMovement().add(nx * knockback, lift, nz * knockback));
				victim.hurtMarked = true;
				if (slowTicks > 0) {
					victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, slowTicks, 1), this.boss);
				}
			}
		}
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 0.5, c.z, 1, 0.0, 0.0, 0.0, 0.0);
		for (int ring = 1; ring <= 3; ring++) {
			double r = Math.min(radius, ring * radius / 3.0);
			for (int i = 0; i < 30; i++) {
				double a = i * (Math.PI * 2.0 / 30.0);
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x + Math.cos(a) * r, c.y + 0.2, c.z + Math.sin(a) * r, 1, 0.0, 0.15, 0.0, 0.05);
			}
		}
		level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 0.3, c.z, 24, radius * 0.4, 0.3, radius * 0.4, 0.04);
		this.sound(level, SoundEvents.LIGHTNING_BOLT_THUNDER, 4.0F, 0.8F);
	}

	private void slash(ServerLevel level, int t, LivingEntity target) {
		if (t == 0) {
			this.sound(level, SoundEvents.ELDER_GUARDIAN_CURSE, 1.0F, 1.4F);
		}
		if (t < Entity303Animations.SLASH_FIRE) {
			this.chargeParticles(level);
		}
		if (t != Entity303Animations.SLASH_FIRE) {
			return;
		}

		// one beam at every player in range: the more of them, the more beams
		List<LivingEntity> targets = new ArrayList<>();
		for (ServerPlayer player : this.crowd()) {
			if (this.boss.distanceTo(player) <= 34.0 && targets.size() < 8) {
				targets.add(player);
			}
		}
		if (target != null && !targets.contains(target)) {
			targets.add(0, target);
		}

		Vec3 origin = this.boss.position().add(0.0, 1.7, 0.0);
		float damage = 11.0F * this.boss.damageMultiplier();
		for (LivingEntity victim : targets) {
			Vec3 aim = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0).subtract(origin);
			if (aim.lengthSqr() < 1.0E-4) {
				continue;
			}
			Vec3 dir = aim.normalize();
			if (this.boss.getPhase() >= 2 && victim == target) {
				for (double offset : new double[] {-0.38, 0.0, 0.38}) {
					this.fireWave(level, origin, dir.yRot((float) offset), damage);
				}
			} else {
				this.fireWave(level, origin, dir, damage);
			}
		}
		this.sound(level, SoundEvents.WITHER_SHOOT, 2.5F, 0.7F);
	}

	private void fireWave(ServerLevel level, Vec3 origin, Vec3 dir, float damage) {
		Vec3 end = origin.add(dir.scale(26.0));
		BlockHitResult hit = level.clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.boss));
		if (hit.getType() != HitResult.Type.MISS) {
			end = hit.getLocation();
		}
		double length = origin.distanceTo(end);

		AABB box = new AABB(origin, end).inflate(1.8);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, this::validVictim)) {
			Vec3 center = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0);
			if (distanceToSegment(center, origin, end) <= 1.5 + victim.getBbWidth() * 0.5) {
				this.hit(level, victim, damage, 0.6, 0.35);
				victim.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0), this.boss);
			}
		}

		for (double d = 0.5; d < length; d += 0.5) {
			Vec3 p = origin.add(dir.scale(d));
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 2, 0.12, 0.12, 0.12, 0.01);
			if (((int) (d / 0.5)) % 5 == 0) {
				level.sendParticles(ParticleTypes.SWEEP_ATTACK, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	private void step(ServerLevel level, int t, LivingEntity target) {
		if (t == 0) {
			// with several players around, he jumps to a random one instead of always the same
			List<ServerPlayer> crowd = this.crowd();
			this.stepTarget = crowd.size() >= 2 ? crowd.get(this.boss.getRandom().nextInt(crowd.size())) : target;
			this.sound(level, SoundEvents.ENDERMAN_TELEPORT, 2.0F, 0.5F);
		}
		if (t < Entity303Animations.STEP_TP) {
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, this.boss.getX(), this.boss.getY() + 1.4, this.boss.getZ(), 8, 0.5, 1.2, 0.5, 0.2);
		}
		LivingEntity to = this.stepTarget != null && this.stepTarget.isAlive() ? this.stepTarget : target;
		if (t == Entity303Animations.STEP_TP && to != null) {
			Vec3 from = this.boss.position();
			if (this.teleportBehind(to)) {
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, from.x, from.y + 1.2, from.z, 40, 0.5, 1.2, 0.5, 0.1);
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, this.boss.getX(), this.boss.getY() + 1.4, this.boss.getZ(), 40, 0.5, 1.2, 0.5, 0.3);
				this.sound(level, SoundEvents.ENDERMAN_TELEPORT, 2.0F, 0.7F);
				this.boss.setTarget(to);
			}
		}
	}

	private boolean teleportBehind(LivingEntity target) {
		Vec3 look = target.getLookAngle();
		Vec3 back = new Vec3(-look.x, 0.0, -look.z);
		back = back.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : back.normalize();
		RandomSource random = this.boss.getRandom();
		for (int i = 0; i < 12; i++) {
			double angle = i == 0 ? 0.0 : (random.nextDouble() - 0.5) * 2.2;
			Vec3 dir = back.yRot((float) angle);
			double distance = 2.6 + (i / 4) * 0.8;
			double x = target.getX() + dir.x * distance;
			double z = target.getZ() + dir.z * distance;
			if (this.boss.randomTeleport(x, target.getY(), z, true)) {
				this.boss.lookAt(target, 360.0F, 360.0F);
				this.boss.getNavigation().stop();
				return true;
			}
		}
		return false;
	}

	private void summon(ServerLevel level, int t, LivingEntity target) {
		if (t == 0) {
			this.sound(level, SoundEvents.EVOKER_PREPARE_SUMMON, 2.0F, 0.6F);
		}
		if (t < Entity303Animations.SUMMON_SPAWN) {
			level.sendParticles(ParticleTypes.SOUL, this.boss.getX(), this.boss.getY() + 0.3, this.boss.getZ(), 4, 1.4, 0.2, 1.4, 0.04);
		}
		if (t == Entity303Animations.SUMMON_SPAWN) {
			this.summonVexes(level, target, 1 + this.boss.getPhase() + (this.crowdSize() - 1));
			this.assignMinionTargets(level);
		}
	}

	private void summonVexes(ServerLevel level, LivingEntity target, int count) {
		int alive = this.countMinions(level);
		int cap = this.maxMinions();
		RandomSource random = this.boss.getRandom();
		for (int i = 0; i < count && alive < cap; i++, alive++) {
			Vex vex = EntityType.VEX.create(level, EntitySpawnReason.MOB_SUMMONED);
			if (vex == null) {
				continue;
			}
			double angle = random.nextDouble() * Math.PI * 2.0;
			double distance = 2.0 + random.nextDouble() * 2.0;
			vex.snapTo(
				this.boss.getX() + Math.cos(angle) * distance,
				this.boss.getY() + 1.5 + random.nextDouble(),
				this.boss.getZ() + Math.sin(angle) * distance,
				this.boss.getYRot(),
				0.0F
			);
			vex.finalizeSpawn(level, level.getCurrentDifficultyAt(vex.blockPosition()), EntitySpawnReason.MOB_SUMMONED, null);
			vex.setOwner(this.boss);
			vex.setBoundOrigin(this.boss.blockPosition());
			vex.setLimitedLife(20 * 25);
			if (target != null) {
				vex.setTarget(target);
			}
			level.addFreshEntity(vex);
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, vex.getX(), vex.getY() + 0.5, vex.getZ(), 20, 0.3, 0.3, 0.3, 0.05);
		}
	}

	private int countMinions(ServerLevel level) {
		AABB area = this.boss.getBoundingBox().inflate(48.0);
		return level.getEntitiesOfClass(Vex.class, area, vex -> vex.getOwner() == this.boss).size();
	}

	private void drain(ServerLevel level, int t, LivingEntity target) {
		if (t == Entity303Animations.DRAIN_START) {
			// he drains as many players as he can handle: more with more players around and in later phases
			this.drainVictims.clear();
			if (target != null) {
				this.drainVictims.add(target);
			}
			int limit = 1 + this.boss.getPhase() + (this.crowdSize() >= 4 ? 1 : 0);
			for (ServerPlayer player : this.crowd()) {
				if (this.drainVictims.size() >= limit) {
					break;
				}
				if (!this.drainVictims.contains(player) && this.boss.distanceTo(player) <= 22.0) {
					this.drainVictims.add(player);
				}
			}
			this.sound(level, SoundEvents.ELDER_GUARDIAN_CURSE, 2.0F, 0.7F);
			for (LivingEntity victim : this.drainVictims) {
				victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 70, 1), this.boss);
				victim.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0), this.boss);
			}
		}
		if (t < Entity303Animations.DRAIN_START || t >= Entity303Animations.DRAIN_END || this.drainVictims.isEmpty()) {
			return;
		}

		Vec3 to = this.boss.position().add(0.0, 1.8, 0.0);
		boolean pulse = (t - Entity303Animations.DRAIN_START) % 4 == 0;
		float damage = 3.0F * this.boss.damageMultiplier();
		int hits = 0;
		for (LivingEntity victim : this.drainVictims) {
			if (!victim.isAlive()) {
				continue;
			}
			Vec3 from = victim.position().add(0.0, victim.getBbHeight() * 0.6, 0.0);
			int points = 14;
			for (int i = 0; i < points; i++) {
				double f = ((i + t * 0.35) % points) / points;
				Vec3 p = from.add(to.subtract(from).scale(f));
				level.sendParticles(ParticleTypes.SOUL, p.x, p.y, p.z, 1, 0.04, 0.04, 0.04, 0.0);
			}
			if (pulse && this.damage(level, victim, level.damageSources().magic(), damage)) {
				hits++;
			}
		}
		if (hits > 0) {
			// phases 1-2: every extra victim heals him a little less than the first; in the final phase the
			// life steal of damage() already pays him back, so the drain does not heal on top of it
			if (this.boss.getPhase() < Entity303.FINAL_PHASE) {
				this.boss.heal(damage * 0.75F * (1.0F + 0.5F * (hits - 1)));
			}
			level.sendParticles(ParticleTypes.SCULK_SOUL, to.x, to.y, to.z, 3, 0.3, 0.3, 0.3, 0.02);
		}
	}

	// ------------------------------------------------------------ newer moves ---
	/** CROSSING SLASHES: a diagonal cut, then an overhead chop that throws everybody up. */
	private void combo(ServerLevel level, int t) {
		if (t == 0) {
			this.sound(level, SoundEvents.PLAYER_ATTACK_STRONG, 1.4F, 0.7F);
		}
		if (t < Entity303Animations.COMBO_HIT1) {
			this.chargeParticles(level);
		}
		if (t == Entity303Animations.COMBO_HIT1) {
			this.arcHit(level, 4.2, 10.0F * this.boss.damageMultiplier(), 0.8, 0.3, -0.1);
			this.arcEffects(level, 3.2);
			this.sound(level, SoundEvents.PLAYER_ATTACK_SWEEP, 2.0F, 0.8F);
		}
		if (t == Entity303Animations.COMBO_HIT2) {
			this.arcHit(level, 4.6, 13.0F * this.boss.damageMultiplier(), 1.4, 0.75, -0.2);
			this.arcEffects(level, 3.6);
			this.sound(level, SoundEvents.PLAYER_ATTACK_SWEEP, 2.0F, 0.5F);
		}
	}

	/** REAPER'S LUNGE: coils, dashes at the target and thrusts the scythe along the way. */
	private void lunge(ServerLevel level, int t, LivingEntity target) {
		if (t == 0) {
			this.sound(level, SoundEvents.WITHER_SHOOT, 1.6F, 1.4F);
		}
		if (t < Entity303Animations.LUNGE_DASH) {
			this.chargeParticles(level);
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, this.boss.getX(), this.boss.getY() + 0.3, this.boss.getZ(), 4, 0.6, 0.2, 0.6, 0.1);
		}
		if (t == Entity303Animations.LUNGE_DASH) {
			Vec3 to = target != null ? target.position().subtract(this.boss.position()) : this.flatLook();
			Vec3 flat = new Vec3(to.x, 0.0, to.z);
			this.lungeDir = flat.lengthSqr() < 1.0E-4 ? this.flatLook() : flat.normalize();
			this.sound(level, SoundEvents.ENDERMAN_TELEPORT, 2.0F, 0.6F);
		}
		if (t >= Entity303Animations.LUNGE_DASH && t < Entity303Animations.LUNGE_HIT) {
			this.boss.setDeltaMovement(this.lungeDir.x * 1.15, this.boss.getDeltaMovement().y, this.lungeDir.z * 1.15);
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, this.boss.getX(), this.boss.getY() + 0.4, this.boss.getZ(), 6, 0.3, 0.3, 0.3, 0.03);
		}
		if (t == Entity303Animations.LUNGE_HIT) {
			this.boss.setDeltaMovement(0.0, this.boss.getDeltaMovement().y, 0.0);
			Vec3 from = this.boss.position().add(0.0, 1.2, 0.0);
			Vec3 end = from.add(this.lungeDir.scale(6.5));
			for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, end).inflate(2.2), this::validVictim)) {
				Vec3 center = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0);
				if (distanceToSegment(center, from, end) <= 1.6 + victim.getBbWidth() * 0.5) {
					if (this.damage(level, victim, level.damageSources().mobAttack(this.boss), 19.0F * this.boss.damageMultiplier())) {
						victim.setDeltaMovement(victim.getDeltaMovement().add(this.lungeDir.x * 1.6, 0.5, this.lungeDir.z * 1.6));
						victim.hurtMarked = true;
					}
				}
			}
			for (double d = 0.5; d <= 6.5; d += 0.5) {
				Vec3 p = from.add(this.lungeDir.scale(d));
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 3, 0.15, 0.15, 0.15, 0.02);
			}
			level.sendParticles(ParticleTypes.SWEEP_ATTACK, end.x, end.y, end.z, 1, 0.0, 0.0, 0.0, 0.0);
			this.sound(level, SoundEvents.PLAYER_ATTACK_CRIT, 2.0F, 0.6F);
		}
	}

	/** DEATH LEAP: jumps in an arc at the target and crashes down on the spot. */
	private void leap(ServerLevel level, int t, LivingEntity target) {
		if (t == 0) {
			this.sound(level, SoundEvents.RAVAGER_ROAR, 2.0F, 0.7F);
		}
		if (t < Entity303Animations.LEAP_TAKEOFF) {
			level.sendParticles(ParticleTypes.SOUL, this.boss.getX(), this.boss.getY() + 0.2, this.boss.getZ(), 4, 0.9, 0.1, 0.9, 0.03);
		}
		if (t == Entity303Animations.LEAP_TAKEOFF) {
			this.leapFrom = this.boss.position();
			Vec3 aim = target != null ? target.position() : this.leapFrom.add(this.flatLook().scale(8.0));
			Vec3 d = new Vec3(aim.x - this.leapFrom.x, 0.0, aim.z - this.leapFrom.z);
			double dist = d.length();
			Vec3 dir = dist < 1.0E-3 ? this.flatLook() : d.scale(1.0 / dist);
			double reach = Mth.clamp(dist - 1.5, 0.0, 22.0);
			this.leapTo = new Vec3(this.leapFrom.x + dir.x * reach, aim.y, this.leapFrom.z + dir.z * reach);
			this.leapHeight = Mth.clamp(this.headroom(level, 6.0) - 1.0, 1.5, 5.5);
			this.boss.setNoGravity(true);
			this.sound(level, SoundEvents.ENDER_DRAGON_FLAP, 3.0F, 0.7F);
		}
		if (t > Entity303Animations.LEAP_TAKEOFF && t <= Entity303Animations.LEAP_LAND) {
			double f = (t - Entity303Animations.LEAP_TAKEOFF) / (double) (Entity303Animations.LEAP_LAND - Entity303Animations.LEAP_TAKEOFF);
			double x = Mth.lerp(f, this.leapFrom.x, this.leapTo.x);
			double z = Mth.lerp(f, this.leapFrom.z, this.leapTo.z);
			double y = Mth.lerp(f, this.leapFrom.y, this.leapTo.y) + Math.sin(f * Math.PI) * this.leapHeight;
			this.boss.setDeltaMovement(x - this.boss.getX(), y - this.boss.getY(), z - this.boss.getZ());
			this.boss.fallDistance = 0.0;
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, this.boss.getX(), this.boss.getY() + 0.5, this.boss.getZ(), 5, 0.3, 0.4, 0.3, 0.03);
		}
		if (t == Entity303Animations.LEAP_LAND) {
			this.boss.setNoGravity(false);
			this.boss.setDeltaMovement(0.0, -0.5, 0.0);
			this.groundBlast(level, this.boss.position(), 5.5, 17.0F * this.boss.damageMultiplier(), 1.3, 0.7, 40);
			this.sound(level, SoundEvents.ENDER_DRAGON_GROWL, 3.0F, 0.8F);
		}
	}

	/** SOUL HOOK: a chain of souls drags the far-away players to him. */
	private void hook(ServerLevel level, int t, LivingEntity target) {
		if (t == 0) {
			this.hookVictims.clear();
			List<ServerPlayer> far = new ArrayList<>();
			for (ServerPlayer player : this.crowd()) {
				if (this.boss.distanceTo(player) > 8.0 && this.boss.distanceTo(player) <= 32.0) {
					far.add(player);
				}
			}
			far.sort(Comparator.comparingDouble(p -> -p.distanceToSqr(this.boss)));
			int limit = this.boss.getPhase() >= 2 ? 3 : 2;
			for (ServerPlayer player : far) {
				if (this.hookVictims.size() < limit) {
					this.hookVictims.add(player);
				}
			}
			if (this.hookVictims.isEmpty() && target != null) {
				this.hookVictims.add(target);
			}
			this.sound(level, SoundEvents.WITHER_SHOOT, 2.5F, 0.5F);
		}
		Vec3 hand = this.boss.position().add(this.flatLook().scale(1.0)).add(0.0, 2.0, 0.0);
		if (t >= Entity303Animations.HOOK_THROW && t <= Entity303Animations.HOOK_YANK) {
			for (LivingEntity victim : this.hookVictims) {
				if (!victim.isAlive()) {
					continue;
				}
				Vec3 to = victim.position().add(0.0, victim.getBbHeight() * 0.6, 0.0);
				double f = Math.min(1.0, (t - Entity303Animations.HOOK_THROW + 1) / (double) (Entity303Animations.HOOK_YANK - Entity303Animations.HOOK_THROW));
				int points = 18;
				for (int i = 0; i < points; i++) {
					Vec3 p = hand.add(to.subtract(hand).scale(f * i / points));
					level.sendParticles(ParticleTypes.SOUL, p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0.0);
				}
			}
		}
		if (t == Entity303Animations.HOOK_YANK) {
			for (LivingEntity victim : this.hookVictims) {
				if (!victim.isAlive()) {
					continue;
				}
				Vec3 eye = victim.position().add(0.0, victim.getBbHeight() * 0.6, 0.0);
				BlockHitResult wall = level.clip(new ClipContext(hand, eye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.boss));
				if (wall.getType() != HitResult.Type.MISS) {
					continue; // a wall is in the way: the chain does not reach
				}
				Vec3 pull = this.boss.position().subtract(victim.position());
				double flat = Math.sqrt(pull.x * pull.x + pull.z * pull.z);
				double speed = Mth.clamp(0.5 + flat * 0.07, 0.8, 2.4);
				double nx = flat > 1.0E-3 ? pull.x / flat : 0.0;
				double nz = flat > 1.0E-3 ? pull.z / flat : 0.0;
				this.damage(level, victim, level.damageSources().mobAttack(this.boss), 6.0F * this.boss.damageMultiplier());
				victim.setDeltaMovement(nx * speed, 0.35, nz * speed);
				victim.hurtMarked = true;
				this.hookLanded = true;
			}
			this.sound(level, SoundEvents.WARDEN_SONIC_CHARGE, 2.0F, 1.3F);
		}
	}

	/** SOUL RINGS: three stomps, each sends a ring of soul fire over the floor that hurts whoever stays on the ground. */
	private void rings(ServerLevel level, int t) {
		if (t == 0) {
			this.rings.clear();
			this.sound(level, SoundEvents.RAVAGER_ROAR, 2.5F, 0.6F);
		}
		if (t < Entity303Animations.RINGS_STOMP) {
			this.chargeParticles(level);
		}
		if (t == Entity303Animations.RINGS_STOMP || t == Entity303Animations.RINGS_STOMP2 || t == Entity303Animations.RINGS_STOMP3) {
			this.rings.add(new Ring(t, this.boss.position(), new HashSet<>()));
			this.sound(level, SoundEvents.LIGHTNING_BOLT_THUNDER, 3.0F, 0.9F);
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, this.boss.getX(), this.boss.getY() + 0.3, this.boss.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
		}
		for (Ring ring : this.rings) {
			int age = t - ring.start();
			if (age < 1 || age > 18) {
				continue;
			}
			double radius = 1.5 + age * 1.05;
			Vec3 c = ring.center();
			int points = Math.max(24, (int) (radius * 6.0));
			for (int i = 0; i < points; i++) {
				double a = i * (Math.PI * 2.0 / points);
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x + Math.cos(a) * radius, c.y + 0.15, c.z + Math.sin(a) * radius, 1, 0.0, 0.1, 0.0, 0.01);
			}
			AABB box = new AABB(c.x - radius - 1.5, c.y - 1.0, c.z - radius - 1.5, c.x + radius + 1.5, c.y + 3.0, c.z + radius + 1.5);
			for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, this::validVictim)) {
				double dx = victim.getX() - c.x;
				double dz = victim.getZ() - c.z;
				double flat = Math.sqrt(dx * dx + dz * dz);
				boolean onTheGround = victim.getY() <= c.y + 0.9;
				if (!onTheGround || Math.abs(flat - radius) > 1.2 || ring.hit().contains(victim.getId())) {
					continue;
				}
				ring.hit().add(victim.getId());
				if (this.damage(level, victim, level.damageSources().mobAttack(this.boss), 13.0F * this.boss.damageMultiplier())) {
					double nx = flat > 1.0E-3 ? dx / flat : 0.0;
					double nz = flat > 1.0E-3 ? dz / flat : 0.0;
					victim.setDeltaMovement(victim.getDeltaMovement().add(nx * 0.6, 0.65, nz * 0.6));
					victim.hurtMarked = true;
				}
			}
		}
	}

	/** PHANTOM DANCE: three jumps behind three different players, a chop after each. */
	private void dance(ServerLevel level, int t, LivingEntity target) {
		if (t == 0) {
			this.sound(level, SoundEvents.ENDERMAN_TELEPORT, 2.0F, 0.5F);
		}
		boolean teleport = t == Entity303Animations.DANCE_TP1 || t == Entity303Animations.DANCE_TP2 || t == Entity303Animations.DANCE_TP3;
		boolean chop = t == Entity303Animations.DANCE_HIT1 || t == Entity303Animations.DANCE_HIT2 || t == Entity303Animations.DANCE_HIT3;
		if (t < Entity303Animations.DANCE_HIT3 && !chop) {
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, this.boss.getX(), this.boss.getY() + 1.4, this.boss.getZ(), 5, 0.5, 1.2, 0.5, 0.2);
		}
		if (teleport) {
			List<ServerPlayer> crowd = this.crowd();
			LivingEntity to = target;
			if (crowd.size() >= 2) {
				for (int tries = 0; tries < 4; tries++) {
					ServerPlayer pick = crowd.get(this.boss.getRandom().nextInt(crowd.size()));
					if (pick != this.danceLast) {
						to = pick;
						break;
					}
				}
			}
			if (to != null && to.isAlive()) {
				Vec3 from = this.boss.position();
				if (this.teleportBehind(to)) {
					level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, from.x, from.y + 1.2, from.z, 40, 0.5, 1.2, 0.5, 0.1);
					level.sendParticles(ParticleTypes.REVERSE_PORTAL, this.boss.getX(), this.boss.getY() + 1.4, this.boss.getZ(), 40, 0.5, 1.2, 0.5, 0.3);
					this.sound(level, SoundEvents.ENDERMAN_TELEPORT, 2.0F, 0.7F + 0.15F * (this.danceLast == null ? 0 : 1));
					this.boss.setTarget(to);
					this.danceLast = to;
				}
			}
		}
		if (chop) {
			this.arcHit(level, 3.8, 11.0F * this.boss.damageMultiplier(), 0.9, 0.4, -0.2);
			this.arcEffects(level, 3.0);
			this.sound(level, SoundEvents.PLAYER_ATTACK_SWEEP, 2.0F, 0.7F);
		}
	}

	/** REAPER'S GUARD: almost no damage gets through (see Entity303#hurtServer); the burst hits harder for every blow he took. */
	private void guard(ServerLevel level, int t) {
		if (t == 0) {
			this.boss.consumeGuardHits();
			this.sound(level, SoundEvents.ELDER_GUARDIAN_CURSE, 1.5F, 1.2F);
		}
		if (t >= Entity303Animations.GUARD_UP && t < Entity303Animations.GUARD_BURST) {
			double spin = t * 0.5;
			for (int i = 0; i < 6; i++) {
				double a = spin + i * (Math.PI * 2.0 / 6.0);
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, this.boss.getX() + Math.cos(a) * 1.7, this.boss.getY() + 1.0 + (i % 3) * 0.7,
					this.boss.getZ() + Math.sin(a) * 1.7, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
		if (t == Entity303Animations.GUARD_BURST) {
			int hits = Math.min(12, this.boss.consumeGuardHits());
			float damage = 9.0F * this.boss.damageMultiplier() * (1.0F + 0.12F * hits);
			this.sound(level, SoundEvents.WITHER_SPAWN, 3.0F, 1.1F);
			this.groundBlast(level, this.boss.position(), 9.0, damage, 2.0, 0.8, 0);
		}
	}

	/** SOUL RAIN: waves of soul-fire columns fall around the players; every one is marked on the ground first. */
	private void rain(ServerLevel level, int t) {
		if (t == 0) {
			this.columns.clear();
			this.sound(level, SoundEvents.EVOKER_PREPARE_SUMMON, 2.5F, 0.5F);
		}
		if (t < Entity303Animations.RAIN_START) {
			level.sendParticles(ParticleTypes.SOUL, this.boss.getX(), this.boss.getY() + 3.4, this.boss.getZ(), 5, 0.9, 0.3, 0.9, 0.03);
		}
		if (t >= Entity303Animations.RAIN_START && t <= Entity303Animations.RAIN_END && t % 3 == 0) {
			List<ServerPlayer> crowd = this.crowd();
			RandomSource random = this.boss.getRandom();
			int perWave = 1 + this.crowdSize() / 3 + (this.boss.getPhase() >= 3 ? 1 : 0);
			for (int i = 0; i < perWave; i++) {
				Vec3 base = crowd.isEmpty() ? this.boss.position() : crowd.get(random.nextInt(crowd.size())).position();
				this.columns.add(new Column(new Vec3(base.x + (random.nextDouble() - 0.5) * 7.0, base.y, base.z + (random.nextDouble() - 0.5) * 7.0), t + 14));
			}
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, this.boss.getX(), this.boss.getY() + 3.2, this.boss.getZ(), 10, 0.6, 0.3, 0.6, 0.05);
		}
		for (Iterator<Column> it = this.columns.iterator(); it.hasNext(); ) {
			Column column = it.next();
			Vec3 p = column.pos();
			if (t < column.strike()) {
				double progress = 1.0 - (column.strike() - t) / 14.0;
				for (int i = 0; i < 10; i++) {
					double a = i * (Math.PI * 2.0 / 10.0) + t * 0.3;
					level.sendParticles(RED_DUST, p.x + Math.cos(a) * 1.9, p.y + 0.1, p.z + Math.sin(a) * 1.9, 1, 0.0, 0.0, 0.0, 0.0);
				}
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y + 0.1 + progress * 0.5, p.z, 1, 0.5 * (1.0 - progress), 0.0, 0.5 * (1.0 - progress), 0.0);
				continue;
			}
			it.remove();
			AABB box = new AABB(p.x - 1.9, p.y - 1.0, p.z - 1.9, p.x + 1.9, p.y + 3.0, p.z + 1.9);
			for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, this::validVictim)) {
				double dx = victim.getX() - p.x;
				double dz = victim.getZ() - p.z;
				if (dx * dx + dz * dz > 1.9 * 1.9) {
					continue;
				}
				if (this.damage(level, victim, level.damageSources().mobAttack(this.boss), 12.0F * this.boss.damageMultiplier())) {
					victim.setDeltaMovement(victim.getDeltaMovement().add(0.0, 0.8, 0.0));
					victim.hurtMarked = true;
				}
			}
			for (double y = 0.0; y < 9.0; y += 0.6) {
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y + y, p.z, 3, 0.25, 0.1, 0.25, 0.02);
			}
			level.sendParticles(ParticleTypes.EXPLOSION, p.x, p.y + 0.4, p.z, 1, 0.0, 0.0, 0.0, 0.0);
			level.playSound(null, p.x, p.y, p.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 1.2F, 1.3F);
		}
	}

	/** SPAWN: he climbs out of the ground, invulnerable, and bursts the people around him away. */
	private void spawn(ServerLevel level, int t) {
		if (t == 0) {
			this.boss.makeInvulnerableFor(Entity303Animations.duration(Entity303Animations.SPAWN));
			this.sound(level, SoundEvents.WITHER_SPAWN, 4.0F, 0.6F);
		}
		if (t < Entity303Animations.SPAWN_BURST) {
			double r = 5.0 * (1.0 - t / (double) Entity303Animations.SPAWN_BURST) + 1.0;
			for (int i = 0; i < 10; i++) {
				double a = i * (Math.PI * 2.0 / 10.0) + t * 0.3;
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, this.boss.getX() + Math.cos(a) * r, this.boss.getY() + 0.1, this.boss.getZ() + Math.sin(a) * r, 1, 0.0, 0.05, 0.0, 0.0);
			}
			level.sendParticles(ParticleTypes.LARGE_SMOKE, this.boss.getX(), this.boss.getY() + 0.2, this.boss.getZ(), 3, 0.8, 0.1, 0.8, 0.02);
		}
		if (t == Entity303Animations.SPAWN_BURST) {
			this.sound(level, SoundEvents.ENDER_DRAGON_GROWL, 5.0F, 0.6F);
			AABB box = this.boss.getBoundingBox().inflate(12.0, 4.0, 12.0);
			for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, this::validVictim)) {
				Vec3 away = victim.position().subtract(this.boss.position());
				double flat = Math.sqrt(away.x * away.x + away.z * away.z);
				Vec3 dir = flat > 1.0E-3 ? new Vec3(away.x / flat, 0.0, away.z / flat) : new Vec3(1.0, 0.0, 0.0);
				victim.setDeltaMovement(victim.getDeltaMovement().add(dir.x * 1.5, 0.5, dir.z * 1.5));
				victim.hurtMarked = true;
			}
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, this.boss.getX(), this.boss.getY() + 1.0, this.boss.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** TAUNT: pure show, no damage. */
	private void taunt(ServerLevel level, int t) {
		if (t == 4) {
			this.sound(level, SoundEvents.ENDER_DRAGON_GROWL, 2.5F, 1.4F);
		}
		if (t == 28) {
			this.sound(level, SoundEvents.WITHER_AMBIENT, 3.0F, 0.6F);
		}
		if (t > 8 && t < 48) {
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, this.boss.getX(), this.boss.getY() + 3.0, this.boss.getZ(), 2, 0.4, 0.3, 0.4, 0.02);
		}
	}

	// -------------------------------------------------- Reaper's Descent ----
	private void descent(ServerLevel level, int t) {
		if (t == 0) {
			this.hoverBaseY = this.boss.getY();
			this.hoverTopY = this.hoverBaseY + Math.max(2.0, this.headroom(level, HOVER_HEIGHT));
			this.boss.setNoGravity(true);
			this.sound(level, SoundEvents.WITHER_SPAWN, 3.0F, 1.3F);
		}

		// ---- where he is: rise, hover, dive
		double targetY;
		if (t < Entity303Animations.WHIRL_RISE) {
			double f = t / (double) Entity303Animations.WHIRL_RISE;
			targetY = Mth.lerp(1.0 - (1.0 - f) * (1.0 - f), this.hoverBaseY, this.hoverTopY);
		} else if (t < Entity303Animations.WHIRL_DIVE) {
			targetY = this.hoverTopY + Math.sin(t * 0.3) * 0.15;
		} else if (t < Entity303Animations.WHIRL_IMPACT) {
			double f = (t - Entity303Animations.WHIRL_DIVE + 1) / (double) (Entity303Animations.WHIRL_IMPACT - Entity303Animations.WHIRL_DIVE);
			targetY = Mth.lerp(f * f, this.hoverTopY, this.hoverBaseY);
		} else {
			targetY = this.hoverBaseY;
		}
		this.boss.getNavigation().stop();
		this.boss.setDeltaMovement(0.0, targetY - this.boss.getY(), 0.0);
		this.boss.fallDistance = 0.0;

		// ---- everybody is dragged down to the floor under him
		if (t >= 4 && t < Entity303Animations.WHIRL_IMPACT) {
			this.pullEveryoneDown(level, t);
		}
		if (t == Entity303Animations.WHIRL_PULL_END) {
			this.sound(level, SoundEvents.WARDEN_SONIC_CHARGE, 4.0F, 0.8F);
		}
		if (t > Entity303Animations.WHIRL_PULL_END && t < Entity303Animations.WHIRL_IMPACT) {
			level.sendParticles(RED_DUST, this.boss.getX(), this.boss.getY() + 1.5, this.boss.getZ(), 8, 1.2, 1.5, 1.2, 0.0);
		}

		if (t == Entity303Animations.WHIRL_IMPACT) {
			this.boss.setNoGravity(false);
			this.descentImpact(level);
		}
	}

	private void pullEveryoneDown(ServerLevel level, int t) {
		double cx = this.boss.getX();
		double cz = this.boss.getZ();
		double ground = this.hoverBaseY;
		AABB box = this.boss.getBoundingBox().inflate(PULL_RADIUS, 14.0, PULL_RADIUS);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, this::validVictim)) {
			double dx = cx - victim.getX();
			double dz = cz - victim.getZ();
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (dist > PULL_RADIUS) {
				continue;
			}
			Vec3 motion = victim.getDeltaMovement();
			double pull = dist < 1.0 ? 0.0 : Mth.clamp(0.16 + dist * 0.012, 0.16, 0.42);
			double nx = dist < 1.0E-3 ? 0.0 : dx / dist;
			double nz = dist < 1.0E-3 ? 0.0 : dz / dist;
			// pinned to the floor: no jumping, no flying away
			victim.setDeltaMovement(motion.x * 0.3 + nx * pull, Math.min(motion.y, -0.2), motion.z * 0.3 + nz * pull);
			victim.fallDistance = 0.0;
			victim.hurtMarked = true;
		}

		// the vortex on the floor: rings that shrink towards him
		double spin = t * 0.45;
		for (int ring = 0; ring < 3; ring++) {
			double radius = 3.0 + ((t * 0.5 + ring * 7.0) % 20.0);
			double r = Math.max(2.0, 22.0 - radius);
			for (int i = 0; i < 8; i++) {
				double a = spin + i * (Math.PI * 2.0 / 8.0) + ring;
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, cx + Math.cos(a) * r, ground + 0.15, cz + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
		if (t % 6 == 0) {
			this.sound(level, SoundEvents.PLAYER_ATTACK_SWEEP, 2.0F, 0.5F);
		}
	}

	private void descentImpact(ServerLevel level) {
		Vec3 c = this.boss.position();
		AABB box = new AABB(c.x - IMPACT_RADIUS, c.y - 4.0, c.z - IMPACT_RADIUS, c.x + IMPACT_RADIUS, c.y + 5.0, c.z + IMPACT_RADIUS);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, this::validVictim)) {
			double dx = victim.getX() - c.x;
			double dz = victim.getZ() - c.z;
			double flat = Math.sqrt(dx * dx + dz * dz);
			if (flat > IMPACT_RADIUS) {
				continue;
			}
			this.descentHit(level, victim, flat > 1.0E-3 ? dx / flat : 0.0, flat > 1.0E-3 ? dz / flat : 0.0);
		}

		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 0.5, c.z, 1, 0.0, 0.0, 0.0, 0.0);
		for (int ring = 1; ring <= 4; ring++) {
			for (int i = 0; i < 40; i++) {
				double a = i * (Math.PI * 2.0 / 40.0);
				double r = ring * 2.0;
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x + Math.cos(a) * r, c.y + 0.2, c.z + Math.sin(a) * r, 1, 0.0, 0.15, 0.0, 0.05);
			}
		}
		level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 0.3, c.z, 40, 3.0, 0.3, 3.0, 0.05);
		this.sound(level, SoundEvents.LIGHTNING_BOLT_THUNDER, 6.0F, 0.6F);
		this.sound(level, SoundEvents.ENDER_DRAGON_GROWL, 4.0F, 0.7F);
	}

	/** Half (75% in the final phase) of the victim's maximum health, whatever armor, enchantments, effects or shield they have. */
	private void descentHit(ServerLevel level, LivingEntity victim, double nx, double nz) {
		float fraction = this.boss.getPhase() >= Entity303.FINAL_PHASE ? DESCENT_FRACTION_FINAL : DESCENT_FRACTION;
		float amount = victim.getMaxHealth() * fraction;
		if (this.damage(level, victim, this.descentSource(level), amount)) {
			victim.setDeltaMovement(victim.getDeltaMovement().add(nx * 1.2, 0.7, nz * 1.2));
			victim.hurtMarked = true;
		}
	}

	private DamageSource descentSource(ServerLevel level) {
		try {
			Holder<DamageType> type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DESCENT_DAMAGE);
			return new DamageSource(type, this.boss);
		} catch (RuntimeException missing) {
			return level.damageSources().mobAttack(this.boss);
		}
	}

	/** Free space above his head (blocks), at most `max`. */
	private double headroom(ServerLevel level, double max) {
		Vec3 start = new Vec3(this.boss.getX(), this.boss.getY() + this.boss.getBbHeight(), this.boss.getZ());
		BlockHitResult hit = level.clip(new ClipContext(start, start.add(0.0, max + 0.5, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.boss));
		if (hit.getType() == HitResult.Type.MISS) {
			return max;
		}
		return Math.max(0.0, Math.min(max, hit.getLocation().y - start.y - 0.3));
	}

	// ----------------------------------------------------------- slam / roar --
	private void slam(ServerLevel level, int t, LivingEntity target) {
		if (t == Entity303Animations.SLAM_MARK) {
			this.slamPoints.clear();
			for (ServerPlayer player : this.crowd()) {
				if (this.slamPoints.size() < 10 && this.boss.distanceTo(player) < 28.0) {
					this.slamPoints.add(player.position());
				}
			}
			if (target != null) {
				RandomSource random = this.boss.getRandom();
				int extra = Math.max(2, this.crowdSize() / 2);
				for (int i = 0; i < extra; i++) {
					this.slamPoints.add(target.position().add((random.nextDouble() - 0.5) * 7.0, 0.0, (random.nextDouble() - 0.5) * 7.0));
				}
			}
			this.sound(level, SoundEvents.ELDER_GUARDIAN_CURSE, 2.0F, 0.5F);
		}

		if (t >= Entity303Animations.SLAM_MARK && t < Entity303Animations.SLAM_STRIKE) {
			double progress = (t - Entity303Animations.SLAM_MARK) / (double) (Entity303Animations.SLAM_STRIKE - Entity303Animations.SLAM_MARK);
			for (Vec3 point : this.slamPoints) {
				for (int i = 0; i < 14; i++) {
					double a = i * (Math.PI * 2.0 / 14.0) + t * 0.2;
					level.sendParticles(RED_DUST, point.x + Math.cos(a) * 3.3, point.y + 0.1, point.z + Math.sin(a) * 3.3, 1, 0.0, 0.0, 0.0, 0.0);
					double inner = 3.3 * (1.0 - progress);
					level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, point.x + Math.cos(a) * inner, point.y + 0.1, point.z + Math.sin(a) * inner, 1, 0.0, 0.0, 0.0, 0.0);
				}
			}
		}

		if (t == Entity303Animations.SLAM_STRIKE) {
			for (Vec3 point : this.slamPoints) {
				level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, point.x, point.y + 0.5, point.z, 1, 0.0, 0.0, 0.0, 0.0);
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, point.x, point.y + 0.2, point.z, 80, 1.4, 0.3, 1.4, 0.35);
				level.playSound(null, point.x, point.y, point.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 3.0F, 0.8F);
				AABB box = new AABB(point.x - 3.4, point.y - 1.0, point.z - 3.4, point.x + 3.4, point.y + 3.0, point.z + 3.4);
				for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, this::validVictim)) {
					double dx = victim.getX() - point.x;
					double dz = victim.getZ() - point.z;
					if (dx * dx + dz * dz > 3.4 * 3.4) {
						continue;
					}
					if (this.damage(level, victim, level.damageSources().mobAttack(this.boss), 18.0F * this.boss.damageMultiplier())) {
						victim.setDeltaMovement(victim.getDeltaMovement().add(0.0, 0.9, 0.0));
						victim.hurtMarked = true;
					}
				}
			}
			this.slamPoints.clear();
		}
	}

	private void roar(ServerLevel level, int t, LivingEntity target) {
		if (t == 0) {
			this.boss.makeInvulnerableFor(Entity303Animations.duration(Entity303Animations.ROAR));
			this.sound(level, SoundEvents.ENDER_DRAGON_GROWL, 5.0F, 0.6F);
		}
		if (t < Entity303Animations.ROAR_BURST) {
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, this.boss.getX(), this.boss.getY() + 1.0, this.boss.getZ(), 10, 0.8, 1.2, 0.8, 0.05);
		}
		if (t != Entity303Animations.ROAR_BURST) {
			return;
		}

		this.sound(level, SoundEvents.WITHER_SPAWN, 4.0F, 0.9F);
		AABB box = this.boss.getBoundingBox().inflate(14.0, 4.0, 14.0);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, this::validVictim)) {
			Vec3 away = victim.position().subtract(this.boss.position());
			double flat = Math.sqrt(away.x * away.x + away.z * away.z);
			Vec3 dir = flat > 1.0E-3 ? new Vec3(away.x / flat, 0.0, away.z / flat) : new Vec3(1.0, 0.0, 0.0);
			if (this.damage(level, victim, level.damageSources().mobAttack(this.boss), 6.0F * Entity303.DAMAGE_SCALE)) {
				victim.setDeltaMovement(victim.getDeltaMovement().add(dir.x * 1.8, 0.6, dir.z * 1.8));
				victim.hurtMarked = true;
			}
		}
		for (int i = 0; i < 36; i++) {
			double a = i * (Math.PI * 2.0 / 36.0);
			for (double r = 1.5; r <= 12.0; r += 2.5) {
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, this.boss.getX() + Math.cos(a) * r, this.boss.getY() + 0.2, this.boss.getZ() + Math.sin(a) * r, 1, 0.0, 0.1, 0.0, 0.02);
			}
		}
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, this.boss.getX(), this.boss.getY() + 1.0, this.boss.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
		this.summonVexes(level, target, 2 + this.boss.getPhase() + (this.crowdSize() - 1));
		this.assignMinionTargets(level);
	}

	// ------------------------------------------------------------- helpers ---
	private boolean validVictim(Entity entity) {
		if (entity == this.boss || !(entity instanceof LivingEntity living) || !living.isAlive() || entity instanceof Entity303) {
			return false;
		}
		if (entity instanceof Vex vex && vex.getOwner() == this.boss) {
			return false;
		}
		return !(entity instanceof Player player && (player.isCreative() || player.isSpectator()));
	}

	/**
	 * Every point of damage he does goes through here. In the final phase he steals
	 * {@link Entity303#LIFESTEAL_FRACTION} of the health (and absorption) the victim actually lost.
	 */
	private boolean damage(ServerLevel level, LivingEntity victim, DamageSource source, float amount) {
		float before = victim.getHealth() + victim.getAbsorptionAmount();
		boolean hurt = victim.hurtServer(level, source, amount);
		if (hurt && this.boss.getPhase() >= Entity303.FINAL_PHASE) {
			float taken = before - (victim.getHealth() + victim.getAbsorptionAmount());
			if (taken > 0.0F) {
				this.boss.heal(taken * Entity303.LIFESTEAL_FRACTION);
				level.sendParticles(ParticleTypes.SCULK_SOUL, this.boss.getX(), this.boss.getY() + 1.8, this.boss.getZ(), 2, 0.3, 0.4, 0.3, 0.02);
			}
		}
		return hurt;
	}

	private void hit(ServerLevel level, LivingEntity victim, float damage, double knockback, double lift) {
		if (!this.damage(level, victim, level.damageSources().mobAttack(this.boss), damage)) {
			return;
		}
		Vec3 away = victim.position().subtract(this.boss.position());
		away = new Vec3(away.x, 0.0, away.z);
		if (away.lengthSqr() > 1.0E-4) {
			away = away.normalize();
		}
		victim.setDeltaMovement(victim.getDeltaMovement().add(away.x * knockback, lift, away.z * knockback));
		victim.hurtMarked = true;
	}

	private Vec3 flatLook() {
		Vec3 look = this.boss.getLookAngle();
		Vec3 flat = new Vec3(look.x, 0.0, look.z);
		return flat.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
	}

	private void chargeParticles(ServerLevel level) {
		Vec3 p = this.boss.position().add(this.flatLook().scale(1.4)).add(0.0, 2.4, 0.0);
		level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 2, 0.3, 0.5, 0.3, 0.02);
	}

	private void sound(ServerLevel level, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, this.boss.getX(), this.boss.getY(), this.boss.getZ(), sound, SoundSource.HOSTILE, volume, pitch);
	}

	private static double distanceToSegment(Vec3 point, Vec3 a, Vec3 b) {
		Vec3 ab = b.subtract(a);
		double lengthSqr = ab.lengthSqr();
		double f = lengthSqr < 1.0E-6 ? 0.0 : Mth.clamp(point.subtract(a).dot(ab) / lengthSqr, 0.0, 1.0);
		return point.distanceTo(a.add(ab.scale(f)));
	}
}
