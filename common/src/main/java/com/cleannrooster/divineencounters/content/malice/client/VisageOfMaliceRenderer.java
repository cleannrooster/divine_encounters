package com.cleannrooster.divineencounters.content.malice.client;

import com.cleannrooster.divineencounters.content.malice.entity.VisageOfMaliceEntity;
import com.cleannrooster.divineencounters.encounter.perception.ObservationCheck;
import com.cleannrooster.divineencounters.encounter.presence.PresenceState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.Color;

/// Renders the Visage of Malice, and — more importantly — decides how much of it you get to see.
///
/// The peripheral fade is computed here rather than server-side because the client has the *real*
/// camera. The server's own observation judgement is a deliberately generous approximation used
/// only to gate behaviour; this is the honest one, used only to draw. Splitting them that way
/// means the fade can be exact without any observation data being trusted from the client.
///
/// Two rules hold no matter how dark the fight gets:
///
/// - looking straight at it always renders it clearly, because observation is the counterplay and
///   it has to be *worth* something;
/// - it never renders at all while unresolved, because in that state it genuinely is not anywhere.
public class VisageOfMaliceRenderer extends GeoEntityRenderer<VisageOfMaliceEntity> {
    /// Never fade below this while it still has a position. The player must always retain enough
    /// information to react to an imminent attack — darkness is never an excuse for unavoidable
    /// damage.
    private static final float MIN_ALPHA = 0.28f;

    public VisageOfMaliceRenderer(EntityRendererProvider.Context context) {
        super(context, new VisageOfMaliceModel());
        this.shadowRadius = 0.5f;
        // Eyes and seams, drawn fullbright over the body from `visage_of_malice_glowmask.png`.
        //
        // This is what makes the fairness floor real rather than aspirational. The body renders
        // through a light-respecting translucent type, so in an unlit arena at the alpha floor it
        // is genuinely almost nothing — and "the player must always retain enough information to
        // react" was, until now, a promise the renderer did not keep.
        //
        // A subclass rather than the stock layer, because the stock one inherits this renderer's
        // fading alpha and would dim exactly when the landmarks matter most. See MaliceGlowLayer.
        addRenderLayer(new MaliceGlowLayer(this));
    }

    @Override
    public void render(VisageOfMaliceEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        // No position means nothing to draw. The boss bar stays up; the body does not.
        if (entity.getPresenceState() == PresenceState.UNRESOLVED) {
            return;
        }
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    /// Translucent so the body can fade toward the edge of vision.
    ///
    /// The readable landmarks do not come from here — this type respects the lightmap, so in the
    /// dark it renders almost nothing. They come from the glow layer added in the constructor,
    /// which is deliberately independent of both light level and this alpha.
    @Override
    public @Nullable RenderType getRenderType(VisageOfMaliceEntity animatable, ResourceLocation texture,
                                              MultiBufferSource bufferSource, float partialTick) {
        return RenderType.entityTranslucent(texture);
    }

    /// Alpha from the true camera: full when looked at, falling away toward the periphery, and
    /// deepening with the fight's phase.
    @Override
    public Color getRenderColor(VisageOfMaliceEntity animatable, float partialTick, int packedLight) {
        return Color.ofRGBA(1.0f, 1.0f, 1.0f, alphaFor(animatable));
    }

    private float alphaFor(VisageOfMaliceEntity animatable) {
        var minecraft = Minecraft.getInstance();
        var camera = minecraft.gameRenderer.getMainCamera();
        var player = minecraft.player;
        if (player == null) {
            return 1.0f;
        }
        var profile = animatable.gloom();

        var eye = camera.getPosition();
        var toEntity = animatable.getBoundingBox().getCenter().subtract(eye);
        var distance = toEntity.length();
        if (distance < 1.0e-4) {
            return 1.0f;
        }
        var look = new net.minecraft.world.phys.Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(), camera.getLookVector().z());
        var dot = Mth.clamp(look.dot(toEntity.scale(1.0 / distance)), -1.0, 1.0);
        var angle = (float) Math.toDegrees(Math.acos(dot));

        // Shared shaping with the server's judgement, so the fade and the mechanic agree in
        // character even though they read different inputs.
        var strength = ObservationCheck.observationStrength(angle, distance, profile);

        // A manifestation is always fully visible: the reveal has to land, or the tell was wasted.
        if (animatable.getPresenceState() == PresenceState.MANIFESTING) {
            return 1.0f;
        }
        if (animatable.getPresenceState() == PresenceState.DISSOLVING) {
            // Visibly dissipating rather than popping out of existence.
            var progress = Mth.clamp(animatable.presence().ticksInState() / 6.0f, 0.0f, 1.0f);
            return Mth.lerp(progress, Math.max(MIN_ALPHA, strength), 0.0f);
        }
        return Mth.clamp(MIN_ALPHA + (1.0f - MIN_ALPHA) * strength, MIN_ALPHA, 1.0f);
    }

    /// Bank the whole body into its drift, and let damage shake the *image* rather than the
    /// creature.
    ///
    /// Both rotations happen about the centre of mass, not the feet — Malice has no feet on the
    /// ground to pivot around, and rolling about the hooves of a hovering body looks like a puppet
    /// on a stick.
    ///
    /// The instability jitter is deliberately high-frequency and small. A hurt animal flinches,
    /// which is a whole-body motion with a direction and a recovery. This has neither: it is the
    /// silhouette failing to hold still for a moment, like a projection losing sync. That is why
    /// `VisageOfMaliceEntity` also suppresses the vanilla hurt tilt — the two read as opposites and
    /// the vanilla one wins if it is left on.
    @Override
    protected void applyRotations(VisageOfMaliceEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);

        var bank = animatable.bankAngle(partialTick);
        var instability = animatable.instability();
        if (Math.abs(bank) < 0.05f && instability < 0.01f) {
            return;
        }

        var pivot = animatable.getBbHeight() * 0.5f;
        poseStack.translate(0.0f, pivot, 0.0f);
        if (Math.abs(bank) >= 0.05f) {
            poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(bank));
        }
        if (instability >= 0.01f) {
            // Two incommensurate frequencies, so it never settles into a readable wobble.
            var time = ageInTicks + partialTick;
            var amount = instability * instability * MAX_JITTER;
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(
                    Mth.sin(time * 2.7f) * amount));
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(
                    Mth.cos(time * 4.1f) * amount));
        }
        poseStack.translate(0.0f, -pivot, 0.0f);
    }

    /// Degrees at full instability. Small on purpose — the effect should register as the image
    /// failing, not as the body being knocked around.
    private static final float MAX_JITTER = 7.0f;
}
