package com.cleannrooster.divineencounters.content.malice.client;

import com.cleannrooster.divineencounters.content.malice.entity.VisageOfMaliceEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.texture.AutoGlowingTexture;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/// Malice's eyes and seams, drawn at full opacity no matter how faded her body is.
///
/// GeckoLib's stock glow layer passes the renderer's own render colour through to the glow pass:
///
/// ```java
/// getRenderer().reRender(..., getRenderer().getRenderColor(animatable, partialTick, packedLight).argbInt());
/// ```
///
/// For almost every mob that is right — a fading entity should fade whole. For this one it is
/// exactly backwards. Her renderer drives alpha down to a 0.28 floor at the edge of vision and to
/// zero while dissolving, and the entire point of the glow layer here is to be the thing that
/// *survives* that. Inheriting the fade would leave the landmarks dimmest precisely when the player
/// most needs them, which is the failure the layer was added to prevent.
///
/// So this passes opaque white instead. The body still fades exactly as before; the eyes do not.
public class MaliceGlowLayer extends AutoGlowingGeoLayer<VisageOfMaliceEntity> {
    /// Opaque white: full brightness, no tint, no fade. The colours come from the texture.
    private static final int OPAQUE = 0xFFFFFFFF;

    public MaliceGlowLayer(GeoRenderer<VisageOfMaliceEntity> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, VisageOfMaliceEntity animatable, BakedGeoModel bakedModel,
                       @Nullable RenderType renderType, MultiBufferSource bufferSource,
                       @Nullable VertexConsumer buffer, float partialTick, int packedLight,
                       int packedOverlay) {
        // Invisible covers the unresolved state; there is nothing to light up when she is nowhere.
        if (animatable.isInvisible()) {
            return;
        }
        var glowType = AutoGlowingTexture.getRenderType(getTextureResource(animatable));
        if (glowType == null) {
            return;
        }
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, glowType,
                bufferSource.getBuffer(glowType), partialTick,
                LightTexture.FULL_BRIGHT, packedOverlay, OPAQUE);
    }
}
