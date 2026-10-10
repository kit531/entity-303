package com.entity303.client;

import com.entity303.ability.CooldownSyncPayload;
import com.entity303.client.render.Entity303Geometry;
import com.entity303.client.render.Entity303Model;
import com.entity303.client.render.Entity303Renderer;
import com.entity303.registry.ModEntities;
import com.entity303.update.ModUpdater;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class Entity303Client implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityModelLayerRegistry.registerModelLayer(Entity303Model.LAYER, Entity303Geometry::createBodyLayer);
		EntityRendererRegistry.register(ModEntities.ENTITY_303, Entity303Renderer::new);

		// the cooldowns of the scythe abilities (the keys themselves belong to the Ability Keys mod)
		ClientPlayNetworking.registerGlobalReceiver(CooldownSyncPayload.TYPE,
			(payload, context) -> AbilityHud.update(payload.remaining(), payload.total()));
		AbilityHud.register();

		// tell the player when an update of the mod is waiting for a restart: now, and again whenever a world is joined
		ModUpdater.setReadyListener(version -> Minecraft.getInstance().execute(() -> announceUpdate(Minecraft.getInstance())));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> announceUpdate(client)));
	}

	private static void announceUpdate(Minecraft client) {
		String version = ModUpdater.readyVersion();
		if (version != null && client.player != null) {
			client.gui.getChat().addMessage(Component.literal("[Entity 303] Update " + version + " downloaded. Restart the game to use it."));
		}
	}
}
