package com.entity303.ability;

import com.abilitykeys.AbilityKeysApi;
import com.entity303.Entity303Mod;
import com.entity303.entity.Entity303;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The four abilities of the Reaper's Scythe. The key bindings live in the separate "Ability Keys" mod: it only tells this
 * class "ability N was pressed", and everything else (the moves, the animations and effects, the cooldowns and their
 * HUD) is here.
 *
 *  1  Soul Laser   a beam of soul fire, the same hit as the boss's first phase Soul Slash
 *  2  Scythe Hook  the scythe is thrown, hooks the first mob/player it hits (through blocks) and drags it to the thrower
 *  3  Soul Steal   the next hit takes 2 hearts of max health from the target and gives them to the thrower for 20 s
 *  4  Dash         a leap that ends in a burst of black dust that hurts everything around the landing point
 */
public final class ScytheAbilities {
	public static final int SLOTS = AbilityKeysApi.SLOTS;
	/** Cooldowns in ticks, indexed by slot - 1. */
	public static final int[] COOLDOWN_TICKS = {30 * 20, 60 * 20, 45 * 20, 20 * 20};

	// Soul Laser: exactly the boss's Soul Slash at phase 1 (11 x DAMAGE_SCALE, wither for 3 s, 26 blocks)
	private static final float LASER_DAMAGE = 11.0F * Entity303.DAMAGE_SCALE;
	private static final double LASER_RANGE = 26.0;
	// Scythe Hook
	private static final double HOOK_RANGE = 20.0;
	private static final double HOOK_SPEED = 1.3;          // blocks per tick while flying
	private static final double PULL_SPEED = 0.85;         // blocks per tick while dragging
	private static final double RELEASE_DISTANCE = 1.9;    // released when about one block next to the thrower
	private static final int HOOK_DAMAGE_INTERVAL = 30;    // one heart every 1.5 s
	private static final float HOOK_DAMAGE = 2.0F;
	private static final int HOOK_TIMEOUT = 20 * 12;
	// Soul Steal
	private static final double STEAL_HEALTH = 4.0;        // two hearts
	private static final int STEAL_TICKS = 20 * 20;
	private static final Identifier STEAL_VICTIM = Entity303Mod.id("soul_steal_victim");
	private static final Identifier STEAL_GAIN = Entity303Mod.id("soul_steal_gain");
	// Dash
	private static final double DASH_SPEED = 1.7;
	private static final double DASH_LIFT = 0.42;
	private static final double DASH_RADIUS = 3.6;
	private static final float DASH_DAMAGE = 7.0F;
	private static final DustParticleOptions BLACK = new DustParticleOptions(0x000000, 1.8F);

	private static final Identifier SCYTHE_MODEL = Entity303Mod.id("reaper_scythe");

	private static final Map<UUID, PlayerState> STATES = new HashMap<>();
	private static final List<Hook> HOOKS = new ArrayList<>();
	private static final List<Dash> DASHES = new ArrayList<>();
	private static final List<Expiry> EXPIRIES = new ArrayList<>();
	private static final Set<UUID> BEING_PULLED = new HashSet<>();

	private ScytheAbilities() {
	}

	private static final class PlayerState {
		final long[] readyAt = new long[SLOTS];
		final int[] total = new int[SLOTS];
		boolean soulStealArmed;
	}

	private static final class Hook {
		final ServerPlayer owner;
		final ServerLevel level;
		final ItemEntity visual;
		final Vec3 dir;
		Vec3 pos;
		double travelled;
		LivingEntity target;
		boolean targetNoPhysics;
		int age;
		int sinceDamage;

		Hook(ServerPlayer owner, ServerLevel level, ItemEntity visual, Vec3 pos, Vec3 dir) {
			this.owner = owner;
			this.level = level;
			this.visual = visual;
			this.pos = pos;
			this.dir = dir;
		}
	}

	private static final class Dash {
		final ServerPlayer player;
		int age;

		Dash(ServerPlayer player) {
			this.player = player;
		}
	}

	private record Expiry(UUID entity, Identifier modifier, long atTick) {
	}

