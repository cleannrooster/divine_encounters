package com.cleannrooster.divineencounters.content.malice.client;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.content.malice.entity.MaliceEchoEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.Color;

/// Draws a false silhouette using Malice's own rig.
///
/// Sharing the rig is the point — a decoy the player could identify by shape alone would be
/// useless. What separates them is behaviour and sound, never geometry: an echo is always faint,
/// always brief, and never carries the strong directional cue that precedes a real manifestation.
///
/// It fades in and out over its short life so it reads as a trick of the dark rather than a mob
/// that blinked in and out of existence.
public class MaliceEchoRenderer extends GeoEntityRenderer<MaliceEchoEntity> {
    /// Never solid. An echo that rendered at full opacity would be indistinguishable from the real
    /// thing at a glance, which crosses the line from uncertainty into unfairness.
    private static final float PEAK_ALPHA = 0.42f;

    public MaliceEchoRenderer(EntityRendererProvider.Context context) {
        super(context, new EchoModel());
        this.shadowRadius = 0.0f;
    }

    @Override
    public @Nullable RenderType getRenderType(MaliceEchoEntity animatable, ResourceLocation texture,
                                              MultiBufferSource bufferSource, float partialTick) {
        return RenderType.entityTranslucent(texture);
    }

    @Override
    public Color getRenderColor(MaliceEchoEntity animatable, float partialTick, int packedLight) {
        var life = animatable.fade(partialTick);
        // Ramp up quickly, hold, then fade out — a shape that was almost there.
        var envelope = life < 0.25f ? life / 0.25f : Mth.clamp((1.0f - life) / 0.55f, 0.0f, 1.0f);
        return Color.ofRGBA(0.55f, 0.4f, 0.7f, PEAK_ALPHA * envelope);
    }

    /// Malice's model, minus any phase-dependent texture switching — an echo is always the plain
    /// silhouette.
    private static final class EchoModel extends GeoModel<MaliceEchoEntity> {
        private static final ResourceLocation MODEL =
                DivineEncounters.id("geo/visage_of_malice.geo.json");
        private static final ResourceLocation ANIMATION =
                DivineEncounters.id("animations/visage_of_malice.animation.json");
        private static final ResourceLocation TEXTURE =
                DivineEncounters.id("textures/entity/visage_of_malice.png");

        @Override
        public ResourceLocation getModelResource(MaliceEchoEntity animatable) {
            return MODEL;
        }

        @Override
        public ResourceLocation getAnimationResource(MaliceEchoEntity animatable) {
            return ANIMATION;
        }

        @Override
        public ResourceLocation getTextureResource(MaliceEchoEntity animatable) {
            return TEXTURE;
        }

        @Override
        public boolean crashIfBoneMissing() {
            return false;
        }
    }
}
