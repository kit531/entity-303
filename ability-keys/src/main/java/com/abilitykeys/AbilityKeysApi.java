package com.abilitykeys;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.level.ServerPlayer;

/**
 * The whole contract between this mod and the mods that own the abilities: a mod registers a handler once, the keys
 * only tell it "the player pressed ability 1/2/3/4". Every handler decides for itself whether it applies (for example
 * by looking at the item in the hand of the player); the moves, animations and cooldowns all live in the other mod.
 */
public final class AbilityKeysApi {
	public static final int SLOTS = 4;
	private static final List<AbilityHandler> HANDLERS = new CopyOnWriteArrayList<>();

	private AbilityKeysApi() {
	}

	public static void registerHandler(AbilityHandler handler) {
		HANDLERS.add(handler);
	}

	/** Called on the server thread by the network receiver. */
	static void dispatch(ServerPlayer player, int slot) {
		if (slot < 1 || slot > SLOTS) {
			return;
		}
		for (AbilityHandler handler : HANDLERS) {
			try {
				handler.onActivate(player, slot);
			} catch (RuntimeException e) {
				AbilityKeys.LOGGER.error("Ability handler failed for slot {}", slot, e);
			}
		}
	}
}
