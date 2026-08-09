package com.cleannrooster.divineencounters.content.malice.ai;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.combat.AttackHooks;
import com.cleannrooster.divineencounters.config.DivineConfig;
import com.cleannrooster.divineencounters.combat.AttackPlane;
import com.cleannrooster.divineencounters.combat.AttackRegistry;
import com.cleannrooster.divineencounters.combat.AttackShape;
import com.cleannrooster.divineencounters.combat.AttackVisuals;
import com.cleannrooster.divineencounters.combat.DirectionalAttack;
import com.cleannrooster.divineencounters.combat.Handedness;
import com.cleannrooster.divineencounters.combat.SlashProfile;
import com.cleannrooster.divineencounters.combat.SoundProfile;
import com.cleannrooster.divineencounters.combat.SwingPath;
import com.cleannrooster.divineencounters.combat.TrackingMode;
import com.cleannrooster.divineencounters.content.malice.entity.VisageOfMaliceEntity;
import com.cleannrooster.divineencounters.encounter.presence.ManifestKind;
import com.cleannrooster.divineencounters.registry.ModSounds;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/// The Visage of Malice's moveset, expressed as data on the shared directional-attack API.
///
/// The whole moveset reuses War's combat pipeline unchanged — geometry, damage, timing, hit
/// detection, slash rendering, particles, sound. What differs is entirely in the numbers and the
/// visual profiles: shorter reaches, faster basics, and dim violet arcs instead of bright gold.
///
/// ### Attacks that require having been unseen
/// Some attacks are only legal from an unresolved state. Rather than each one implementing the
/// mechanic, they simply *declare* a {@link ManifestKind} here, and
/// {@link com.cleannrooster.divineencounters.encounter.presence.SuperpositionController} finds a
/// position that satisfies it. That is why Backbite has no code of its own about running behind
/// anybody: it does not run anywhere, it resolves.
///
/// Every one of those still passes through the fairness chain — candidate, directional tell,
/// manifestation, windup, damage — because the controller enforces it centrally.
public final class MaliceAttacks {
    private MaliceAttacks() {
    }

    /// Attacks that may only be launched out of an unresolved state, and the kind of position each
    /// needs. Anything absent from this map is an ordinary attack usable while visible.
    private static final Map<DirectionalAttack, ManifestKind> AMBUSH_KINDS = new HashMap<>();

    private static DirectionalAttack ambush(DirectionalAttack attack, ManifestKind kind) {
        AMBUSH_KINDS.put(attack, kind);
        return attack;
    }

    /// The manifestation category an attack needs, or null when it can be used normally.
    public static @Nullable ManifestKind ambushKind(DirectionalAttack attack) {
        return AMBUSH_KINDS.get(attack);
    }

    public static boolean requiresAmbush(DirectionalAttack attack) {
        return AMBUSH_KINDS.containsKey(attack);
    }

    // --- sound profiles ----------------------------------------------------------------------------

    private static final SoundProfile CLAW = SoundProfile.builder()
            .swingStart(ModSounds.SPITE_SWIPE)
            .impact(ModSounds.SPITE_IMPACT)
            // Below a player's own sweep, and heavily jittered. The sources are physical on purpose —
            // claws moving through air — so the pitch is what keeps it from reading as somebody
            // swinging a sword at you.
            .volume(0.95f).pitch(0.86f).pitchJitter(0.16f)
            .build();

    private static final SoundProfile HEAVY = SoundProfile.builder()
            .windup(ModSounds.MALICE_SCRAPE)
            .swingStart(ModSounds.SPITE_CLEAVE)
            .impact(ModSounds.SPITE_IMPACT)
            .volume(1.15f).pitch(0.72f).pitchJitter(0.05f)
            .weight(ModSounds.SPITE_WEIGHT)
            .build();

    private static final SoundProfile PIERCE = SoundProfile.builder()
            .swingStart(ModSounds.SPITE_PIERCE)
            .impact(ModSounds.SPITE_IMPACT)
            .volume(0.95f).pitch(0.93f).pitchJitter(0.12f)
            .build();

