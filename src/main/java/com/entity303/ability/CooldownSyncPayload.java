package com.entity303.ability;

import com.entity303.Entity303Mod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: for each of the four scythe abilities, the ticks left on its cooldown and the total cooldown. */
public record CooldownSyncPayload(int[] remaining, int[] total) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<CooldownSyncPayload> TYPE =
		new CustomPacketPayload.Type<>(Entity303Mod.id("ability_cooldowns"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CooldownSyncPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			for (int i = 0; i < ScytheAbilities.SLOTS; i++) {
				buf.writeVarInt(payload.remaining[i]);
				buf.writeVarInt(payload.total[i]);
			}
		},
		buf -> {
			int[] remaining = new int[ScytheAbilities.SLOTS];
			int[] total = new int[ScytheAbilities.SLOTS];
			for (int i = 0; i < ScytheAbilities.SLOTS; i++) {
				remaining[i] = buf.readVarInt();
				total[i] = buf.readVarInt();
			}
			return new CooldownSyncPayload(remaining, total);
		}
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
