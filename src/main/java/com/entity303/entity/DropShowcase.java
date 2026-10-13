package com.entity303.entity;

import com.entity303.Entity303Mod;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The death animation of the loot: when Entity 303 dies, the Reaper's Scythe it drops does not simply fall to the floor. It rises
 * into the air, floats there turning in a ring of soul fire for a few seconds, and only then falls down as a normal item.
 *
 * The drop itself still comes from the loot table. This class only catches the scythe's item entity at the moment it appears next to a
 * dying Entity 303 and holds it (no gravity, nobody can pick it up, nothing can destroy it) until the animation is over.
 */
public final class DropShowcase {
	private static final Identifier SCYTHE_MODEL = Entity303Mod.id("reaper_scythe");
	private static final int RISE_TICKS = 30;            // it rises for 1.5 s ...
	private static final int HOVER_TICKS = 90;           // ... and falls after 4.5 s in all
	private static final double RISE_HEIGHT = 3.2;       // blocks above the place where it appeared
	private static final List<Showcase> ACTIVE = new ArrayList<>();

	private static final class Showcase {
		final ItemEntity item;
		final ServerLevel level;
		final double x;
		final double z;
		final double startY;
		int age;

		Showcase(ItemEntity item, ServerLevel level) {
			this.item = item;
			this.level = level;
			this.x = item.getX();
			this.z = item.getZ();
			this.startY = item.getY();
		}
	}

	private DropShowcase() {
	}

	public static void init() {
		ServerEntityEvents.ENTITY_LOAD.register(DropShowcase::onLoad);
		ServerTickEvents.END_SERVER_TICK.register(DropShowcase::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Showcase showcase : ACTIVE) {
				release(showcase, false);
			}
			ACTIVE.clear();
		});
	}

	private static boolean isScythe(ItemStack stack) {
		return !stack.isEmpty() && SCYTHE_MODEL.equals(stack.get(DataComponents.ITEM_MODEL));
	}

	/** A scythe appears next to a dying Entity 303: that is the boss drop. */
	private static void onLoad(Entity entity, ServerLevel level) {
		if (!(entity instanceof ItemEntity item) || !isScythe(item.getItem())) {
			return;
		}
		boolean bossIsDying = !level.getEntitiesOfClass(Entity303.class, item.getBoundingBox().inflate(10.0), LivingEntity::isDeadOrDying).isEmpty();
		if (!bossIsDying) {
			return;
		}
		item.setNoGravity(true);
		item.setInvulnerable(true);
		item.setPickUpDelay(32767);                    // 32767 = nobody can pick it up and the delay never counts down
		item.setDeltaMovement(Vec3.ZERO);
		ACTIVE.add(new Showcase(item, level));
	}

	private static void tick(MinecraftServer server) {
		ACTIVE.removeIf(DropShowcase::tickOne);
	}

	/** @return true when the animation is over (or the item is gone) */
	private static boolean tickOne(Showcase s) {
		ItemEntity item = s.item;
		if (item.isRemoved() || item.level() != s.level) {
			return true;
		}
		s.age++;
		double t = Math.min(1.0, s.age / (double) RISE_TICKS);
		double target = s.startY + RISE_HEIGHT * (t * t * (3.0 - 2.0 * t)) + 0.12 * Math.sin(s.age * 0.3);
		// it holds its place sideways and glides up (then bobs a little); the item's own turning is vanilla's
		item.setDeltaMovement(0.0, Mth.clamp(target - item.getY(), -0.5, 0.5), 0.0);

		ServerLevel level = s.level;
		double cx = item.getX();
		double cy = item.getY() + 0.3;
		double cz = item.getZ();
		double angle = s.age * 0.5;
		for (int k = 0; k < 3; k++) {            // a ring of soul fire turning around it
			double a = angle + k * (Math.PI * 2.0 / 3.0);
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, cx + Math.cos(a) * 0.9, cy, cz + Math.sin(a) * 0.9, 1, 0.0, 0.0, 0.0, 0.0);
		}
		if (s.age % 4 == 0) {
			level.sendParticles(ParticleTypes.SCULK_SOUL, cx, cy + 0.3, cz, 1, 0.25, 0.25, 0.25, 0.01);
		}
		if (s.age == 1) {
			level.playSound(null, cx, cy, cz, SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 2.0F, 0.6F);
		}
		if (s.age >= HOVER_TICKS) {
			release(s, true);
			return true;
		}
		return false;
	}

	/** The animation is over: the item may fall, and may be picked up a moment later. */
	private static void release(Showcase s, boolean effects) {
		ItemEntity item = s.item;
		if (item.isRemoved()) {
			return;
		}
		item.setNoGravity(false);
		item.setInvulnerable(false);
		item.setPickUpDelay(effects ? 40 : 0);
		if (effects) {
			ServerLevel level = s.level;
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, item.getX(), item.getY() + 0.3, item.getZ(), 30, 0.3, 0.3, 0.3, 0.12);
			level.playSound(null, item.getX(), item.getY(), item.getZ(), SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1.2F, 1.4F);
		}
	}
}
