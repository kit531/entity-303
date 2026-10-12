package com.abilitykeys.client;

import com.abilitykeys.AbilityKeys;
import com.abilitykeys.AbilityKeysApi;
import com.abilitykeys.ActivateAbilityPayload;
import com.abilitykeys.update.ModUpdater;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the four ability keys and the passive switch key (they show up under "Abilities" in Options > Controls > Key Binds, so they can be
 * rebound there) and tells the server which one was pressed.
 */
public class AbilityKeysClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY =
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath(AbilityKeys.MOD_ID, "abilities"));
	private static final int[] DEFAULT_KEYS = {GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_V};
	/** The language keys: the four abilities, then the passive switch. */
	private static final String[] NAMES = {
		"key.abilitykeys.ability_1", "key.abilitykeys.ability_2", "key.abilitykeys.ability_3", "key.abilitykeys.ability_4",
		"key.abilitykeys.disable_passive_1"
	};
	private static final KeyMapping[] KEYS = new KeyMapping[AbilityKeysApi.KEYS];

	@Override
	public void onInitializeClient() {
		for (int i = 0; i < KEYS.length; i++) {
			KEYS[i] = KeyBindingHelper.registerKeyBinding(
				new KeyMapping(NAMES[i], InputConstants.Type.KEYSYM, DEFAULT_KEYS[i], CATEGORY));
		}
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean active = client.player != null && client.screen == null;
			for (int i = 0; i < KEYS.length; i++) {
				while (KEYS[i].consumeClick()) {
					if (active) {
						ClientPlayNetworking.send(new ActivateAbilityPayload(i + 1));
					}
				}
			}
		});

		// tell the player when an update of the mod is waiting for a restart: now, and again whenever a world is joined
		ModUpdater.setReadyListener(version -> Minecraft.getInstance().execute(() -> announceUpdate(Minecraft.getInstance())));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> announceUpdate(client)));
	}

	private static void announceUpdate(Minecraft client) {
		String version = ModUpdater.readyVersion();
		if (version != null && client.player != null) {
			client.gui.getChat().addMessage(Component.literal("[Ability Keys] Update " + version + " downloaded. Restart the game to use it."));
		}
	}

	/** The key mapping of ability {@code slot} (1..4; 5 is the passive switch), for HUDs that want to show the bound key. */
	public static KeyMapping key(int slot) {
		return KEYS[slot - 1];
	}
}
