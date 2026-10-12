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
	/** The four ability keys: slots 1..4. */
	public static final int SLOTS = 4;
	/** The fifth key, "Disable Passive Ability 1": it does not use an ability, it switches the passive of the item in the inventory off and on. */
	public static final int PASSIVE_TOGGLE_1 = SLOTS + 1;
	/** Every key of this mod: the four abilities and the passive switch. */
	public static final int KEYS = SLOTS + 1;
	private static final List<AbilityHandler> HANDLERS = new CopyOnWriteArrayList<>();

	private AbilityKeysApi() {
	}

	public static void registerHandler(AbilityHandler handler) {
		HANDLERS.add(handler);
	}

	/** Called on the server thread by the network receiver. */
	static void dispatch(ServerPlayer player, int slot) {
		if (slot < 1 || slot > KEYS) {
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
