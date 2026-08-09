package com.cleannrooster.divineencounters.content.malice.client;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.content.malice.entity.VisageOfMaliceEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/// The head is not attached to the body in the way a head normally is.
///
/// Everything below exists to break the one relationship every living model has: that turning the
/// body turns the face. Here the face holds still in *world* space while the body swivels beneath
/// it, and rights itself against the roll the body is banking through. Anatomically the head is
/// still parented to the neck; it just refuses to behave like it.
public class VisageOfMaliceModel extends GeoModel<VisageOfMaliceEntity> {
    private static final ResourceLocation MODEL = DivineEncounters.id("geo/visage_of_malice.geo.json");
    private static final ResourceLocation ANIMATION =
            DivineEncounters.id("animations/visage_of_malice.animation.json");
    private static final ResourceLocation TEXTURE =
            DivineEncounters.id("textures/entity/visage_of_malice.png");
    /// Deeper phases burn hotter along the seams — a visible read on how dark the fight has become.
    private static final ResourceLocation TEXTURE_DEEP =
            DivineEncounters.id("textures/entity/visage_of_malice_deep.png");

    @Override
    public ResourceLocation getModelResource(VisageOfMaliceEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getAnimationResource(VisageOfMaliceEntity animatable) {
        return ANIMATION;
    }

    @Override
    public ResourceLocation getTextureResource(VisageOfMaliceEntity animatable) {
        return animatable.getPhase() >= 2 ? TEXTURE_DEEP : TEXTURE;
    }

    /// Placeholder rig: tolerate missing bones so the model can be swapped without touching code.
    @Override
    public boolean crashIfBoneMissing() {
        return false;
    }

    /// Hold the head still in world space while the body does whatever it is doing.
    ///
    /// GeckoLib runs this after the animation has posed the rig, so these are corrections layered
    /// on top of the clip rather than a replacement for it. Three of them:
    ///
    /// 1. **Yaw.** The entity's gaze is tracked separately from its body yaw and turns roughly ten
    ///    times faster (see `MaliceController`). Rotating the head by the *difference* means the
    ///    face stays locked on you through an entire body rotation — the cart-and-turret read.
    /// 2. **Pitch.** Same idea vertically, so looking up at it from below is met rather than
    ///    ignored.
    /// 3. **Roll.** The renderer banks the whole model; the head cancels that roll out and stays
    ///    level. A creature's head tilts with its body. This one refuses to, which is the single
    ///    cheapest cue that the thing is mounted rather than grown.
    ///
    /// The neck absorbs a fraction of the yaw so the correction doesn't read as a severed head
    /// floating above the shoulders — enough continuity to look connected, not enough to look
    /// biological.
    @Override
    public void setCustomAnimations(VisageOfMaliceEntity animatable, long instanceId,
                                    AnimationState<VisageOfMaliceEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        var partialTick = animationState == null ? 1.0f : (float) animationState.getPartialTick();
        // Into bone space. See BONE_SIGN.
        var yaw = BONE_SIGN * animatable.gazeOffsetYaw(partialTick) * Mth.DEG_TO_RAD;
        var pitch = BONE_SIGN * animatable.gazeOffsetPitch(partialTick) * Mth.DEG_TO_RAD;
        var roll = animatable.bankAngle(partialTick) * Mth.DEG_TO_RAD;

        var neck = getBone("neck").orElse(null);
        var head = getBone("head").orElse(null);
        // Read before the neck is modified below — the head's pitch is computed relative to it.
        var neckPitch = neck == null ? 0.0f : neck.getRotX();

        if (head != null) {
            head.setRotY(head.getRotY() + yaw * HEAD_SHARE);
            // Absolute, not additive.
            //
            // The clips tilt the neck by anywhere from 14 to 46 degrees as body language, and the
            // head inherits that from its parent. Adding the gaze on top would aim it relative to
            // whatever the clip happened to be doing, so the same target would be met with a
            // different head angle in every animation — the exact opposite of a head that holds
            // still on you. Subtracting the neck's contribution makes the gaze mean the same thing
            // in every clip.
            head.setRotX(pitch - neckPitch);
            // Counter-roll: the body banks, the gaze does not. Not negated — the entity flip that
            // inverts X and Y leaves rotations about Z alone.
            head.setRotZ(head.getRotZ() - roll);
        }
        if (neck != null) {
            neck.setRotY(neck.getRotY() + yaw * NECK_SHARE);
            neck.setRotZ(neck.getRotZ() - roll * NECK_SHARE);
        }
    }

    /// How the head/neck split the gaze correction. The head takes nearly all of it; the neck's
    /// share exists only so the join doesn't look broken.
    private static final float HEAD_SHARE = 0.78f;
    private static final float NECK_SHARE = 0.22f;

    /// GeckoLib bone rotations run opposite to Minecraft's entity rotation convention on X and Y.
    ///
    /// `GeoEntityRenderer` hands models a **pre-negated** head yaw and pitch:
    ///
    /// ```
    /// new EntityModelData(shouldSit, isBaby, -netHeadYaw, -headPitch)
    /// ```
    ///
    /// so the ordinary `bone.setRotX(data.headPitch())` idiom is already applying `-xRot`. Anything
    /// computed in entity convention — as the gaze channel is, since the server needs it in the same
    /// units as everything else — has to be negated on the way in.
    ///
    /// The cause is the `scale(-1, -1, 1)` every entity model is rendered under: conjugating a
    /// rotation by that flip inverts rotations about X and Y and leaves Z unchanged. That is why the
    /// bank counter-roll above is *not* negated while the yaw and pitch are.
    ///
    /// Getting this wrong is close to invisible in code review and unmistakable in game: feeding raw
    /// entity-convention values in made Malice hold its head at a fixed diagonal upward angle,
    /// because its eye sits about five blocks up and the gaze it wanted was therefore always
    /// strongly downward — inverted into a permanent stare at the sky.
    private static final float BONE_SIGN = -1.0f;
}
