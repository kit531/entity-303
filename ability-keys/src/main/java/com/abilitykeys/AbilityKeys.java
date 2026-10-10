package com.abilitykeys;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AbilityKeys implements ModInitializer {
	public static final String MOD_ID = "abilitykeys";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playC2S().register(ActivateAbilityPayload.TYPE, ActivateAbilityPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ActivateAbilityPayload.TYPE,
			(payload, context) -> AbilityKeysApi.dispatch(context.player(), payload.slot()));
	}
}
