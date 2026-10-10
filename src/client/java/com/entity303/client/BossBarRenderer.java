package com.entity303.client;

import com.entity303.Entity303Mod;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;

/**
 * Entity 303's boss bar: no text, an ornate frame (scythe blades, a skull, gems) around a 182 x 7 bar whose fill depends on
 * the phase. The art is drawn by tools/gen_ui.py (keep the geometry constants in sync with it).
 */
public final class BossBarRenderer {
	private static final RenderPipeline PIPELINE = RenderPipelines.GUI_TEXTURED;
	private static final Identifier FRAME = Entity303Mod.id("textures/gui/boss_bar_frame.png");
	private static final Identifier GLOSS = Entity303Mod.id("textures/gui/boss_bar_gloss.png");
	private static final Identifier[] FILL = {
		Entity303Mod.id("textures/gui/boss_bar_fill_1.png"),
		Entity303Mod.id("textures/gui/boss_bar_fill_2.png"),
		Entity303Mod.id("textures/gui/boss_bar_fill_3.png"),
	};

	private static final int FRAME_W = 252;
	private static final int FRAME_H = 46;
	private static final int SLOT_X = 35;
	private static final int SLOT_Y = 19;
	private static final int SLOT_W = 182;
	private static final int SLOT_H = 7;

	private BossBarRenderer() {
	}

	/** @param x left edge of the (182 px wide) vanilla bar, @param y its top edge */
	public static void draw(GuiGraphics graphics, int x, int y, BossEvent event) {
		int phase = switch (event.getColor()) {
			case RED -> 3;
			case PURPLE -> 2;
			default -> 1;
		};
		float progress = Mth.clamp(event.getProgress(), 0.0F, 1.0F);
		int left = x - SLOT_X;
		int top = y - SLOT_Y + 8;                 // the slot sits a little below the vanilla bar so the ornaments fit on screen

		graphics.blit(PIPELINE, FRAME, left, top, 0.0F, 0.0F, FRAME_W, FRAME_H, FRAME_W, FRAME_H);

		int width = Math.round(SLOT_W * progress);
		if (width > 0) {
			int tint = 0xFFFFFFFF;
			if (phase == 3) {                      // the final form pulses
				int v = 205 + (int) (50.0 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 140.0)));
				tint = 0xFF000000 | (v << 16) | (v << 8) | v;
			}
			graphics.blit(PIPELINE, FILL[phase - 1], left + SLOT_X, top + SLOT_Y, 0.0F, 0.0F, width, SLOT_H, SLOT_W, SLOT_H, tint);
			if (width > 2 && width < SLOT_W) {     // bright leading edge
				graphics.fill(left + SLOT_X + width - 1, top + SLOT_Y, left + SLOT_X + width, top + SLOT_Y + SLOT_H, 0xA0FFE0E0);
			}
		}
		graphics.blit(PIPELINE, GLOSS, left + SLOT_X, top + SLOT_Y, 0.0F, 0.0F, SLOT_W, SLOT_H, SLOT_W, SLOT_H);
	}
}
