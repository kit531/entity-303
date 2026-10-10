package com.abilitykeys.client;

import com.abilitykeys.AbilityKeys;
import com.abilitykeys.AbilityKeysApi;
import com.abilitykeys.ActivateAbilityPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the four ability keys (they show up under "Abilities" in Options > Controls > Key Binds, so they can be
 * rebound there) and tells the server which one was pressed.
 */
public class AbilityKeysClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY =
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath(AbilityKeys.MOD_ID, "abilities"));
	private static final int[] DEFAULT_KEYS = {GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_K};
	private static final KeyMapping[] KEYS = new KeyMapping[AbilityKeysApi.SLOTS];

	@Override
	public void onInitializeClient() {
		for (int i = 0; i < KEYS.length; i++) {
			KEYS[i] = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.abilitykeys.ability_" + (i + 1), InputConstants.Type.KEYSYM, DEFAULT_KEYS[i], CATEGORY));
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
	}

	/** The key mapping of ability {@code slot} (1..4), for HUDs that want to show the bound key. */
	public static KeyMapping key(int slot) {
		return KEYS[slot - 1];
	}
}
