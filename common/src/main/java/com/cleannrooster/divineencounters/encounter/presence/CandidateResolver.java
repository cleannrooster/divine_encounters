package com.cleannrooster.divineencounters.encounter.presence;

import com.cleannrooster.divineencounters.encounter.perception.ObservationCheck;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/// Turns raw proposed positions into judged {@link ManifestationCandidate}s, and picks one when
/// something finally forces the entity to become definite.
///
/// Building and selecting are deliberately separate calls. The set is built and refreshed
/// continuously while unresolved; selection happens only at a resolution event. That ordering is
/// what makes the indeterminacy real rather than a pre-chosen teleport wearing a disguise — until
/// something asks, there genuinely is no answer.
public final class CandidateResolver {
    /// Cone within which a player counts as "staring at" a spot, and how long they must hold it.
    /// Tighter than the observation cone: this is about a spot they are actually attending to.
    private static final float STARE_CONE = 28.0f;
    private static final int STARE_TICKS = 14;

    /// Vertical span searched for solid footing beneath a proposed position.
    private static final int GROUND_SEARCH = 4;

    private final List<CandidateSource> sources;

    public CandidateResolver(CandidateSource... sources) {
        this.sources = List.of(sources);
    }

    /// Build the current candidate set: collect from every source, then judge each one.
    public List<ManifestationCandidate> build(CandidateContext context) {
        var raw = new ArrayList<Vec3>();
        for (var source : this.sources) {
            source.collect(context, raw);
        }
        var judged = new ArrayList<ManifestationCandidate>(raw.size());
        for (var position : raw) {
            var candidate = judge(context, position);
            if (candidate != null) {
                judged.add(candidate);
            }
        }
        return judged;
    }

    /// Judge one position against the live world. Returns null only for positions so far outside
    /// the arena that they are not worth keeping; everything else is retained with its validity
    /// recorded, so the set can be re-checked cheaply as the fight moves.
    private @Nullable ManifestationCandidate judge(CandidateContext context, Vec3 position) {
        if (!context.withinArena(position)) {
            return null;
        }
        var grounded = snapToGround(context, position);
        var target = context.target();
        var toTarget = target.position().subtract(grounded);
        var distance = toTarget.horizontalDistance();

        var facing = distance < 1.0e-4
                ? 0.0f
                : Mth.wrapDegrees((float) (Mth.atan2(toTarget.z, toTarget.x) * (180.0 / Math.PI)) - 90.0f);

        // Angle measured from the *target's* facing: 0 is in front of them, 180 directly behind.
        var viewAngle = angleFromTargetView(context, grounded);
        var valid = fits(context, grounded);
        var visible = visibleToAnyone(context, grounded);
        var staredAt = context.stare().anyoneStaringAt(eyeLevel(context, grounded), STARE_CONE, STARE_TICKS);
        var elevated = grounded.y - target.position().y >= 1.5;

        return new ManifestationCandidate(grounded, facing, distance, viewAngle, visible, staredAt,
                valid, elevated, eyeLevel(context, grounded));
    }

    /// Refresh only the live judgements on an existing set — cheaper than rebuilding, and used
    /// every tick while unresolved so the pool never goes stale as players move and look around.
    public List<ManifestationCandidate> refresh(CandidateContext context,
                                                List<ManifestationCandidate> existing) {
        var refreshed = new ArrayList<ManifestationCandidate>(existing.size());
        for (var candidate : existing) {
            refreshed.add(candidate.withLiveState(
                    visibleToAnyone(context, candidate.position()),
                    context.stare().anyoneStaringAt(candidate.cueOrigin(), STARE_CONE, STARE_TICKS),
                    fits(context, candidate.position())));
        }
        return refreshed;
    }

