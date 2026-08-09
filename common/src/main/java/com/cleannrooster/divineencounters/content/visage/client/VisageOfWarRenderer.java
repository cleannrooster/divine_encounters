package com.cleannrooster.divineencounters.content.visage.client;

import com.cleannrooster.divineencounters.content.visage.entity.VisageOfWarEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/// Renderer for the Visage. Deliberately thin: her attacks are drawn by the shared slash renderer, not
/// from here, so a second boss using the same combat system needs no rendering code of its own beyond its
/// model.
///
/// The one thing it does add is three-axis body orientation. Yaw comes from the entity as usual; roll and
/// pitch come from the controller, which is the only thing that knows a reposition arc curves left rather
/// than right. Applying them here — rather than baking them into every animation — means any reposition
/// move gets its banking for free.
public class VisageOfWarRenderer extends GeoEntityRenderer<VisageOfWarEntity> {
    public VisageOfWarRenderer(EntityRendererProvider.Context context) {
        super(context, new VisageOfWarModel());
        this.shadowRadius = 0.6f;
    }

    @Override
    protected void applyRotations(VisageOfWarEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);

        var bank = animatable.bankAngle(partialTick);
        var pitch = animatable.bodyPitch(partialTick);
        if (Math.abs(bank) < 0.05f && Math.abs(pitch) < 0.05f) {
            return;
        }
        // Rotate about her own centre of mass rather than her feet, so banking looks like a lean into the
        // turn instead of a pivot on the floor.
        var pivot = animatable.getBbHeight() * 0.5f;
        poseStack.translate(0.0f, pivot, 0.0f);
        poseStack.mulPose(Axis.ZP.rotationDegrees(bank));
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.translate(0.0f, -pivot, 0.0f);
    }
}
