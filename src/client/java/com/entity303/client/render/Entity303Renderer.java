package com.entity303.client.render;

import com.entity303.Entity303Mod;
import com.entity303.entity.Entity303;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

public class Entity303Renderer extends MobRenderer<Entity303, Entity303RenderState, Entity303Model> {
	private static final Identifier TEXTURE = Entity303Mod.id("textures/entity/entity303.png");
	private static final Identifier EYES = Entity303Mod.id("textures/entity/entity303_eyes.png");
	private static final Identifier RAGE = Entity303Mod.id("textures/entity/entity303_rage.png");

	/** The boss is a player-sized model blown up to match its 0.9 x 2.9 hitbox. */
	private static final float SCALE = 1.5F;

	public Entity303Renderer(EntityRendererProvider.Context context) {
		super(context, new Entity303Model(context.bakeLayer(Entity303Model.LAYER)), 0.8F);
		this.addLayer(new Entity303EyesLayer(this, EYES, false));
		this.addLayer(new Entity303EyesLayer(this, RAGE, true));
	}

	@Override
	public Entity303RenderState createRenderState() {
		return new Entity303RenderState();
	}

	@Override
	public Identifier getTextureLocation(Entity303RenderState state) {
		return TEXTURE;
	}

	@Override
	public void extractRenderState(Entity303 entity, Entity303RenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.attack = entity.visualAnimation();
		state.phase = entity.getPhase();
		state.animTime = entity.visualTime(partialTick);
		state.spinX = Mth.lerp(partialTick, entity.spinXO, entity.spinX);
		state.spinY = Mth.lerp(partialTick, entity.spinYO, entity.spinY);
	}

	/** Vanilla tips a dying mob over by 90 degrees; his own death animation does the falling instead. */
	@Override
	protected float getFlipDegrees() {
		return 0.0F;
	}

	@Override
	protected void scale(Entity303RenderState state, PoseStack poseStack) {
		poseStack.scale(SCALE, SCALE, SCALE);
	}
}
