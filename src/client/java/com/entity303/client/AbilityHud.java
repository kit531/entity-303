package com.entity303.client;

import com.abilitykeys.client.AbilityKeysClient;
import com.entity303.Entity303Mod;
import com.entity303.ability.ScytheAbilities;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The cooldowns of the four scythe abilities, drawn above the health and hunger bars while the scythe is in the main
 * hand: an icon per ability, the bound key above it, and READY under it, or the seconds left over the icon.
 */
public final class AbilityHud {
	private static final Identifier[] ICONS = {
		Entity303Mod.id("textures/gui/ability_1.png"),
		Entity303Mod.id("textures/gui/ability_2.png"),
		Entity303Mod.id("textures/gui/ability_3.png"),
		Entity303Mod.id("textures/gui/ability_4.png"),
	};
	private static final int SLOT = 24;
	private static final int PITCH = 36;
	/** The game time (of the client level) at which each cooldown ends, and the length of each cooldown. */
	private static final long[] END = new long[ScytheAbilities.SLOTS];
	private static final int[] TOTAL = new int[ScytheAbilities.SLOTS];

	private AbilityHud() {
	}

	public static void register() {
		HudElementRegistry.addLast(Entity303Mod.id("ability_cooldowns"), AbilityHud::render);
	}

	/** Called when the server sends the cooldowns (remaining ticks and totals). */
	public static void update(int[] remaining, int[] total) {
		Minecraft mc = Minecraft.getInstance();
		long now = mc.level != null ? mc.level.getGameTime() : 0L;
		for (int i = 0; i < ScytheAbilities.SLOTS; i++) {
			END[i] = remaining[i] > 0 ? now + remaining[i] : 0L;
			TOTAL[i] = total[i];
		}
	}

	private static void render(GuiGraphics graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.options.hideGui || !ScytheAbilities.isScythe(mc.player.getMainHandItem())) {
			return;
		}
		Font font = mc.font;
		long now = mc.level.getGameTime();
		int width = ScytheAbilities.SLOTS * SLOT + (ScytheAbilities.SLOTS - 1) * (PITCH - SLOT);
		int left = graphics.guiWidth() / 2 - width / 2;
		int top = graphics.guiHeight() - 92;

		for (int i = 0; i < ScytheAbilities.SLOTS; i++) {
			int x = left + i * PITCH;
			long remaining = END[i] - now;
			boolean ready = remaining <= 0;

			// frame and background
			int frame = ready ? 0xFFE03A3A : 0xFF5A1A1E;
			graphics.fill(x - 1, top - 1, x + SLOT + 1, top + SLOT + 1, 0xFF0B0507);
			graphics.fill(x, top, x + SLOT, top + SLOT, frame);
			graphics.fill(x + 1, top + 1, x + SLOT - 1, top + SLOT - 1, 0xF0160609);

			// icon, dimmed while it recharges
			int tint = ready ? 0xFFFFFFFF : 0xFF707070;
			graphics.blit(RenderPipelines.GUI_TEXTURED, ICONS[i], x + 4, top + 4, 0.0F, 0.0F, 16, 16, 16, 16, tint);

			if (!ready) {
				// the cooldown drains from the top down
				int total = Math.max(1, TOTAL[i]);
				int cover = (int) Math.ceil((SLOT - 2) * Math.min(1.0, remaining / (double) total));
				graphics.fill(x + 1, top + 1, x + SLOT - 1, top + 1 + cover, 0x99000000);
				String seconds = String.valueOf((int) Math.ceil(remaining / 20.0));
				graphics.drawString(font, seconds, x + (SLOT - font.width(seconds)) / 2, top + (SLOT - 8) / 2 + 1, 0xFFFFFFFF, true);
			}

			// the key above, READY under it
			String key = AbilityKeysClient.key(i + 1).getTranslatedKeyMessage().getString();
			graphics.drawString(font, key, x + (SLOT - font.width(key)) / 2, top - 10, ready ? 0xFFFFFFFF : 0xFF9A8A8A, true);
			if (ready) {
				String text = "READY";
				graphics.drawString(font, text, x + (SLOT - font.width(text)) / 2, top + SLOT + 3, 0xFF55FF55, true);
			}
		}
	}
}
