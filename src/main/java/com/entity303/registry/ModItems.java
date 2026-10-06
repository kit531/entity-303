package com.entity303.registry;

import com.entity303.Entity303Mod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

public final class ModItems {
	private static final ResourceKey<Item> SPAWN_EGG_KEY =
		ResourceKey.create(Registries.ITEM, Entity303Mod.id("entity_303_spawn_egg"));

	public static final Item ENTITY_303_SPAWN_EGG = Registry.register(
		BuiltInRegistries.ITEM,
		SPAWN_EGG_KEY,
		new SpawnEggItem(new Item.Properties().setId(SPAWN_EGG_KEY).spawnEgg(ModEntities.ENTITY_303))
	);

	private ModItems() {
	}

	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS)
			.register(entries -> entries.accept(ENTITY_303_SPAWN_EGG));
	}
}
