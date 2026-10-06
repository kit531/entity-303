package com.entity303.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/** Fullbright glowing layer: the red eyes, and (rageOnly) the face glowing dark red from phase 2. */
public class Entity303EyesLayer extends EyesLayer<Entity303RenderState, Entity303Model> {
	private final RenderType renderType;
	private final boolean rageOnly;

	public Entity303EyesLayer(RenderLayerParent<Entity303RenderState, Entity303Model> parent, Identifier texture, boolean rageOnly) {
		super(parent);
		this.renderType = RenderTypes.eyes(texture);
		this.rageOnly = rageOnly;
	}

	@Override
	public RenderType renderType() {
		return this.renderType;
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, Entity303RenderState state, float yRot, float xRot) {
		if (this.rageOnly && state.phase < 2) {
			return;
		}
		super.submit(poseStack, collector, light, state, yRot, xRot);
	}
}
