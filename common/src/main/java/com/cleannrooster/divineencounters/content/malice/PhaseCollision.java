package com.cleannrooster.divineencounters.content.malice;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/// Movement collision with the phaseable blocks removed.
///
/// ### Why it is built this way
///
/// `Entity#collide` is private, so there is no hook to filter what an entity collides with, and this
/// project carries no mixins. The collider list is therefore assembled here — minus anything
/// phaseable — and swept manually.
///
/// The tempting shortcut is `Entity#collideBoundingBox`, which is public, static, and takes the
/// shape list as a parameter. It does not work, and it fails *silently*: it delegates to
/// `collectColliders`, which calls `Level#getBlockCollisions` and appends every block collision to
/// whatever list it was handed. A filtered list passed to it is simply supplemented by the
/// unfiltered one, so the filtering has no effect at all and the entity collides with everything as
/// normal. {@link #sweep} exists because of that.
///
/// The result is fed back through `Entity#move` with `noPhysics` set for the duration of that one
/// call. That looks like a trick but is the conservative option: `move` does a great deal besides
/// collision — position, block-entity stepping, movement sounds, portal and fluid checks — and all
/// of it should keep happening. Only the collision step is replaced, by pre-resolving the vector and
/// telling `move` not to collide it a second time.
///
/// ### The fast path
///
/// {@link #collide} returns null when nothing phaseable is anywhere near the entity, and the caller
/// then takes the ordinary vanilla path untouched. The value of that is correctness rather than
/// speed: in terrain with no vegetation none of this code runs, so the phasing cannot introduce a
/// subtle difference in ordinary collision behaviour.
///
/// It is *not* a meaningful optimisation. Measured in a dark forest, roughly nine moves in ten take
/// the filtered path — the search box is a block wider than the entity in every direction, and in a
/// forest something leafy is almost always inside it. So the scan below should be assumed to run
/// every tick during an encounter. That is acceptable for one or two bosses and would not be for a
/// common mob: it walks ~100 positions doing a state lookup and a shape lookup each, where vanilla's
/// `BlockCollisions` cursor is a good deal cleverer about both.
public final class PhaseCollision {
    /// How far outside the swept box to look for colliders. One block is what vanilla uses.
    private static final double SEARCH_MARGIN = 1.0;

    private PhaseCollision() {
    }

    /// Resolve `movement` against everything except the blocks this entity may phase through.
    ///
    /// @param supernatural whether trunk traversal is currently unlocked
    /// @return the collided movement vector, or null if nothing phaseable is nearby and the caller
    ///         should simply use vanilla collision
    public static @Nullable Vec3 collide(Entity entity, Vec3 movement, boolean supernatural) {
        var level = entity.level();
        var box = entity.getBoundingBox();
        var swept = box.expandTowards(movement).inflate(SEARCH_MARGIN);

        var colliders = new ArrayList<VoxelShape>();
        var foundPhaseable = false;

        var min = BlockPos.containing(swept.minX, swept.minY, swept.minZ);
        var max = BlockPos.containing(swept.maxX, swept.maxY, swept.maxZ);
        var cursor = new BlockPos.MutableBlockPos();

        for (var x = min.getX(); x <= max.getX(); x++) {
            for (var y = min.getY(); y <= max.getY(); y++) {
                for (var z = min.getZ(); z <= max.getZ(); z++) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    var state = level.getBlockState(cursor);
                    var shape = state.getCollisionShape(level, cursor);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    if (MalicePhasing.canPhase(state, supernatural)) {
                        foundPhaseable = true;
                        continue;
                    }
                    colliders.add(shape.move(x, y, z));
                }
            }
        }

        if (!foundPhaseable) {
            // Nothing to ignore — let vanilla handle it exactly as it would for any other entity.
            return null;
        }

        // Entities still collide normally; only the world is being filtered.
        colliders.addAll(level.getEntityCollisions(entity, swept));

        var border = level.getWorldBorder();
        if (border.isInsideCloseToBorder(entity, swept)) {
            colliders.add(border.getCollisionShape());
        }

