package com.cleannrooster.divineencounters.content.visage.client;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.content.visage.entity.VisageOfWarEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class VisageOfWarModel extends GeoModel<VisageOfWarEntity> {
    private static final ResourceLocation MODEL = DivineEncounters.id("geo/visage_of_war.geo.json");
    private static final ResourceLocation ANIMATION =
            DivineEncounters.id("animations/visage_of_war.animation.json");
    private static final ResourceLocation TEXTURE =
            DivineEncounters.id("textures/entity/visage_of_war.png");
    /// Swapped in once Divine Pursuit is active, so the phase change is visible at a glance.
    private static final ResourceLocation TEXTURE_ASCENDED =
            DivineEncounters.id("textures/entity/visage_of_war_ascended.png");

    @Override
    public ResourceLocation getModelResource(VisageOfWarEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getAnimationResource(VisageOfWarEntity animatable) {
        return ANIMATION;
    }

    @Override
    public ResourceLocation getTextureResource(VisageOfWarEntity animatable) {
        return animatable.isDivinePursuit() ? TEXTURE_ASCENDED : TEXTURE;
    }

    /// Placeholder rig: tolerate missing bones so the model can be swapped without touching code.
    @Override
    public boolean crashIfBoneMissing() {
        return false;
    }
}
