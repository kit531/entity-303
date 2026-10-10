package com.abilitykeys;

import net.minecraft.server.level.ServerPlayer;

/** What a mod does when a player presses one of the four ability keys. Runs on the server thread. */
@FunctionalInterface
public interface AbilityHandler {
	/**
	 * @param player the player who pressed the key
	 * @param slot   1..4: the first, second, third or fourth ability key (G, H, J, K unless the player rebound them)
	 */
	void onActivate(ServerPlayer player, int slot);
}
