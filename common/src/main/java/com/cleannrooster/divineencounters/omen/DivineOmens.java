package com.cleannrooster.divineencounters.omen;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.encounter.ArenaDefinition;
import com.cleannrooster.divineencounters.encounter.ArenaRegistry;
import com.cleannrooster.divineencounters.encounter.structure.ManifestationEffects;
import com.cleannrooster.divineencounters.encounter.structure.ManifestationOrder;
import com.cleannrooster.divineencounters.registry.ModEntities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/// The two arenas and the omens that summon them.
///
/// This is where the generalised manifestation engine stops being theoretical. Both arenas run the
/// same code; what makes them feel like different events is entirely the data here — the order they
/// assemble in, how fast, and what each emerging section sounds like.
public final class DivineOmens {
    private DivineOmens() {
    }

    // --- arenas -------------------------------------------------------------------------------------

    /// War's tower climbs out of the ground, foundations first. Slower than the cage: this is a
    /// monument being raised, and the player is meant to watch it happen.
    public static final ArenaDefinition WAR_ARENA = ArenaRegistry.register(
            ArenaDefinition.builder(DivineEncounters.id("war_arena"),
                            DivineEncounters.id("war_tower"))
                    .order(ManifestationOrder.BOTTOM_TO_TOP)
                    .pacing(40, 3)
                    .boss(() -> ModEntities.VISAGE_OF_WAR.get())
                    .warningTicks(60)
                    .effects(DivineOmens::towerEffects)
                    .build());

    /// Malice's cage follows its template's authored staging — roots, ribs, walls, canopy — and is
    /// deliberately much faster. The player should register that something closed around them
    /// before they have finished deciding what it is.
    public static final ArenaDefinition MALICE_ARENA = ArenaRegistry.register(
            ArenaDefinition.builder(DivineEncounters.id("malice_arena"),
                            DivineEncounters.id("malice_cage"))
                    .order(ManifestationOrder.STAGED)
                    .pacing(28, 2)
                    .boss(() -> ModEntities.VISAGE_OF_MALICE.get())
                    .warningTicks(40)
                    .effects(DivineOmens::cageEffects)
                    .build());

    // --- omens --------------------------------------------------------------------------------------

    /// War wants open ground with room to raise a tower, so its biome tag covers plains, savanna and
    /// similar. Footprint 22 covers the 43-block tower plus a margin. Relief 6 is what open ground actually
    /// measures once trees are excluded — 4 was authored against a heightmap that counted tree
    /// trunks as terrain, and was unreachable in practice.
    public static final OmenType OMEN_OF_WAR = OmenType.register(
            OmenType.of("war", () -> WAR_ARENA, 22, 6));

    /// Malice wants dark forest. A cage grown between trees does not need a parade square, so a
    /// smaller footprint and more tolerance for uneven ground than War.
    public static final OmenType OMEN_OF_MALICE = OmenType.register(
            OmenType.of("malice", () -> MALICE_ARENA, 16, 8));

    // --- emergence effects --------------------------------------------------------------------------

    /// Stone grinding upward. Effects are placed at each batch's own bounds, so the noise climbs the
    /// tower with the construction rather than playing everywhere at once.
    private static void towerEffects(net.minecraft.server.level.ServerLevel level,
                                     net.minecraft.world.level.levelgen.structure.BoundingBox bounds,
                                     int index, int count, boolean reversing) {
        var x = (bounds.minX() + bounds.maxX()) * 0.5;
        var y = reversing ? bounds.maxY() : bounds.minY();
        var z = (bounds.minZ() + bounds.maxZ()) * 0.5;

        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 10,
                (bounds.maxX() - bounds.minX()) * 0.4 + 0.5, 0.3,
                (bounds.maxZ() - bounds.minZ()) * 0.4 + 0.5, 0.02);
        level.sendParticles(ParticleTypes.CRIT, x, y + 0.5, z, 6, 1.2, 0.4, 1.2, 0.05);
        // Rising pitch as it climbs — the sound of something being completed.
        var pitch = 0.6f + 0.5f * (index / (float) Math.max(1, count));
        level.playSound(null, (int) x, (int) y, (int) z, SoundEvents.DEEPSLATE_PLACE,
                SoundSource.BLOCKS, 1.6f, reversing ? 1.4f - pitch : pitch);
    }

    /// Roots tearing out of the forest floor and folding inward.
    private static void cageEffects(net.minecraft.server.level.ServerLevel level,
                                    net.minecraft.world.level.levelgen.structure.BoundingBox bounds,
                                    int index, int count, boolean reversing) {
        var x = (bounds.minX() + bounds.maxX()) * 0.5;
        var y = (bounds.minY() + bounds.maxY()) * 0.5;
        var z = (bounds.minZ() + bounds.maxZ()) * 0.5;

        level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, x, y, z, 14,
                (bounds.maxX() - bounds.minX()) * 0.4 + 0.5, 0.5,
                (bounds.maxZ() - bounds.minZ()) * 0.4 + 0.5, 0.03);
        level.sendParticles(ParticleTypes.SQUID_INK, x, y, z, 8, 1.0, 0.6, 1.0, 0.01);
        level.playSound(null, (int) x, (int) y, (int) z,
                reversing ? SoundEvents.ROOTS_BREAK : SoundEvents.ROOTS_PLACE,
                SoundSource.BLOCKS, 1.5f, 0.5f + 0.3f * (index / (float) Math.max(1, count)));
        if (index == count - 1 && !reversing) {
            // The lid closing: one unmistakable beat marking the moment escape stops being an option.
            level.playSound(null, (int) x, (int) y, (int) z, SoundEvents.WARDEN_HEARTBEAT,
                    SoundSource.HOSTILE, 2.0f, 0.5f);
        }
    }

    /// Touching this class forces its constants — and therefore their registration — to initialise.
    public static void bootstrap() {
        // Intentionally empty: class initialisation is the work.
    }
}
