package com.cleannrooster.divineencounters.encounter.perception;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/// Answers one question, with no state of its own: **is this player meaningfully looking at that
/// entity right now?**
///
/// "Meaningfully" is doing real work. A boss standing behind the player is not observed even
/// though the server could trivially say it is in range; a boss on the far edge of the screen in
/// deep darkness is not observed either. The judgement combines line of sight, angular distance
/// from the look vector, and range, all shaped by the current {@link GloomProfile}.
///
/// Everything here runs from the player's *server-side* yaw and pitch, which the client already
/// reports as part of normal movement. That is deliberate: observation gates a boss's strongest
/// mechanic, so it must never depend on data a client could lie about. No observation packet
/// exists, and none should.
///
/// The functions are pure so they can be unit-checked headlessly and reused by the client renderer
/// for its own fade, which is computed from the true camera rather than this approximation.
public final class ObservationCheck {
    private ObservationCheck() {
    }

    /// Whether `player` currently observes `target`.
    ///
    /// `previouslyObserved` selects which side of the hysteresis band applies — pass the last
    /// answer for this same player/target pair. Without it, a player holding the target right at
    /// the edge of vision would flip the state every tick.
    public static boolean isObserving(Player player, Entity target, GloomProfile profile,
                                      boolean previouslyObserved) {
        return isObserving(player, target, profile, previouslyObserved, NOTHING_IS_TRANSPARENT);
    }

    /// Nothing blocks sight except solid geometry — ordinary behaviour, and the default.
    public static final java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState>
            NOTHING_IS_TRANSPARENT = state -> false;

    /// As above, but blocks matching `seeThrough` do not break line of sight.
    ///
    /// Supplied by bosses that have a reason not to be hidden by a particular kind of block — see
    /// {@link LineOfSight} for why leaves are the motivating case.
    public static boolean isObserving(Player player, Entity target, GloomProfile profile,
                                      boolean previouslyObserved,
                                      java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> seeThrough) {
        if (!canParticipate(player)) {
            return false;
        }
        var eye = player.getEyePosition();
        var toTarget = target.getBoundingBox().getCenter().subtract(eye);
        var distance = toTarget.length();
        if (distance > profile.maxDistance() || distance < 1.0e-4) {
            // Standing inside the target counts as observing it: there is no meaningful angle, and
            // "it vanished while touching me" would read as a bug rather than a mechanic.
            return distance <= profile.maxDistance();
        }
        var angle = viewAngle(player, toTarget.scale(1.0 / distance));
        if (!withinObservationCone(angle, distance, profile, previouslyObserved)) {
            return false;
        }
        if (!profile.requireLineOfSight()) {
            return true;
        }
        return LineOfSight.clear(player.level(), eye,
                target.getBoundingBox().getCenter(), player, seeThrough);
    }

    /// The angular/range half of the judgement, split out from the entity plumbing so the
    /// hysteresis rule can be verified headlessly — it is the part most likely to be broken by a
    /// later retune, and the part whose failure (a state flickering every tick) is hardest to
    /// diagnose from in-game symptoms.
    ///
    /// Wider tolerance to *keep* observing than to start: that gap is the hysteresis band.
    public static boolean withinObservationCone(float angleDegrees, double distance,
                                                GloomProfile profile, boolean previouslyObserved) {
        if (distance > profile.maxDistance()) {
            return false;
        }
        var threshold = previouslyObserved ? profile.releaseAngle() : profile.focusAngle();
        return angleDegrees <= threshold;
    }

    /// Angle in degrees between the player's look direction and an already-normalised direction.
    public static float viewAngle(Player player, Vec3 normalisedDirection) {
        var look = player.getLookAngle();
        var dot = Mth.clamp(look.dot(normalisedDirection), -1.0, 1.0);
        return (float) Math.toDegrees(Math.acos(dot));
    }

    /// Angle in degrees between the player's look direction and a world position.
    public static float viewAngleTo(Player player, Vec3 position) {
        var toPosition = position.subtract(player.getEyePosition());
        if (toPosition.lengthSqr() < 1.0e-8) {
            return 0.0f;
        }
        return viewAngle(player, toPosition.normalize());
    }

    /// How strongly a target at this angle and distance registers, 0 (unseen) to 1 (dead centre).
    ///
    /// Shared shaping so the server's judgement and the client's fade agree in character even
    /// though they are fed different inputs — the server its approximation, the client the real
    /// camera. Presentation only; nothing authoritative reads this.
    public static float observationStrength(float angleDegrees, double distance, GloomProfile profile) {
        if (distance > profile.maxDistance()) {
            return 0.0f;
        }
        if (angleDegrees >= profile.releaseAngle()) {
            return 0.0f;
        }
        // Full strength inside the focus cone, tapering to nothing at the release angle.
        if (angleDegrees <= profile.focusAngle()) {
            return 1.0f;
        }
        var span = profile.releaseAngle() - profile.focusAngle();
        var fade = 1.0f - (angleDegrees - profile.focusAngle()) / span;
        return Mth.clamp((float) Math.pow(fade, profile.renderFalloff()), 0.0f, 1.0f);
    }

    /// Whether a player is eligible to constrain a boss at all. Spectators and the dead do not
    /// count as witnesses — otherwise a downed player would keep pinning the boss in place.
    public static boolean canParticipate(Player player) {
        return player.isAlive() && !player.isSpectator();
    }
}
