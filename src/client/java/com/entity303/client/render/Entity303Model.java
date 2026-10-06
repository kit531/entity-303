package com.entity303.client.render;

import com.entity303.Entity303Mod;
import com.entity303.anim.Entity303Animations;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * The geometry comes from {@link Entity303Geometry} (generated); the pose comes from the keyframes in
 * {@link Entity303Animations} (generated from tools/animations.py) plus walking and head look.
 */
public class Entity303Model extends EntityModel<Entity303RenderState> {
	public static final ModelLayerLocation LAYER = new ModelLayerLocation(Entity303Mod.id("entity_303"), "main");

	private final ModelPart head;
	private final ModelPart body;
	private final ModelPart rightArm;
	private final ModelPart leftArm;
	private final ModelPart rightLeg;
	private final ModelPart leftLeg;
	private final ModelPart scythePivot;
	private final ModelPart scythe;

	private static final float TWO_PI = (float) (Math.PI * 2.0);

	private final float[] channels = new float[Entity303Animations.CHANNELS];
	private final float[] spin = new float[2];

	public Entity303Model(ModelPart root) {
		super(root);
		this.head = root.getChild("head");
		this.body = root.getChild("body");
		this.rightArm = root.getChild("right_arm");
		this.leftArm = root.getChild("left_arm");
		this.rightLeg = root.getChild("right_leg");
		this.leftLeg = root.getChild("left_leg");
		this.scythePivot = this.rightArm.getChild("scythe_pivot");
		this.scythe = this.scythePivot.getChild("scythe");
	}

	@Override
	public void setupAnim(Entity303RenderState state) {
		super.setupAnim(state);

		// head follows the target
		this.head.yRot = state.yRot * (Mth.PI / 180.0F);
		this.head.xRot = state.xRot * (Mth.PI / 180.0F);

		// walking
		float swing = Math.min(1.0F, state.walkAnimationSpeed);
		float pos = state.walkAnimationPos * 0.6662F;
		this.rightLeg.xRot = Mth.cos(pos) * 1.2F * swing;
		this.leftLeg.xRot = Mth.cos(pos + Mth.PI) * 1.2F * swing;
		if (Entity303Animations.isIdle(state.attack)) {
			this.leftArm.xRot += Mth.cos(pos) * 0.9F * swing;
		}

		// keyframes
		Entity303Animations.sample(state.attack, state.animTime, this.channels, this.spin);
		this.pose(this.root, Entity303Animations.ROOT);
		this.pose(this.head, Entity303Animations.HEAD);
		this.pose(this.body, Entity303Animations.BODY);
		this.pose(this.rightArm, Entity303Animations.RIGHT_ARM);
		this.pose(this.leftArm, Entity303Animations.LEFT_ARM);
		this.pose(this.scythePivot, Entity303Animations.SCYTHE_PIVOT);
		this.pose(this.scythe, Entity303Animations.SCYTHE);
		this.pose(this.rightLeg, Entity303Animations.RIGHT_LEG);
		this.pose(this.leftLeg, Entity303Animations.LEFT_LEG);
		this.root.x += this.channels[Entity303Animations.OFFSET];
		this.root.y += this.channels[Entity303Animations.OFFSET + 1];
		this.root.z += this.channels[Entity303Animations.OFFSET + 2];

		// the scythe twirl turns the scythe like a wheel around the arm; it only applies while the scythe is
		// held as a wheel (scythe rz = 90 degrees), so a scythe held side-on for a chop never gets spun
		// through his body (tools/collide.py checks exactly this)
		float wheel = Mth.clamp(this.channels[Entity303Animations.SCYTHE + 2] / (Mth.PI / 2.0F), 0.0F, 1.0F);
		float twirl = (state.spinX - TWO_PI * Math.round(state.spinX / TWO_PI)) * wheel;
		switch (Entity303Animations.SPIN_AXIS) {
			case 0 -> this.scythePivot.xRot += twirl;
			case 1 -> this.scythePivot.yRot += twirl;
			default -> this.scythePivot.zRot += twirl;
		}
		this.scythe.yRot += state.spinY;
	}

	private void pose(ModelPart part, int channel) {
		part.xRot += this.channels[channel];
		part.yRot += this.channels[channel + 1];
		part.zRot += this.channels[channel + 2];
	}
}
