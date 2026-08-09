package com.cleannrooster.divineencounters.fabric;

import com.cleannrooster.divineencounters.client.DivineEncountersClient;
import com.cleannrooster.divineencounters.client.combat.SlashEffectManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;

public final class DivineEncountersFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DivineEncountersClient.init();

        // Loader shim for the shared slash renderer. Drawn after translucent terrain so the arcs blend
        // over the world; all the actual geometry lives in common.
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context ->
                SlashEffectManager.render(
                        context.matrixStack(),
                        context.camera(),
                        Minecraft.getInstance().renderBuffers().bufferSource(),
                        context.tickCounter().getGameTimeDeltaPartialTick(false)));
    }
}
