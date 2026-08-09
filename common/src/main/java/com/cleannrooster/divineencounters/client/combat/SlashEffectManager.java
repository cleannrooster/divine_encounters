package com.cleannrooster.divineencounters.client.combat;

import com.cleannrooster.divineencounters.combat.AttackRegistry;
import com.cleannrooster.divineencounters.combat.net.AttackNetwork;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/// Client-side pool of live attack visuals.
///
/// Both loaders funnel their world-render callback into {@link #render}; the per-tick lifetime and
/// particle work rides Architectury's shared client tick event. Nothing here knows about any particular
/// boss — an entity that fires an attack through the combat API gets its visuals for free.
public final class SlashEffectManager {
    private static final List<SlashEffect> ACTIVE = new ArrayList<>();

    private SlashEffectManager() {
    }

    /// Wire up the packet receiver and the tick hook. Called from client init on both loaders.
    @SuppressWarnings("removal")
    public static void init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, AttackNetwork.SWING, (buffer, context) -> {
            var swing = AttackNetwork.read(buffer);
            context.queue(() -> spawn(swing));
        });
        ClientTickEvent.CLIENT_POST.register(minecraft -> tick());
    }

    private static void spawn(AttackNetwork.Swing swing) {
        var definition = AttackRegistry.lookupForClient(swing.attackId());
        if (definition == null) {
            return;
        }
        ACTIVE.add(new SlashEffect(definition, swing.frame(), swing.attackerId()));
    }

    /// Ages effects out and emits their particles. Particles are spawned here, once per tick, rather than
    /// per frame — a frame-rate-dependent particle count would make the same attack read differently on
    /// different machines.
    private static void tick() {
        if (ACTIVE.isEmpty()) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) {
            ACTIVE.clear();
            return;
        }
        for (Iterator<SlashEffect> it = ACTIVE.iterator(); it.hasNext(); ) {
            var effect = it.next();
            // A travelling attack's volume follows its owner, so the visual has to follow it too.
            if (effect.definition().shape().followsAttacker()) {
                var attacker = level.getEntity(effect.attackerId());
                if (attacker != null) {
                    effect.setFrame(effect.frame().withOrigin(attacker.position()
                            .add(0.0, effect.definition().offsetVertical(), 0.0)));
                }
            }
            if (effect.isSwinging(1.0f)) {
                AttackParticleEmitter.emit(level, effect);
            }
            effect.tick();
            if (effect.isExpired()) {
                it.remove();
            }
        }
    }

    /// Draw every live slash. Called from each loader's world-render hook with a camera-relative pose
    /// stack.
    public static void render(PoseStack poseStack, Camera camera, MultiBufferSource.BufferSource buffers,
                              float partialTick) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (var effect : ACTIVE) {
            SlashRenderer.render(effect, poseStack, camera, buffers, partialTick);
        }
        buffers.endBatch();
    }

    /// Drop everything — on level change or disconnect.
    public static void clear() {
        ACTIVE.clear();
    }
}