	// ------------------------------------------------------------------ setup --
	public static void init() {
		PayloadTypeRegistry.playS2C().register(CooldownSyncPayload.TYPE, CooldownSyncPayload.CODEC);
		AbilityKeysApi.registerHandler(ScytheAbilities::onActivate);

		ServerTickEvents.END_SERVER_TICK.register(ScytheAbilities::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			STATES.clear();
			HOOKS.forEach(h -> h.visual.discard());
			HOOKS.clear();
			DASHES.clear();
			EXPIRIES.clear();
			BEING_PULLED.clear();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sync(handler.getPlayer(), server.overworld().getGameTime()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.getPlayer().getUUID();
			STATES.remove(id);
			BEING_PULLED.remove(id);
		});

		// the soul steal is armed: the next hit that does damage drains the target
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
			if (blocked || damage <= 0.0F || !(source.getEntity() instanceof ServerPlayer attacker) || source.getDirectEntity() != attacker) {
				return;
			}
			PlayerState state = STATES.get(attacker.getUUID());
			if (state != null && state.soulStealArmed && entity != attacker) {
				state.soulStealArmed = false;
				stealSouls(attacker, entity);
			}
		});
		// being dragged through blocks must not suffocate
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			!(source.is(DamageTypes.IN_WALL) && BEING_PULLED.contains(entity.getUUID())));
	}

	/** True for the item the boss drops: a netherite axe wearing the Reaper's Scythe model. */
	public static boolean isScythe(ItemStack stack) {
		return !stack.isEmpty() && SCYTHE_MODEL.equals(stack.get(DataComponents.ITEM_MODEL));
	}

	// -------------------------------------------------------------- activation --
	private static void onActivate(ServerPlayer player, int slot) {
		if (!isScythe(player.getMainHandItem()) || !player.isAlive() || player.isSpectator()) {
			return;
		}
		ServerLevel level = player.level();
		PlayerState state = STATES.computeIfAbsent(player.getUUID(), id -> new PlayerState());
		long now = level.getGameTime();
		if (now < state.readyAt[slot - 1]) {
			return;
		}
		boolean used = switch (slot) {
			case 1 -> soulLaser(level, player);
			case 2 -> throwScythe(level, player);
			case 3 -> armSoulSteal(level, player, state);
			default -> dash(level, player);
		};
		if (used) {
			state.readyAt[slot - 1] = now + COOLDOWN_TICKS[slot - 1];
			state.total[slot - 1] = COOLDOWN_TICKS[slot - 1];
			sync(player, now);
		}
	}

	private static void sync(ServerPlayer player, long now) {
		PlayerState state = STATES.get(player.getUUID());
		int[] remaining = new int[SLOTS];
		int[] total = new int[SLOTS];
		if (state != null) {
			for (int i = 0; i < SLOTS; i++) {
				remaining[i] = (int) Math.max(0L, state.readyAt[i] - now);
				total[i] = state.total[i];
			}
		}
		ServerPlayNetworking.send(player, new CooldownSyncPayload(remaining, total));
	}

	// ------------------------------------------------------------ 1: soul laser --
	private static boolean soulLaser(ServerLevel level, ServerPlayer player) {
		Vec3 origin = player.getEyePosition();
		Vec3 dir = player.getLookAngle();
		Vec3 end = origin.add(dir.scale(LASER_RANGE));
		BlockHitResult hit = level.clip(new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.MISS) {
			end = hit.getLocation();
		}
		double length = origin.distanceTo(end);

		DamageSource source = level.damageSources().playerAttack(player);
		AABB box = new AABB(origin, end).inflate(1.8);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, e -> e != player && e.isAlive() && !e.isSpectator())) {
			Vec3 center = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0);
			if (distanceToSegment(center, origin, end) <= 1.5 + victim.getBbWidth() * 0.5 && victim.hurtServer(level, source, LASER_DAMAGE)) {
				victim.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0), player);
				victim.push(dir.x * 0.6, 0.35, dir.z * 0.6);
				victim.hurtMarked = true;
			}
		}

		for (double d = 1.0; d < length; d += 0.5) {
			Vec3 p = origin.add(dir.scale(d));
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 2, 0.12, 0.12, 0.12, 0.01);
			if (((int) (d / 0.5)) % 5 == 0) {
				level.sendParticles(ParticleTypes.SWEEP_ATTACK, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
		player.swing(InteractionHand.MAIN_HAND, true);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WITHER_SHOOT, SoundSource.PLAYERS, 1.5F, 0.7F);
		return true;
	}

	private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
		Vec3 ab = b.subtract(a);
		double len2 = ab.lengthSqr();
		if (len2 < 1.0E-6) {
			return p.distanceTo(a);
		}
		double t = Math.max(0.0, Math.min(1.0, p.subtract(a).dot(ab) / len2));
		return p.distanceTo(a.add(ab.scale(t)));
	}

	// ------------------------------------------------------------ 2: scythe hook --
	private static boolean throwScythe(ServerLevel level, ServerPlayer player) {
		Vec3 dir = player.getLookAngle();
		Vec3 start = player.getEyePosition().add(dir.scale(0.8));
		ItemStack shown = player.getMainHandItem().copyWithCount(1);
		ItemEntity visual = new ItemEntity(level, start.x, start.y, start.z, shown);
		visual.setNoGravity(true);
		visual.setNeverPickUp();
		visual.setUnlimitedLifetime();
		visual.setInvulnerable(true);
		visual.noPhysics = true;
		visual.setDeltaMovement(Vec3.ZERO);
		level.addFreshEntity(visual);
		HOOKS.add(new Hook(player, level, visual, start, dir));
		player.swing(InteractionHand.MAIN_HAND, true);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.2F, 0.6F);
		return true;
	}

	/** @return true when the hook is finished and should be forgotten */
	private static boolean tickHook(Hook h) {
		ServerPlayer owner = h.owner;
		h.age++;
		if (!owner.isAlive() || owner.isRemoved() || owner.level() != h.level || h.visual.isRemoved() || h.age > HOOK_TIMEOUT) {
			endHook(h);
			return true;
		}
		if (h.target == null) {
			h.pos = h.pos.add(h.dir.scale(HOOK_SPEED));
			h.travelled += HOOK_SPEED;
			h.visual.setPos(h.pos.x, h.pos.y - 0.25, h.pos.z);
			h.visual.setDeltaMovement(Vec3.ZERO);
			h.level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, h.pos.x, h.pos.y, h.pos.z, 1, 0.08, 0.08, 0.08, 0.0);
			h.level.sendParticles(ParticleTypes.SQUID_INK, h.pos.x, h.pos.y, h.pos.z, 1, 0.1, 0.1, 0.1, 0.0);

			LivingEntity best = null;
			double bestDistance = Double.MAX_VALUE;
			for (LivingEntity e : h.level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(h.pos, 2.4, 2.4, 2.4),
				e -> e != owner && e.isAlive() && !e.isSpectator())) {
				double d = e.distanceToSqr(h.pos);
				if (d < bestDistance) {
					best = e;
					bestDistance = d;
				}
			}
			if (best != null) {
				h.target = best;
				h.targetNoPhysics = best.noPhysics;
				best.noPhysics = true;
				BEING_PULLED.add(best.getUUID());
				h.level.playSound(null, best.getX(), best.getY(), best.getZ(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.5F, 0.6F);
			} else if (h.travelled >= HOOK_RANGE) {
				endHook(h);
				return true;
			}
			return false;
		}

		LivingEntity t = h.target;
		if (!t.isAlive() || t.isRemoved() || t.level() != h.level) {
			endHook(h);
			return true;
		}
		Vec3 delta = owner.position().subtract(t.position());
		double distance = delta.length();
		if (distance <= RELEASE_DISTANCE) {
			endHook(h);
			return true;
		}
		Vec3 next = t.position().add(delta.scale(Math.min(PULL_SPEED, distance - 1.0) / distance));
		t.setDeltaMovement(Vec3.ZERO);
		t.fallDistance = 0.0;
		if (t instanceof ServerPlayer pulled) {
			pulled.teleportTo(h.level, next.x, next.y, next.z, Set.of(), pulled.getYRot(), pulled.getXRot(), false);
		} else {
			t.setPos(next);
		}
		h.visual.setPos(next.x, next.y + t.getBbHeight() * 0.5, next.z);
		h.visual.setDeltaMovement(Vec3.ZERO);
		h.level.sendParticles(ParticleTypes.SOUL, next.x, next.y + t.getBbHeight() * 0.5, next.z, 1, 0.2, 0.3, 0.2, 0.01);
		if (++h.sinceDamage >= HOOK_DAMAGE_INTERVAL) {
			h.sinceDamage = 0;
			t.hurtServer(h.level, h.level.damageSources().playerAttack(owner), HOOK_DAMAGE);
		}
		return false;
	}

	private static void endHook(Hook h) {
		if (h.target != null) {
			h.target.noPhysics = h.targetNoPhysics;
			BEING_PULLED.remove(h.target.getUUID());
		}
		h.visual.discard();
		h.level.playSound(null, h.pos.x, h.pos.y, h.pos.z, SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 1.0F, 0.9F);
	}

	// ------------------------------------------------------------ 3: soul steal --
	private static boolean armSoulSteal(ServerLevel level, ServerPlayer player, PlayerState state) {
		state.soulStealArmed = true;
		level.sendParticles(ParticleTypes.SOUL, player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.5, 0.8, 0.5, 0.05);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.5F, 0.8F);
		player.displayClientMessage(Component.literal("Soul Steal: your next hit drains 2 hearts"), true);
		return true;
	}

	private static void stealSouls(ServerPlayer attacker, LivingEntity victim) {
		AttributeInstance victimMax = victim.getAttribute(Attributes.MAX_HEALTH);
		AttributeInstance attackerMax = attacker.getAttribute(Attributes.MAX_HEALTH);
		if (victimMax == null || attackerMax == null) {
			return;
		}
		double take = Math.min(STEAL_HEALTH, Math.max(0.0, victimMax.getValue() - 2.0));   // leaves him at least one heart
		if (take <= 0.0) {
			return;
		}
		victimMax.removeModifier(STEAL_VICTIM);
		victimMax.addTransientModifier(new AttributeModifier(STEAL_VICTIM, -take, AttributeModifier.Operation.ADD_VALUE));
		if (victim.getHealth() > victim.getMaxHealth()) {
			victim.setHealth(victim.getMaxHealth());
		}
		attackerMax.removeModifier(STEAL_GAIN);
		attackerMax.addTransientModifier(new AttributeModifier(STEAL_GAIN, take, AttributeModifier.Operation.ADD_VALUE));
		attacker.heal((float) take);

		long until = attacker.level().getGameTime() + STEAL_TICKS;
		EXPIRIES.add(new Expiry(victim.getUUID(), STEAL_VICTIM, until));
		EXPIRIES.add(new Expiry(attacker.getUUID(), STEAL_GAIN, until));
		ServerLevel level = attacker.level();
		level.sendParticles(ParticleTypes.SOUL, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ(), 25, 0.4, 0.6, 0.4, 0.08);
		level.sendParticles(ParticleTypes.HEART, attacker.getX(), attacker.getY() + 1.6, attacker.getZ(), 6, 0.4, 0.3, 0.4, 0.0);
		level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.6F, 1.6F);
	}

	// ------------------------------------------------------------------ 4: dash --
	private static boolean dash(ServerLevel level, ServerPlayer player) {
		Vec3 look = player.getLookAngle();
		Vec3 flat = new Vec3(look.x, 0.0, look.z);
		flat = flat.lengthSqr() < 1.0E-4 ? Vec3.directionFromRotation(0.0F, player.getYRot()) : flat.normalize();
		player.setDeltaMovement(flat.x * DASH_SPEED, DASH_LIFT, flat.z * DASH_SPEED);
		player.hurtMarked = true;
		player.fallDistance = 0.0;
		DASHES.add(new Dash(player));
		player.swing(InteractionHand.MAIN_HAND, true);
		level.sendParticles(BLACK, player.getX(), player.getY() + 0.2, player.getZ(), 25, 0.4, 0.1, 0.4, 0.02);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 1.0F, 1.4F);
		return true;
	}

	/** @return true when the dash is over */
	private static boolean tickDash(Dash d) {
		ServerPlayer p = d.player;
		d.age++;
		if (!p.isAlive() || p.isRemoved()) {
			return true;
		}
		ServerLevel level = p.level();
		p.fallDistance = 0.0;
		level.sendParticles(BLACK, p.getX(), p.getY() + 0.5, p.getZ(), 4, 0.25, 0.3, 0.25, 0.0);
		if (!((d.age >= 5 && p.onGround()) || d.age > 50)) {
			return false;
		}
		// landing: a burst of black dust, and everything around is hurt
		double x = p.getX();
		double y = p.getY();
		double z = p.getZ();
		level.sendParticles(BLACK, x, y + 0.15, z, 140, 1.8, 0.15, 1.8, 0.03);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 0.3, z, 30, 1.4, 0.2, 1.4, 0.02);
		for (int i = 0; i < 36; i++) {
			double a = i * Math.PI * 2.0 / 36.0;
			level.sendParticles(BLACK, x + Math.cos(a) * DASH_RADIUS, y + 0.1, z + Math.sin(a) * DASH_RADIUS, 1, 0.0, 0.05, 0.0, 0.0);
		}
		DamageSource source = level.damageSources().playerAttack(p);
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(DASH_RADIUS, 1.5, DASH_RADIUS),
			e -> e != p && e.isAlive() && !e.isSpectator())) {
			if (victim.hurtServer(level, source, DASH_DAMAGE)) {
				Vec3 away = victim.position().subtract(p.position());
				away = away.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : away.normalize();
				victim.push(away.x * 0.8, 0.4, away.z * 0.8);
				victim.hurtMarked = true;
			}
		}
		level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.9F, 0.7F);
		return true;
	}

	// -------------------------------------------------------------------- tick --
	private static void tick(MinecraftServer server) {
		HOOKS.removeIf(ScytheAbilities::tickHook);
		DASHES.removeIf(ScytheAbilities::tickDash);
		if (EXPIRIES.isEmpty()) {
			return;
		}
		long now = server.overworld().getGameTime();
		Iterator<Expiry> it = EXPIRIES.iterator();
		while (it.hasNext()) {
			Expiry e = it.next();
			if (now < e.atTick) {
				continue;
			}
			it.remove();
			for (ServerLevel level : server.getAllLevels()) {
				if (level.getEntity(e.entity) instanceof LivingEntity living) {
					AttributeInstance max = living.getAttribute(Attributes.MAX_HEALTH);
					if (max != null) {
						max.removeModifier(e.modifier);
					}
					if (living.getHealth() > living.getMaxHealth()) {
						living.setHealth(living.getMaxHealth());
					}
					break;
				}
			}
		}
	}
}
