package com.entity303.registry;

import com.entity303.Entity303Mod;
import com.entity303.entity.Entity303;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	public static final ResourceKey<EntityType<?>> ENTITY_303_KEY =
		ResourceKey.create(Registries.ENTITY_TYPE, Entity303Mod.id("entity_303"));

	public static final EntityType<Entity303> ENTITY_303 = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		ENTITY_303_KEY,
		EntityType.Builder.of(Entity303::new, MobCategory.MONSTER)
			.sized(0.9F, 2.9F)
			.eyeHeight(2.55F)
			.fireImmune()
			.clientTrackingRange(10)
			.updateInterval(2)
			.build(ENTITY_303_KEY)
	);

	private ModEntities() {
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(ENTITY_303, Entity303.createAttributes());
	}
}
