package com.cleannrooster.divineencounters.registry;

import com.cleannrooster.divineencounters.DivineEncounters;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;

/// Mod sound events. Every entry currently points at a placeholder OGG (see `sounds.json`); swapping in
/// finished audio is a file drop, no code change.
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(DivineEncounters.MOD_ID, Registries.SOUND_EVENT);

    // --- attack cues, shared by SoundProfiles rather than bound to one boss -------------------------
    public static final RegistrySupplier<SoundEvent> SWING_LIGHT = register("attack.swing_light");
    public static final RegistrySupplier<SoundEvent> SWING_HEAVY = register("attack.swing_heavy");
    public static final RegistrySupplier<SoundEvent> THRUST = register("attack.thrust");
    public static final RegistrySupplier<SoundEvent> CHARGE_RUSH = register("attack.charge_rush");
    public static final RegistrySupplier<SoundEvent> WINDUP_HEAVY = register("attack.windup_heavy");
    public static final RegistrySupplier<SoundEvent> IMPACT = register("attack.impact");
    /// The low layer under a heavy strike. See SoundProfile#playWeight.
    public static final RegistrySupplier<SoundEvent> IMPACT_WEIGHT = register("attack.impact_weight");

    // --- Malice's own attack family --------------------------------------------------------------
    /// Malice used War's swings pitched down, which meant a gold halberd and soul-cutting claws made
    /// the same noise. Two bosses built as deliberate inversions should not share a voice.
    public static final RegistrySupplier<SoundEvent> SPITE_SWIPE = register("attack.spite_swipe");
    public static final RegistrySupplier<SoundEvent> SPITE_CLEAVE = register("attack.spite_cleave");
    public static final RegistrySupplier<SoundEvent> SPITE_PIERCE = register("attack.spite_pierce");
    public static final RegistrySupplier<SoundEvent> SPITE_IMPACT = register("attack.spite_impact");
    public static final RegistrySupplier<SoundEvent> SPITE_WEIGHT = register("attack.spite_weight");

    // --- Visage of Malice: the information game -----------------------------------------------------
    // Weak cues are the ambient evidence scattered across candidate positions while it has no
    // location; the reveal is the single strong directional tell that precedes every manifestation.
    public static final RegistrySupplier<SoundEvent> MALICE_SCRAPE = register("entity.visage_of_malice.scrape");
    public static final RegistrySupplier<SoundEvent> MALICE_REVEAL = register("entity.visage_of_malice.reveal");
    public static final RegistrySupplier<SoundEvent> MALICE_DISSOLVE = register("entity.visage_of_malice.dissolve");
    public static final RegistrySupplier<SoundEvent> MALICE_IDLE = register("entity.visage_of_malice.idle");
    public static final RegistrySupplier<SoundEvent> MALICE_HURT = register("entity.visage_of_malice.hurt");
    public static final RegistrySupplier<SoundEvent> MALICE_DEATH = register("entity.visage_of_malice.death");
    public static final RegistrySupplier<SoundEvent> MALICE_HORN = register("entity.visage_of_malice.horn");
    public static final RegistrySupplier<SoundEvent> MALICE_POUNCE = register("entity.visage_of_malice.pounce");

    // --- Visage of War -----------------------------------------------------------------------------
    public static final RegistrySupplier<SoundEvent> VISAGE_IDLE = register("entity.visage_of_war.idle");
    public static final RegistrySupplier<SoundEvent> VISAGE_HURT = register("entity.visage_of_war.hurt");
    public static final RegistrySupplier<SoundEvent> VISAGE_DEATH = register("entity.visage_of_war.death");
    public static final RegistrySupplier<SoundEvent> VISAGE_WINGS = register("entity.visage_of_war.wings");
    public static final RegistrySupplier<SoundEvent> VISAGE_ASCEND = register("entity.visage_of_war.ascend");

    private ModSounds() {
    }

    private static RegistrySupplier<SoundEvent> register(String path) {
        var id = DivineEncounters.id(path);
        return SOUND_EVENTS.register(path, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
