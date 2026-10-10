package com.entity303.entity;

import com.entity303.anim.Entity303Animations;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.damagesource.CombatRules;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Entity 303: a 1500 HP boss with a custom boss bar, three phases and a set of special attacks
 * (see {@link Entity303AttackGoal}). The animation timing lives in tools/animations.py.
 */
public class Entity303 extends Monster {
	private static final EntityDataAccessor<Integer> DATA_ATTACK =
		SynchedEntityData.defineId(Entity303.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_ATTACK_TICK =
		SynchedEntityData.defineId(Entity303.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_PHASE =
		SynchedEntityData.defineId(Entity303.class, EntityDataSerializers.INT);

	private static final float TWO_PI = (float) (Math.PI * 2.0);

	public static final double MAX_HEALTH = 1500.0;
	/** Every damage number of the attacks is multiplied by this (on top of the +25% / +50% of phases 2 and 3). */
	public static final float DAMAGE_SCALE = 2.0F;
	/** Twice the armor of the first version (8). */
	public static final double ARMOR = 16.0;
	private static final double[] PHASE_SPEED = {0.30, 0.34, 0.38};
	/** The last phase ("FINAL FORM"): effect immunity and life steal. */
	public static final int FINAL_PHASE = 3;
	/** In the final phase he heals this fraction of the health he takes from players. */
	public static final float LIFESTEAL_FRACTION = 0.5F;
	/** In the final phase every one of his attacks hits this many times harder (was 2.0, lowered a little). */
	public static final float FINAL_DAMAGE_BONUS = 1.5F;
	/** Every player beyond the first one near him (up to CROWD_CAP players) makes his attacks hit this much harder... */
	public static final float CROWD_DAMAGE_PER_PLAYER = 0.10F;
	/** ...and makes him take this much less damage, but never more than CROWD_TOUGHNESS_MAX (no crowd makes him unkillable). */
	public static final float CROWD_TOUGHNESS_PER_PLAYER = 0.06F;
	public static final float CROWD_TOUGHNESS_MAX = 0.24F;
	public static final int CROWD_CAP = 8;
	/** Health he can steal back (life steal, Soul Drain): at most this much per tick on average, saved up to a maximum. */
	public static final float HEAL_PER_TICK = 0.4F;
	public static final float HEAL_BUDGET_MAX = 30.0F;
	/** A target farther away than this (blocks) is lost: he switches to a nearer player at once. */
	public static final double TARGET_REACH = 40.0;
	/** While he is guarding (Reaper's Guard) he only takes this fraction of the damage. */
	public static final float GUARD_DAMAGE_FACTOR = 0.2F;
	/** How long the death animation plays before he disappears (ticks). */
	public static final int DEATH_TICKS = Entity303Animations.duration(Entity303Animations.DEATH) + 10;

	private final ServerBossEvent bossEvent = (ServerBossEvent) new ServerBossEvent(
		Component.empty(), BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.NOTCHED_20
	).setDarkenScreen(true);

	private int invulnerableTicks;
	private boolean roarRequested;
	/** Set when he is freshly summoned: the attack goal then plays the "climbs out of the ground" intro. */
	private boolean introPending;
	private int crowdSize = 1;
	private float healBudget = HEAL_BUDGET_MAX;
	/** Hits taken while guarding: the counter burst of Reaper's Guard grows with them. */
	private int guardHits;

	// client side only: the scythe's accumulated spin (radians), previous values for interpolation
	public float spinX;
	public float spinY;
	public float spinXO;
	public float spinYO;
	private final float[] animScratch = new float[Entity303Animations.CHANNELS];
	private final float[] spinScratch = new float[2];

	public Entity303(EntityType<? extends Entity303> type, Level level) {
		super(type, level);
		this.xpReward = 500;
		this.setPersistenceRequired();
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Monster.createMonsterAttributes()
			.add(Attributes.MAX_HEALTH, MAX_HEALTH)
			.add(Attributes.MOVEMENT_SPEED, PHASE_SPEED[0])
			.add(Attributes.ATTACK_DAMAGE, 32.0)
			.add(Attributes.FOLLOW_RANGE, 48.0)
			.add(Attributes.KNOCKBACK_RESISTANCE, 0.9)
			.add(Attributes.ARMOR, ARMOR)
			.add(Attributes.STEP_HEIGHT, 1.0);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new Entity303AttackGoal(this));
		this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
		this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 16.0F));
		this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
		this.targetSelector.addGoal(1, new HurtByTargetGoal(this)); // whoever hits him can take his attention
		this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_ATTACK, Entity303Animations.IDLE);
		builder.define(DATA_ATTACK_TICK, 0);
		builder.define(DATA_PHASE, 1);
	}

	// ---------------------------------------------------------------- state --
	public int getAttack() {
		return this.entityData.get(DATA_ATTACK);
	}

	public int getAttackTick() {
		return this.entityData.get(DATA_ATTACK_TICK);
	}

	public int getPhase() {
		return this.entityData.get(DATA_PHASE);
	}

	void startAttack(int attack) {
		this.entityData.set(DATA_ATTACK, attack);
		this.entityData.set(DATA_ATTACK_TICK, 0);
	}

	void setAttackTick(int tick) {
		this.entityData.set(DATA_ATTACK_TICK, tick);
	}

	void endAttack() {
		this.entityData.set(DATA_ATTACK, Entity303Animations.IDLE);
		this.entityData.set(DATA_ATTACK_TICK, 0);
	}

	void makeInvulnerableFor(int ticks) {
		this.invulnerableTicks = Math.max(this.invulnerableTicks, ticks);
	}

	boolean isRoarRequested() {
		return this.roarRequested;
	}

	boolean isIntroPending() {
		return this.introPending;
	}

	boolean consumeIntro() {
		boolean pending = this.introPending;
		this.introPending = false;
		return pending;
	}

	/** True while Reaper's Guard is up (from the moment he braces until the burst). */
	public boolean isGuarding() {
		return this.getAttack() == Entity303Animations.GUARD
			&& this.getAttackTick() >= Entity303Animations.GUARD_UP
			&& this.getAttackTick() < Entity303Animations.GUARD_BURST;
	}

	int consumeGuardHits() {
		int hits = this.guardHits;
		this.guardHits = 0;
		return hits;
	}

	@Override
	public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
		this.introPending = true;
		return super.finalizeSpawn(level, difficulty, reason, groupData);
	}

	/** Animation id the client draws: death while dying, the rage idle in the final form, else the current move. */
	public int visualAnimation() {
		if (this.isDeadOrDying()) {
			return Entity303Animations.DEATH;
		}
		int attack = this.getAttack();
		return attack == Entity303Animations.IDLE && this.getPhase() >= FINAL_PHASE ? Entity303Animations.IDLE_RAGE : attack;
	}

	/** Time in ticks inside {@link #visualAnimation()} (loops use the age, moves their own timer). */
	public float visualTime(float partialTick) {
		if (this.isDeadOrDying()) {
			return this.deathTime + partialTick;
		}
		return this.getAttack() == Entity303Animations.IDLE ? this.tickCount + partialTick : this.getAttackTick() + partialTick;
	}

	boolean consumeRoarRequest() {
		boolean requested = this.roarRequested;
		this.roarRequested = false;
		return requested;
	}

	/** Living survival-mode players within `radius` blocks: the crowd he is fighting. */
	public List<ServerPlayer> nearbyPlayers(ServerLevel level, double radius) {
		double r2 = radius * radius;
		return level.getPlayers(p -> p.isAlive() && !p.isCreative() && !p.isSpectator() && p.distanceToSqr(this) < r2);
	}

	/** Survival players around him (updated twice a second); the more there are, the harder he hits and the tougher he is. */
	public int crowdSize() {
		return this.crowdSize;
	}

	/** Players beyond the first one that count (0 when he fights alone). */
	private int extraPlayers() {
		return Math.min(this.crowdSize, CROWD_CAP) - 1;
	}

	/** Damage multiplier of the current phase and of the number of players around him (includes DAMAGE_SCALE). */
	float damageMultiplier() {
		float phase = switch (this.getPhase()) {
			case 3 -> 1.5F * FINAL_DAMAGE_BONUS * DAMAGE_SCALE;
			case 2 -> 1.25F * DAMAGE_SCALE;
			default -> DAMAGE_SCALE;
		};
		return phase * (1.0F + CROWD_DAMAGE_PER_PLAYER * this.extraPlayers());
	}

	/** How much the pauses between attacks shrink in the current phase. */
	float cooldownScale() {
		return switch (this.getPhase()) {
			case 3 -> 0.55F;
			case 2 -> 0.75F;
			default -> 1.0F;
		};
	}

	// ----------------------------------------------------------------- tick --
	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide()) {
			this.spinXO = this.spinX;
			this.spinYO = this.spinY;
			int anim = this.visualAnimation();
			float t = this.visualTime(0.0F);
			Entity303Animations.sample(anim, t, this.animScratch, this.spinScratch);
			// the model only lets this twirl turn the scythe while it is held like a wheel (see Entity303Model)
			this.spinX += this.spinScratch[0];
			this.spinY += this.spinScratch[1];
			if (Math.abs(this.spinX) > 1000.0F) {
				float wrap = Math.round(this.spinX / TWO_PI) * TWO_PI;
				this.spinX -= wrap;
				this.spinXO -= wrap;
			}
		}
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		if (this.invulnerableTicks > 0) {
			this.invulnerableTicks--;
		}
		if (this.tickCount % 10 == 0) {
			this.crowdSize = Math.max(1, this.nearbyPlayers(level, Entity303AttackGoal.AWARE_RADIUS).size());
		}
		this.healBudget = Math.min(HEAL_BUDGET_MAX, this.healBudget + HEAL_PER_TICK);
		int attack = this.getAttack();
		if (this.isNoGravity() && attack != Entity303Animations.WHIRL && attack != Entity303Animations.LEAP) {
			this.setNoGravity(false); // safety net: he only flies during Reaper's Descent and the Death Leap
		}

		double ratio = this.getHealth() / this.getMaxHealth();
		int phase = ratio <= 0.33 ? 3 : ratio <= 0.66 ? 2 : 1;
		if (phase > this.getPhase()) {
			this.enterPhase(level, phase);
		}

		this.updateBossBar();
	}

	private void enterPhase(ServerLevel level, int phase) {
		this.entityData.set(DATA_PHASE, phase);
		AttributeInstance speed = this.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.setBaseValue(PHASE_SPEED[phase - 1]);
		}
		this.roarRequested = true;
		this.bossEvent.setCreateWorldFog(true);
		if (phase >= FINAL_PHASE) {
			this.removeAllEffects(); // final form: nothing sticks to him any more
		}
	}

	/** Heals him with health taken from players, within the heal budget (so a big crowd cannot be out-healed forever). */
	void stealHealth(float amount) {
		float given = Math.min(amount, this.healBudget);
		if (given > 0.0F) {
			this.healBudget -= given;
			this.heal(given);
		}
	}

	/** Cobwebs, sweet berry bushes and powder snow do not slow him down. */
	@Override
	public void makeStuckInBlock(BlockState state, Vec3 motionMultiplier) {
	}

	/** In the final form he is immune to every effect, good or bad (potions, beacons, tipped arrows, wither, ...). */
	@Override
	public boolean canBeAffected(MobEffectInstance effect) {
		return this.getPhase() < FINAL_PHASE && super.canBeAffected(effect);
	}

	/** Splash and lingering potions apply instant effects directly: keep them off him too in the final form. */
	@Override
	public boolean isAffectedByPotions() {
		return this.getPhase() < FINAL_PHASE && super.isAffectedByPotions();
	}

	/**
	 * The bar has no text any more: the client draws it itself (BossBarRenderer, through BossHealthOverlayMixin) and picks the
	 * fill by the colour, which is therefore a plain function of the phase (white = 1, purple = 2, red = 3).
	 */
	private void updateBossBar() {
		this.bossEvent.setProgress(Mth.clamp(this.getHealth() / this.getMaxHealth(), 0.0F, 1.0F));
		int phase = this.getPhase();
		this.bossEvent.setColor(phase >= 3 ? BossEvent.BossBarColor.RED : phase == 2 ? BossEvent.BossBarColor.PURPLE : BossEvent.BossBarColor.WHITE);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (this.invulnerableTicks > 0 && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return false;
		}
		if (source.is(DamageTypeTags.IS_FALL)) {
			return false; // the crash of Reaper's Descent and the Death Leap must not hurt him
		}
		if (!source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			// tougher against a crowd, with a ceiling so that no number of players makes him invincible
			amount /= 1.0F + Math.min(CROWD_TOUGHNESS_MAX, CROWD_TOUGHNESS_PER_PLAYER * this.extraPlayers());
		}
		if (this.isGuarding() && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			amount *= GUARD_DAMAGE_FACTOR;
			this.guardHits++;
			level.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 1.5F, 0.6F);
		}
		return super.hurtServer(level, source, amount);
	}

	// ----------------------------------------------------------------- death --
	@Override
	public void die(DamageSource source) {
		super.die(source);
		this.bossEvent.setProgress(0.0F);
	}

	/** Vanilla removes a dead mob after 20 ticks; he stays for the whole death animation instead. */
	@Override
	protected void tickDeath() {
		this.deathTime++;
		if (this.level() instanceof ServerLevel level) {
			if (this.deathTime % 3 == 0 && this.deathTime < DEATH_TICKS) {
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, this.getX(), this.getY() + 1.2, this.getZ(), 6, 0.5, 0.9, 0.5, 0.04);
			}
			if (this.deathTime >= DEATH_TICKS && !this.isRemoved()) {
				level.broadcastEntityEvent(this, (byte) 60);
				this.remove(Entity.RemovalReason.KILLED);
			}
		}
	}


	// ------------------------------------------------- damage budget per player ---
	/**
	 * The most HP a single player can lose to him (after armor, enchantments and resistance) within one burst window, by
	 * phase. Sized so that a player in full diamond armor with Protection III who eats golden apples without a pause
	 * out-heals it: 8 hearts at most in his final form, then no more damage to that player until the window is over.
	 * See tools/balance_check.py for the numbers behind it.
	 */
	public static final float[] BURST_CAP = {8.0F, 12.0F, 16.0F};
	/** Length of the window (5 s; at least three were asked for, the rate has to stay below what golden apples heal). */
	public static final int BURST_WINDOW_TICKS = 100;

	private static final class Burst {
		int start = Integer.MIN_VALUE;
		float spent;
	}

	private final Map<UUID, Burst> bursts = new HashMap<>();

	/**
	 * @return the damage he may actually deal: {@code amount} when the budget of this window allows it, a smaller number
	 * when only part of it fits, 0 once the player has already taken the cap (that attack does nothing to him)
	 */
	public float limitBurst(ServerLevel level, ServerPlayer player, DamageSource source, float amount) {
		float cap = BURST_CAP[Mth.clamp(this.getPhase(), 1, BURST_CAP.length) - 1];
		Burst burst = this.bursts.computeIfAbsent(player.getUUID(), id -> new Burst());
		if (burst.start == Integer.MIN_VALUE || this.tickCount - burst.start >= BURST_WINDOW_TICKS) {
			burst.start = this.tickCount;
			burst.spent = 0.0F;
		}
		float room = cap - burst.spent;
		if (room < 0.5F) {
			return 0.0F;
		}
		float effective = effectiveDamage(level, player, source, amount);
		if (effective <= room) {
			burst.spent += effective;
			return amount;
		}
		float low = 0.0F;
		float high = amount;
		for (int i = 0; i < 14; i++) {
			float mid = (low + high) * 0.5F;
			if (effectiveDamage(level, player, source, mid) > room) {
				high = mid;
			} else {
				low = mid;
			}
		}
		burst.spent = cap;
		return low;
	}

	/** What {@code amount} of damage costs the player after armor, enchantments and the resistance effect. */
	private static float effectiveDamage(ServerLevel level, LivingEntity victim, DamageSource source, float amount) {
		float damage = amount;
		if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
			damage = CombatRules.getDamageAfterAbsorb(victim, damage, source, (float) victim.getArmorValue(),
				(float) victim.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
		}
		if (!source.is(DamageTypeTags.BYPASSES_EFFECTS) && victim.hasEffect(MobEffects.RESISTANCE)) {
			int levels = victim.getEffect(MobEffects.RESISTANCE).getAmplifier() + 1;
			damage = Math.max(damage * (25 - levels * 5) / 25.0F, 0.0F);
		}
		if (damage > 0.0F && !source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
			float protection = EnchantmentHelper.getDamageProtection(level, victim, source);
			if (protection > 0.0F) {
				damage = CombatRules.getDamageAfterMagicAbsorb(damage, protection);
			}
		}
		return damage;
	}

	// ------------------------------------------------------------ boss bar ---
	@Override
	public void startSeenByPlayer(ServerPlayer player) {
		super.startSeenByPlayer(player);
		this.bossEvent.addPlayer(player);
	}

	@Override
	public void stopSeenByPlayer(ServerPlayer player) {
		super.stopSeenByPlayer(player);
		this.bossEvent.removePlayer(player);
	}

	// ------------------------------------------------------------- misc ------
	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public boolean canUsePortal(boolean allowPassengers) {
		return false;
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return SoundEvents.WITHER_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.WITHER_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.WITHER_DEATH;
	}
}
