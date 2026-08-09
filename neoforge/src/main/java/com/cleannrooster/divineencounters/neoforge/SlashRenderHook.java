package com.cleannrooster.divineencounters.neoforge;

import com.cleannrooster.divineencounters.client.combat.SlashEffectManager;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/// NeoForge counterpart to the Fabric world-render shim. Both loaders do nothing but hand a
/// camera-relative pose stack to {@link SlashEffectManager#render}; the slash geometry itself is written
/// once, in common.
public final class SlashRenderHook {
    private SlashRenderHook() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                return;
            }
            SlashEffectManager.render(
                    event.getPoseStack(),
                    event.getCamera(),
                    Minecraft.getInstance().renderBuffers().bufferSource(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false));
        });
    }
}
