package com.entity303.client.render;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

public class Entity303RenderState extends LivingEntityRenderState {
	/** One of Entity303Animations.IDLE ... ROAR. */
	public int attack;
	/** Ticks since the attack started (with partial tick), or the age while idle. */
	public float animTime;
	public int phase = 1;
	public float spinX;
	public float spinY;
}
