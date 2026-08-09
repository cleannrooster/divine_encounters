package com.cleannrooster.divineencounters.encounter.perception;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/// Line of sight that can be told to ignore certain blocks.
///
/// ### Why this is not just `Entity#hasLineOfSight`
///
/// Vanilla's sight test clips against `ClipContext.Block.COLLIDER`, and leaves have a full collision
/// shape. So a boss that drifts into a canopy is, as far as the observation system is concerned,
/// gone — even while the player is looking straight at it through foliage they can seethrough.
///
/// For the Visage of Malice that is not a cosmetic problem. Losing observation is what earns it the
/// right to dissolve, so leaves would hand it a free dissolve every time it crossed a treeline in
/// full view. The player's counterplay is to keep their eyes on it, and the mechanic has to agree
/// with what their eyes are actually doing.
///
/// Obscuring vision is still fine and still happens — foliage genuinely makes it harder to see, and
/// the renderer's peripheral fade is unaffected by any of this. What is removed is only the binary
/// claim that a leaf between you and it means you cannot see it at all.
public final class LineOfSight {
    /// Maximum transparent blocks a single ray will pass through.
    ///
    /// Sixteen blocks of continuous foliage does not occur naturally; the cap exists so a malformed
    /// predicate cannot turn this into an unbounded loop. Hitting it reports "blocked", which is the
    /// safe direction — the failure mode of guessing "visible" is a boss seen through a wall.
    private static final int MAX_TRANSPARENT = 32;

    /// How far the ray advances past each transparent hit. Less than a block, so nothing behind the
    /// foliage can be stepped over.
    private static final double STEP = 0.5;

    private LineOfSight() {
    }

    /// Whether `from` can see `to`, treating blocks matching `seeThrough` as if they were not there.
    ///
    /// @param viewer     entity the clip is performed on behalf of, or null
    /// @param seeThrough blocks that do not break sight; pass a predicate that is always false for
    ///                   ordinary vanilla behaviour
    public static boolean clear(Level level, Vec3 from, Vec3 to, @Nullable Entity viewer,
                                Predicate<BlockState> seeThrough) {
        var direction = to.subtract(from);
        var length = direction.length();
        if (length < 1.0e-4) {
            return true;
        }
        var step = direction.scale(STEP / length);

        var origin = from;

        for (var attempt = 0; attempt < MAX_TRANSPARENT; attempt++) {
            var hit = level.clip(new ClipContext(origin, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
            if (!seeThrough.test(level.getBlockState(hit.getBlockPos()))) {
                return false;
            }
            // Advance past it. Always from the hit location plus a fixed step, so progress is
            // guaranteed even when the clip starts inside a block and reports that same block back —
            // which is what happens on the tick after a ray has entered foliage.
            origin = hit.getLocation().add(step);
            if (origin.distanceToSqr(from) > length * length) {
                // Stepped past the target: nothing solid was in the way.
                return true;
            }
        }
        return false;
    }
}