    private static final SoundProfile HORN = SoundProfile.builder()
            .windup(ModSounds.MALICE_HORN)
            .swingStart(ModSounds.SPITE_CLEAVE)
            .impact(ModSounds.SPITE_IMPACT)
            .volume(1.2f).pitch(0.8f)
            .weight(ModSounds.SPITE_WEIGHT)
            .build();

    // --- basic attacks -----------------------------------------------------------------------------

    /// Fast short sweep with strong lateral coverage — the bread-and-butter poke that punishes
    /// standing at claw range. Travels left to right off its leading hand.
    public static final DirectionalAttack HOOKING_SWIPE = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("hooking_swipe"), AttackShape.HORIZONTAL_ARC)
                    .swing(SwingPath.leftToRight(115.0f))
                    .range(3.2).innerRadius(0.7)
                    .verticalExtent(2.2)
                    .origin(0.2, 0.3, 1.2)
                    .damage(DivineConfig.damage("hooking_swipe", 6.0f)).knockback(0.4, 0.08)
                    .timing(8, 5, 9)
                    .advance(0.3)
                    .tracking(TrackingMode.REDUCED, TrackingMode.LOCKED)
                    .animation(VisageOfMaliceEntity.ANIM_HOOKING_SWIPE)
                    .sounds(CLAW)
                    .particles(AttackVisuals.spiteParticles(26, 2.8f))
                    .slash(AttackVisuals.spiteCrescent(0.72f, 24))
                    .build());

    /// Head down, horns forward, committed. Frequently the follow-up to a flank manifestation, so
    /// it doubles as the reveal's punish — but it always shows its brace first.
    public static final DirectionalAttack HORN_RUSH = ambush(AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("horn_rush"), AttackShape.CHARGE_PATH)
                    .swing(SwingPath.lane(0.0f))
                    .range(4.0).innerRadius(0.0)
                    .width(1.9).verticalExtent(2.2)
                    .origin(0.0, 0.0, 0.9)
                    .damage(DivineConfig.damage("horn_rush", 11.0f)).knockback(0.9, 0.25)
                    // A long brace: this is the most committed thing it does while visible.
                    .timing(16, 14, 15)
                    .tracking(TrackingMode.MINIMAL, TrackingMode.LOCKED)
                    .animation(VisageOfMaliceEntity.ANIM_HORN_RUSH)
                    .sounds(HORN)
                    .particles(AttackVisuals.spiteParticles(22, 3.0f))
                    .slash(AttackVisuals.chargeTrail())
                    .build()), ManifestKind.FLANK);

    /// An unnatural rear-quarter swipe — the torso twists without the legs following. Exists to
    /// punish players who reflexively run behind it after every attack.
    public static final DirectionalAttack RAKING_BACKHAND = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("raking_backhand"), AttackShape.HORIZONTAL_ARC)
                    .swing(SwingPath.rightToLeft(150.0f).withYawOffset(150.0f))
                    .range(3.4).innerRadius(0.6)
                    .verticalExtent(2.4)
                    .origin(-0.3, 0.3, 1.1)
                    .damage(DivineConfig.damage("raking_backhand", 8.0f)).knockback(0.7, 0.15)
                    .timing(12, 6, 13)
                    .tracking(TrackingMode.LOCKED, TrackingMode.LOCKED)
                    .animation(VisageOfMaliceEntity.ANIM_BACKHAND)
                    .sounds(CLAW)
                    .particles(AttackVisuals.spiteParticles(24, 3.0f))
                    .slash(AttackVisuals.spiteCrescent(0.7f, 26))
                    .build());

    /// Short bounding leap with a small landing impact. Tempo, not damage.
    public static final DirectionalAttack LOW_POUNCE = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("low_pounce"), AttackShape.RADIAL_IMPACT)
                    .swing(SwingPath.fixedSpan(360.0f))
                    .range(2.2).innerRadius(0.0)
                    .damage(DivineConfig.damage("low_pounce", 7.0f)).knockback(0.8, 0.3)
                    .origin(0.0, 0.0, 0.3)
                    .timing(9, 3, 10)
                    .advance(3.2)
                    .tracking(TrackingMode.REDUCED, TrackingMode.LOCKED)
                    .animation(VisageOfMaliceEntity.ANIM_LOW_POUNCE)
                    .sounds(CLAW)
                    .particles(AttackVisuals.impactParticles(26))
                    .slash(SlashProfile.NONE)
                    .build());

    /// Very narrow piercing strike. Small hitbox, high precision, hurts. The usual follow-up when
    /// it appears at a flank.
    public static final DirectionalAttack NEEDLE_THRUST = ambush(AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("needle_thrust"), AttackShape.THRUST_LANE)
                    .swing(SwingPath.lane(0.0f).withHandedness(Handedness.RIGHT))
                    .range(4.6).innerRadius(0.6)
                    .width(1.0).verticalExtent(1.4)
                    .origin(0.3, 0.25, 1.3)
                    .damage(DivineConfig.damage("needle_thrust", 10.0f)).knockback(0.3, 0.05).armorPierce(0.2f)
                    .timing(11, 4, 10)
                    .advance(0.8)
                    .tracking(TrackingMode.REDUCED, TrackingMode.LOCKED)
                    .animation(VisageOfMaliceEntity.ANIM_NEEDLE_THRUST)
                    .sounds(PIERCE)
                    .particles(AttackVisuals.spiteThrustParticles(28))
                    .slash(AttackVisuals.spiteThrust())
                    .build()), ManifestKind.FLANK);

    // --- strong attacks ----------------------------------------------------------------------------

    /// The signature unseen punish. Only legal from an unresolved state, and only from behind.
    ///
    /// It never simulates running around the player — it resolves at a rear candidate that the
    /// resolver has already confirmed nobody is watching, announces itself, then strikes. The tell
    /// plus the windup is the dodge window, and both are generous enough to react to.
    public static final DirectionalAttack BACKBITE = ambush(AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("backbite"), AttackShape.THRUST_LANE)
                    .swing(SwingPath.lane(20.0f).withHandedness(Handedness.RIGHT))
                    .range(4.2).innerRadius(0.5)
                    .width(1.5).verticalExtent(2.0)
                    .origin(0.2, 0.2, 1.2)
                    .damage(DivineConfig.damage("backbite", 15.0f)).knockback(0.8, 0.2).armorPierce(0.3f)
                    // Deliberately not the fastest thing it does: an ambush the player cannot react
                    // to is just unavoidable damage wearing a mechanic's clothes.
                    .timing(14, 5, 16)
                    .advance(1.0)
                    .tracking(TrackingMode.MINIMAL, TrackingMode.LOCKED)
                    .animation(VisageOfMaliceEntity.ANIM_BACKBITE)
                    .sounds(PIERCE)
                    .particles(AttackVisuals.spiteThrustParticles(40))
                    .slash(AttackVisuals.spiteThrust())
                    .build()), ManifestKind.REAR_AMBUSH);

    /// Broad frontal slash that also drags the light down behind it. High damage, clear windup,
    /// and the one attack that wants to be seen arriving.
    public static final DirectionalAttack BLACK_SWEEP = ambush(AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("black_sweep"), AttackShape.HORIZONTAL_ARC)
                    .swing(SwingPath.rightToLeft(140.0f))
                    .range(5.0).innerRadius(0.8)
                    .verticalExtent(2.8)
                    .origin(0.0, 0.3, 1.2)
                    .damage(DivineConfig.damage("black_sweep", 14.0f)).knockback(0.9, 0.2)
                    .timing(22, 8, 18)
                    .advance(0.5)
                    .tracking(TrackingMode.MINIMAL, TrackingMode.LOCKED)
                    .animation(VisageOfMaliceEntity.ANIM_BLACK_SWEEP)
                    .sounds(HEAVY)
                    .particles(AttackVisuals.spiteParticles(48, 3.4f))
                    .slash(AttackVisuals.spiteWideCrescent())
                    .hooks(AttackHooks.builder()
                            .onActiveStart(execution -> {
                                if (execution.attacker() instanceof VisageOfMaliceEntity malice) {
                                    malice.onBlackSweep();
                                }
                            })
                            .build())
                    .build()), ManifestKind.FRONTAL_REVEAL);

    /// The delayed strike on a remembered position. The mark is readable and the answer is simply
    /// to move — no interaction, nothing to clear, nowhere to stand.
    ///
    /// Uses {@code AttackRunner.startAt} so the strike resolves where the mark was left rather than
    /// wherever Malice happens to be by then.
    public static final DirectionalAttack GRUDGE = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("grudge"), AttackShape.RADIAL_IMPACT)
                    .swing(SwingPath.fixedSpan(360.0f))
                    .range(3.0).innerRadius(0.0)
                    .damage(DivineConfig.damage("grudge", 13.0f)).knockback(1.0, 0.4)
                    .origin(0.0, 0.0, 0.2)
                    .timing(0, 3, 8)
                    .tracking(TrackingMode.LOCKED, TrackingMode.LOCKED)
                    .animation(VisageOfMaliceEntity.ANIM_GRUDGE)
                    .sounds(HEAVY)
                    .particles(AttackVisuals.impactParticles(50))
                    .slash(SlashProfile.NONE)
                    .build());

    /// A near-180 hooking slash across the whole front. Enormous frontal coverage with a long
    /// windup, and a genuinely safe rear angle — so pressing in and around beats it, while backing
    /// straight off does not.
    public static final DirectionalAttack CRESCENT_OF_SPITE = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("crescent_of_spite"), AttackShape.HORIZONTAL_ARC)
                    .swing(SwingPath.leftToRight(172.0f))
                    .range(5.4).innerRadius(1.0)
                    .verticalExtent(3.0)
                    .origin(0.0, 0.3, 1.2)
                    .damage(DivineConfig.damage("crescent_of_spite", 16.0f)).knockback(1.1, 0.25)
                    .timing(26, 10, 20)
                    .advance(0.4)
                    .tracking(TrackingMode.MINIMAL, TrackingMode.LOCKED)
                    .animation(VisageOfMaliceEntity.ANIM_CRESCENT)
                    .sounds(HEAVY)
                    .particles(AttackVisuals.spiteParticles(54, 3.6f))
                    .slash(AttackVisuals.spiteWideCrescent())
                    .build());

    /// The dive off a perch. Requires an elevated manifestation, so it simply never comes up in an
    /// arena-less fight — the candidate pool has nothing that satisfies it.
    public static final DirectionalAttack POUNCE_STRIKE = ambush(AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("pounce_strike"), AttackShape.RADIAL_IMPACT)
                    .swing(SwingPath.fixedSpan(360.0f))
                    .range(3.0).innerRadius(0.0)
                    .damage(DivineConfig.damage("pounce_strike", 14.0f)).knockback(1.0, 0.35)
                    .origin(0.0, 0.0, 0.3)
                    // The windup is the crouch on the perch: long, obvious, and from a fixed place.
                    .timing(20, 4, 14)
                    .tracking(TrackingMode.MINIMAL, TrackingMode.LOCKED)
                    .animation(VisageOfMaliceEntity.ANIM_POUNCE)
                    .sounds(HEAVY)
                    .particles(AttackVisuals.impactParticles(44))
                    .slash(SlashProfile.NONE)
                    .build()), ManifestKind.PERCH);

    /// Touching this class forces its constants — and therefore their registration — to initialise.
    public static void bootstrap() {
        // Intentionally empty: class initialisation is the work.
    }
}
