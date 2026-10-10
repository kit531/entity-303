package com.entity303.client.mixin;

import com.entity303.client.BossBarRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Entity 303 is the only thing that uses a 20-notch bar: those bars are drawn by {@link BossBarRenderer} instead. */
@Mixin(BossHealthOverlay.class)
public class BossHealthOverlayMixin {
	@Inject(
		method = "drawBar(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/world/BossEvent;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void entity303$drawBar(GuiGraphics graphics, int x, int y, BossEvent event, CallbackInfo ci) {
		if (event.getOverlay() == BossEvent.BossBarOverlay.NOTCHED_20) {
			BossBarRenderer.draw(graphics, x, y, event);
			ci.cancel();
		}
	}
}
