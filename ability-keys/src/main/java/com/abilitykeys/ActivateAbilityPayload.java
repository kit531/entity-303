package com.abilitykeys;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: the player pressed ability key {@code slot} (1..4). */
public record ActivateAbilityPayload(int slot) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ActivateAbilityPayload> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AbilityKeys.MOD_ID, "activate"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ActivateAbilityPayload> CODEC = StreamCodec.of(
		(buf, payload) -> buf.writeVarInt(payload.slot),
		buf -> new ActivateAbilityPayload(buf.readVarInt())
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