    /// Choose the best candidate for a requested kind, or null when nothing qualifies.
    ///
    /// Returning null is a normal outcome, not a failure: it means "you cannot ambush from behind
    /// right now", and the caller is expected to pick a different action rather than force one.
    public @Nullable ManifestationCandidate select(List<ManifestationCandidate> candidates,
                                                   ManifestKind kind, RandomSource random) {
        var preferred = (kind.minDistance() + kind.maxDistance()) * 0.5;
        ManifestationCandidate best = null;
        var bestScore = Double.NEGATIVE_INFINITY;

        for (var candidate : candidates) {
            if (!candidate.satisfies(kind)) {
                continue;
            }
            // A little noise so a repeated ambush doesn't always arrive from the identical spot.
            var score = candidate.score(kind, preferred) + random.nextDouble() * 0.12;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /// Whether any candidate could satisfy a kind — lets the AI check an action is possible before
    /// committing to it.
    public boolean canSatisfy(List<ManifestationCandidate> candidates, ManifestKind kind) {
        for (var candidate : candidates) {
            if (candidate.satisfies(kind)) {
                return true;
            }
        }
        return false;
    }

    // --- world queries ---------------------------------------------------------------------------

    /// Drop a position onto the nearest solid footing within a short vertical span. Anchors that
    /// are already elevated keep their height — this only rescues ring samples that landed inside
    /// a slope or a block.
    private static Vec3 snapToGround(CandidateContext context, Vec3 position) {
        var level = context.level();
        var origin = BlockPos.containing(position.x, position.y, position.z);
        for (var offset = 0; offset <= GROUND_SEARCH; offset++) {
            var probe = origin.below(offset);
            if (isFooting(context, probe.below()) && isClear(context, probe)) {
                return new Vec3(position.x, probe.getY(), position.z);
            }
        }
        return position;
    }

    /// Solid enough to stand on.
    ///
    /// Passable blocks explicitly do not count. For a boss that treats leaves as empty air, a canopy
    /// is not a floor — without this, ring samples in a forest would snap onto the treetops and the
    /// boss would manifest above the fight.
    private static boolean isFooting(CandidateContext context, BlockPos pos) {
        return !context.isFree(pos);
    }

    private static boolean isClear(CandidateContext context, BlockPos pos) {
        return context.isFree(pos);
    }

    /// Whether the entity physically fits here — clear of blocks and of other entities.
    ///
    /// "Clear of blocks" is judged through the context's passability predicate rather than by
    /// `noCollision`, so an entity that treats some blocks as empty can resolve inside them. What it
    /// cannot do is resolve inside anything the predicate does not name: travelling through a trunk
    /// and materialising inside one are separate permissions, and only the first is granted.
    private static boolean fits(CandidateContext context, Vec3 position) {
        var box = boxAt(context, position);
        if (!context.fitsWithin(box)) {
            return false;
        }
        // Manifesting inside a player would be both unfair and physically absurd.
        return context.level().getEntities((net.minecraft.world.entity.Entity) null, box,
                entity -> entity instanceof net.minecraft.world.entity.LivingEntity).isEmpty();
    }

    private static AABB boxAt(CandidateContext context, Vec3 position) {
        var dimensions = context.dimensions();
        return dimensions.makeBoundingBox(position);
    }

    private static Vec3 eyeLevel(CandidateContext context, Vec3 position) {
        return position.add(0.0, context.dimensions().height() * 0.65, 0.0);
    }

    private static boolean visibleToAnyone(CandidateContext context, Vec3 position) {
        var probe = eyeLevel(context, position);
        for (var player : context.participants()) {
            if (!ObservationCheck.canParticipate(player)) {
                continue;
            }
            var distance = player.getEyePosition().distanceTo(probe);
            var angle = ObservationCheck.viewAngleTo(player, probe);
            if (!ObservationCheck.withinObservationCone(angle, distance, context.profile(), true)) {
                continue;
            }
            // Same sight rule the observation check uses. If foliage does not conceal the boss
            // once it is there, it must not conceal the spot beforehand either — otherwise it would
            // happily manifest behind a leaf the player can see straight through, which is the
            // visible teleport the whole candidate system exists to prevent.
            if (com.cleannrooster.divineencounters.encounter.perception.LineOfSight.clear(
                    context.level(), player.getEyePosition(), probe, player, context.passable())) {
                return true;
            }
        }
        return false;
    }

    /// Angle between the target's facing and the direction to a position, 0-180.
    private static float angleFromTargetView(CandidateContext context, Vec3 position) {
        var look = context.targetLook();
        var toPosition = position.subtract(context.targetPosition());
        var flat = new Vec3(toPosition.x, 0.0, toPosition.z);
        if (flat.lengthSqr() < 1.0e-6) {
            return 0.0f;
        }
        var dot = Mth.clamp(look.dot(flat.normalize()), -1.0, 1.0);
        return (float) Math.toDegrees(Math.acos(dot));
    }
}