        return sweep(movement, box, colliders);
    }

    /// Resolve a movement vector against an explicit list of shapes.
    ///
    /// A faithful reimplementation of `Entity#collideWithShapes`, which is private. It would be
    /// preferable to call vanilla's, and the obvious route — `Entity#collideBoundingBox`, which is
    /// public — does not work: it delegates to `collectColliders`, which calls
    /// `Level#getBlockCollisions` and appends *every* block collision to whatever list it was given.
    /// Passing a filtered list to it therefore filters nothing, because the unfiltered set is added
    /// straight back on top. That is a silent no-op rather than an error, and it is exactly the bug
    /// this method exists to fix.
    ///
    /// The axis order below is vanilla's and matters: Y is resolved first, then the smaller of the
    /// two horizontal components before the larger. Getting it wrong produces corner cases where an
    /// entity slips through a diagonal gap it should not fit through.
    private static Vec3 sweep(Vec3 movement, AABB box, List<VoxelShape> shapes) {
        if (shapes.isEmpty()) {
            return movement;
        }
        var x = movement.x;
        var y = movement.y;
        var z = movement.z;

        if (y != 0.0) {
            y = Shapes.collide(Direction.Axis.Y, box, shapes, y);
            if (y != 0.0) {
                box = box.move(0.0, y, 0.0);
            }
        }

        var zFirst = Math.abs(x) < Math.abs(z);
        if (zFirst && z != 0.0) {
            z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
            if (z != 0.0) {
                box = box.move(0.0, 0.0, z);
            }
        }
        if (x != 0.0) {
            x = Shapes.collide(Direction.Axis.X, box, shapes, x);
            if (!zFirst && x != 0.0) {
                box = box.move(x, 0.0, 0.0);
            }
        }
        if (!zFirst && z != 0.0) {
            z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
        }
        return new Vec3(x, y, z);
    }

    /// Whether the entity's own box currently overlaps a block it can only pass through by
    /// supernatural means.
    ///
    /// Two uses, both of which matter. It keeps phasing switched on until the boss is clear of a
    /// trunk, so a state ending mid-traversal cannot strand it inside one — and it gates damage, so
    /// the boss can never strike a player from inside a tree.
    public static boolean embeddedInPhaseable(Entity entity) {
        return overlaps(entity, entity.getBoundingBox().deflate(EMBED_TOLERANCE));
    }

    /// Shrink the test box slightly: brushing the face of a trunk is not being inside it, and
    /// treating it as such would leave phasing latched on far longer than intended.
    private static final double EMBED_TOLERANCE = 0.08;

    private static boolean overlaps(Entity entity, AABB box) {
        var level = entity.level();
        var min = BlockPos.containing(box.minX, box.minY, box.minZ);
        var max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        var cursor = new BlockPos.MutableBlockPos();

        for (var x = min.getX(); x <= max.getX(); x++) {
            for (var y = min.getY(); y <= max.getY(); y++) {
                for (var z = min.getZ(); z <= max.getZ(); z++) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    var state = level.getBlockState(cursor);
                    if (!MalicePhasing.phasesWhenSupernatural(state)) {
                        continue;
                    }
                    if (!state.getCollisionShape(level, cursor).isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /// Sample one phaseable block the entity is currently intersecting, for effects. Null when it is
    /// not touching any.
    public static @Nullable BlockPos sampleIntersecting(Entity entity, boolean supernatural) {
        var level = entity.level();
        var box = entity.getBoundingBox().deflate(0.05);
        var min = BlockPos.containing(box.minX, box.minY, box.minZ);
        var max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        var cursor = new BlockPos.MutableBlockPos();

        for (var x = min.getX(); x <= max.getX(); x++) {
            for (var y = min.getY(); y <= max.getY(); y++) {
                for (var z = min.getZ(); z <= max.getZ(); z++) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    if (MalicePhasing.canPhase(level.getBlockState(cursor), supernatural)) {
                        return cursor.immutable();
                    }
                }
            }
        }
        return null;
    }
}
