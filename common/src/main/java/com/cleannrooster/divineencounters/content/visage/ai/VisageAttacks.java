package com.cleannrooster.divineencounters.content.visage.ai;

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
import com.cleannrooster.divineencounters.combat.SwingDynamics;
import com.cleannrooster.divineencounters.combat.SwingPath;
import com.cleannrooster.divineencounters.combat.TrackingMode;
import com.cleannrooster.divineencounters.content.visage.entity.VisageOfWarEntity;
import com.cleannrooster.divineencounters.registry.ModSounds;

/// The Visage of War's whole moveset, expressed as data.
///
/// Every entry here is a complete attack: geometry, damage, timing, movement, tracking, animation, sound
/// and visuals. There is no per-attack code in the entity — the state machine picks one of these and
/// hands it to the {@link com.cleannrooster.divineencounters.combat.AttackRunner}, which does the rest.
/// Tuning the fight means editing numbers in this file.
///
/// ### Swings are stated, not inferred
/// Each cut declares a {@link SwingPath}: the plane it travels through, which side the blade starts on,
/// and which way it moves. The rendered arc follows those angles, and so does the animation — the
/// placeholder clips are generated from the same numbers by `tools/gen_visage_animations.py` — so the
/// visual reads as attached to the weapon rather than drawn independently of it. Change a swing's angles
/// here and re-run that script to keep the clip in step.
///
/// ### Swing dynamics
/// Cleaves declare {@link SwingDynamics#FORCEFUL} and driven lances {@link SwingDynamics#LUNGE};
/// everything else stays `STEADY`. The curve reshapes swing progress inside
/// {@link com.cleannrooster.divineencounters.combat.AttackGeometry}, so the damage sweep, the drawn
/// ribbon and the particles accelerate as one — the arc is still exactly the volume that can hit
/// you, it simply crosses that volume fastest in the middle. Total active duration is unchanged.
///
/// Charges, the radial impact and the anti-body-block cone are deliberately left steady. A charge's
/// force already comes from the controller's own travel, and shaping a shape that resolves all at
/// once would do nothing but confuse the reading of it.
///
/// ### Rhythm
/// Timings are in ticks (20 per second). This pass deliberately runs attacks 10-30% slower than the
/// movement around them: the Visage repositions fast and strikes deliberately. Relative hierarchy is
/// preserved — basics stay quicker than strong attacks, and strong attacks stay heavily telegraphed.
public final class VisageAttacks {
    private VisageAttacks() {
    }

    // --- shared sound profiles ---------------------------------------------------------------------

    private static final SoundProfile LIGHT_SWING = SoundProfile.builder()
            .swingStart(ModSounds.SWING_LIGHT)
            .impact(ModSounds.IMPACT)
            .volume(1.0f).pitch(1.1f).pitchJitter(0.12f)
            .build();

    private static final SoundProfile HEAVY_SWING = SoundProfile.builder()
            .windup(ModSounds.WINDUP_HEAVY)
            .swingStart(ModSounds.SWING_HEAVY)
            .impact(ModSounds.IMPACT)
            .volume(1.3f).pitch(0.85f).pitchJitter(0.06f)
            .weight(ModSounds.IMPACT_WEIGHT)
            .build();

    private static final SoundProfile PIERCE = SoundProfile.builder()
            .swingStart(ModSounds.THRUST)
            .impact(ModSounds.IMPACT)
            .volume(1.0f).pitch(1.0f).pitchJitter(0.1f)
            .build();

    private static final SoundProfile RUSH = SoundProfile.builder()
            .windup(ModSounds.VISAGE_WINGS)
            .swingStart(ModSounds.CHARGE_RUSH)
            .impact(ModSounds.IMPACT)
            .volume(1.2f).pitch(0.95f)
            .weight(ModSounds.IMPACT_WEIGHT)
            .build();

    // --- basic attacks -----------------------------------------------------------------------------

