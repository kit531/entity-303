package com.entity303;

import com.entity303.registry.ModEntities;
import com.entity303.registry.ModItems;
import com.entity303.update.ModUpdater;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Entity303Mod implements ModInitializer {
	public static final String MOD_ID = "entity303";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModEntities.register();
		ModItems.register();
		ModUpdater.start();
	}
}
