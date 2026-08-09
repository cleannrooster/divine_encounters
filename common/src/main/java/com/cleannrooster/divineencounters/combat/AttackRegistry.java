package com.cleannrooster.divineencounters.combat;

import com.cleannrooster.divineencounters.DivineEncounters;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/// Registry of every {@link DirectionalAttack} in the game, keyed by id.
///
/// Both sides register the same definitions during common init. That is what lets an attack be networked
/// as a single {@link ResourceLocation} plus a {@link AttackFrame} — the client already knows the arc
/// span, the reach, the density curve and the ribbon colour, so nothing about the shape has to be sent.
public final class AttackRegistry {
    private static final Map<ResourceLocation, DirectionalAttack> ATTACKS = new LinkedHashMap<>();

    private AttackRegistry() {
    }

    /// Register a definition. Returns the same instance so registration can be inlined into a constant:
    /// `public static final DirectionalAttack LANCE = AttackRegistry.register(builder.build());`
    public static DirectionalAttack register(DirectionalAttack attack) {
        var previous = ATTACKS.putIfAbsent(attack.id(), attack);
        if (previous != null && previous != attack) {
            throw new IllegalStateException("Duplicate directional attack id: " + attack.id());
        }
        return attack;
    }

    public static @Nullable DirectionalAttack get(ResourceLocation id) {
        return ATTACKS.get(id);
    }

    /// Look up a definition received over the network, logging rather than throwing so a version mismatch
    /// costs a missing visual instead of a client crash.
    public static @Nullable DirectionalAttack lookupForClient(ResourceLocation id) {
        var attack = ATTACKS.get(id);
        if (attack == null) {
            DivineEncounters.LOGGER.warn("Received an unknown directional attack id: {}", id);
        }
        return attack;
    }

    public static Collection<DirectionalAttack> all() {
        return Collections.unmodifiableCollection(ATTACKS.values());
    }
}