    /// Fast piercing lunge. Long enough to catch a player who simply walks backwards, narrow enough that
    /// stepping sideways beats it. Tracking is cut hard at commit so the lane it telegraphs is the lane
    /// it travels.
    ///
    /// Right-handed: the lane leaves her right shoulder, which is where the lance is.
    public static final DirectionalAttack LANCE_THRUST = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("lance_thrust"), AttackShape.THRUST_LANE)
                    .swing(SwingPath.lane(0.0f).withHandedness(Handedness.RIGHT)
                            .withDynamics(SwingDynamics.LUNGE))
                    .range(5.6).innerRadius(0.8)
                    .width(1.8).verticalExtent(1.9)
                    .origin(0.4, 0.3, 1.05)
                    .damage(DivineConfig.damage("lance_thrust", 7.0f)).knockback(0.35, 0.05)
                    // 13 ticks = 0.65s telegraph (was 10). Slower to read, same reach.
                    .timing(13, 5, 11)
                    .advance(1.0)
                    .tracking(TrackingMode.REDUCED, TrackingMode.LOCKED)
                    .animation(VisageOfWarEntity.ANIM_THRUST)
                    .sounds(PIERCE)
                    .particles(AttackVisuals.thrustParticles(30))
                    .slash(AttackVisuals.thrustStreak())
                    .build());

    /// Quick horizontal cut across the front, travelling right to left — she winds the lance back over
    /// her right shoulder and carries it across her body. Punishes strafing at knife range; harmless from
    /// behind.
    public static final DirectionalAttack WING_SWEEP = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("wing_sweep"), AttackShape.HORIZONTAL_ARC)
                    .swing(SwingPath.rightToLeft(105.0f).withDynamics(SwingDynamics.FORCEFUL))
                    .range(3.9).innerRadius(0.9)
                    .verticalExtent(2.4)
                    .origin(0.2, 0.35, 1.1)
                    .damage(DivineConfig.damage("wing_sweep", 6.5f)).knockback(0.45, 0.1)
                    // 9/7/10 (was 7/5/8): a visible wind-back and a slower blade.
                    .timing(9, 7, 10)
                    .advance(0.35)
                    .tracking(TrackingMode.REDUCED, TrackingMode.LOCKED)
                    .animation(VisageOfWarEntity.ANIM_SWEEP)
                    .sounds(LIGHT_SWING)
                    .particles(AttackVisuals.sweepParticles(30, 2.8f))
                    .slash(AttackVisuals.crescent(0.88f, 26))
                    .build());

    /// Heavier overhead on a steep diagonal: the blade starts raised over her right shoulder and finishes
    /// low on her left. The punish for standing square in front of her and trading.
    public static final DirectionalAttack DESCENDING_CUT = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("descending_cut"), AttackShape.DIAGONAL_ARC)
                    .swing(SwingPath.overhead(120.0f, AttackPlane.DIAGONAL_STEEP)
                            .withYawOffset(-8.0f)
                            .withDynamics(SwingDynamics.FORCEFUL))
                    .range(4.2).innerRadius(0.7)
                    .verticalExtent(2.0)
                    .origin(0.3, 0.35, 1.5)
                    .damage(DivineConfig.damage("descending_cut", 10.0f)).knockback(0.6, 0.28)
                    // 17/7/14 (was 13/5/12).
                    .timing(17, 7, 14)
                    .lift(0.9)
                    .advance(0.5)
                    .tracking(TrackingMode.REDUCED, TrackingMode.LOCKED)
                    .animation(VisageOfWarEntity.ANIM_DESCENDING_CUT)
                    .sounds(HEAVY_SWING)
                    .particles(AttackVisuals.sweepParticles(34, 3.2f))
                    .slash(AttackVisuals.crescent(0.92f, 28))
                    .build());

    /// Anti-body-block shove. Low damage, short cooldown, exists purely so hugging her hitbox is not a
    /// safe place to stand. No dramatic arc — the motion is the read, so it keeps a fixed span with no
    /// direction of travel to contradict.
    public static final DirectionalAttack PRESSING_ADVANCE = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("pressing_advance"), AttackShape.FRONTAL_CONE)
                    .swing(SwingPath.fixedSpan(80.0f))
                    .range(2.4).innerRadius(0.0)
                    .verticalExtent(2.4)
                    .origin(0.0, 0.0, 1.0)
                    .damage(DivineConfig.damage("pressing_advance", 3.0f)).knockback(0.95, 0.32)
                    // Kept fast on purpose: this is a reactive poke, not a telegraphed commitment.
                    .timing(6, 3, 7)
                    .advance(0.4)
                    .tracking(TrackingMode.FULL, TrackingMode.REDUCED)
                    .animation(VisageOfWarEntity.ANIM_SHOVE)
                    .sounds(LIGHT_SWING)
                    .particles(AttackVisuals.impactParticles(14))
                    .slash(SlashProfile.NONE)
                    .build());

    // --- strong attacks ----------------------------------------------------------------------------

    /// Heaven's Divide. A long windup with the blade raised, then a narrow vertical wall of force driven
    /// eleven blocks straight ahead. Enormous damage, enormous recovery, and completely avoidable by not
    /// standing in a two-and-a-half-block-wide line.
    ///
    /// Built as a lane rolled onto its side: `width` becomes the blade's height and `verticalExtent` its
    /// horizontal thickness, so the ribbon draws as a tall forward cleave while the damage volume stays
    /// the narrow corridor the numbers describe.
    public static final DirectionalAttack HEAVENS_DIVIDE = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("heavens_divide"), AttackShape.THRUST_LANE)
                    .swing(SwingPath.lane(78.0f).withDynamics(SwingDynamics.LUNGE))
                    .range(11.0).innerRadius(1.0)
                    .width(5.0).verticalExtent(2.6)
                    .origin(0.0, 0.0, 1.4)
                    .damage(DivineConfig.damage("heavens_divide", 20.0f)).knockback(1.1, 0.35).armorPierce(0.35f)
                    // 27 ticks = 1.35s telegraph (was 22). The single most readable moment in the fight.
                    .timing(27, 8, 24)
                    .lift(0.6)
                    .tracking(TrackingMode.MINIMAL, TrackingMode.LOCKED)
                    .animation(VisageOfWarEntity.ANIM_HEAVY_CLEAVE)
                    .sounds(HEAVY_SWING)
                    .particles(AttackVisuals.thrustParticles(56))
                    .slash(AttackVisuals.heavyCrescent())
                    .build());

    /// Valkyrie's Passage. She commits to a heading and rushes through it, damaging whatever the path
    /// crosses without stopping on contact. The controller supplies the travel; the attack supplies the
    /// volume that rides along with her.
    public static final DirectionalAttack VALKYRIES_PASSAGE = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("valkyries_passage"), AttackShape.CHARGE_PATH)
                    .swing(SwingPath.lane(0.0f))
                    // `range` is the length of the trail drawn behind her, not her travel distance.
                    .range(5.0).innerRadius(0.0)
                    .width(2.4).verticalExtent(2.8)
                    .origin(0.0, 0.0, 1.0)
                    .damage(DivineConfig.damage("valkyries_passage", 13.0f)).knockback(0.8, 0.3)
                    // 15/16/17 (was 12/16/14): a longer, clearer commit before the rush.
                    .timing(15, 16, 17)
                    .tracking(TrackingMode.MINIMAL, TrackingMode.LOCKED)
                    .animation(VisageOfWarEntity.ANIM_CHARGE)
                    .sounds(RUSH)
                    .particles(AttackVisuals.chargeParticles(24))
                    .slash(AttackVisuals.chargeTrail())
                    .build());

    /// Sundering Sweep. A 162-degree cleave travelling left to right — the mirror of Wing Sweep's
    /// direction, so back-to-back sweeps read as different moves. Backing away does not beat it; moving
    /// around her does, which is the whole point.
    public static final DirectionalAttack SUNDERING_SWEEP = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("sundering_sweep"), AttackShape.HORIZONTAL_ARC)
                    .swing(SwingPath.leftToRight(162.0f).withDynamics(SwingDynamics.FORCEFUL))
                    .range(5.6).innerRadius(0.8)
                    .verticalExtent(3.0)
                    .origin(0.0, 0.35, 1.1)
                    .damage(DivineConfig.damage("sundering_sweep", 15.0f)).knockback(1.0, 0.25)
                    // 25/10/19 (was 20/8/16).
                    .timing(25, 10, 19)
                    .advance(0.6)
                    .tracking(TrackingMode.MINIMAL, TrackingMode.LOCKED)
                    .animation(VisageOfWarEntity.ANIM_WIDE_CLEAVE)
                    .sounds(HEAVY_SWING)
                    .particles(AttackVisuals.sweepParticles(52, 3.4f))
                    .slash(AttackVisuals.heavyCrescent())
                    .build());

    /// The impact half of Judgment Fall — fired by the controller the moment she lands. The rise, the
    /// target lock and the dive are movement, and live in {@link VisageController}.
    public static final DirectionalAttack JUDGMENT_FALL_IMPACT = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("judgment_fall_impact"), AttackShape.RADIAL_IMPACT)
                    .swing(SwingPath.fixedSpan(360.0f))
                    .range(3.0).innerRadius(0.0)
                    .damage(DivineConfig.damage("judgment_fall_impact", 16.0f)).knockback(1.2, 0.45)
                    .origin(0.0, 0.0, 0.4)
                    .timing(0, 3, 12)
                    .tracking(TrackingMode.LOCKED, TrackingMode.LOCKED)
                    .animation(VisageOfWarEntity.ANIM_AERIAL_DESCEND)
                    .sounds(HEAVY_SWING)
                    .particles(AttackVisuals.impactParticles(60))
                    .slash(SlashProfile.NONE)
                    .hooks(AttackHooks.builder()
                            .onActiveStart(execution -> {
                                if (execution.attacker() instanceof VisageOfWarEntity visage) {
                                    visage.onJudgmentImpact();
                                }
                            })
                            .build())
                    .build());

    /// The passes of the Divine Pursuit transition. Same charge geometry as Valkyrie's Passage, but with
    /// no recovery — the transition drives them back to back.
    public static final DirectionalAttack TRANSITION_PASS = AttackRegistry.register(
            DirectionalAttack.builder(DivineEncounters.id("transition_pass"), AttackShape.CHARGE_PATH)
                    .swing(SwingPath.lane(0.0f))
                    .range(6.0).innerRadius(0.0)
                    .width(2.6).verticalExtent(3.0)
                    .origin(0.0, 0.0, 1.0)
                    .damage(DivineConfig.damage("transition_pass", 11.0f)).knockback(0.7, 0.35)
                    .timing(12, 14, 2)
                    .tracking(TrackingMode.LOCKED, TrackingMode.LOCKED)
                    .animation(VisageOfWarEntity.ANIM_CHARGE)
                    .sounds(RUSH)
                    .particles(AttackVisuals.chargeParticles(28))
                    .slash(AttackVisuals.chargeTrail())
                    .build());

    /// Touching this class forces its constants — and therefore their registration — to initialise.
    public static void bootstrap() {
        // Intentionally empty: class initialisation is the work.
    }
}
