package com.cleannrooster.divineencounters.encounter.presence;

import com.cleannrooster.divineencounters.encounter.anchor.AnchorRegistry;
import com.cleannrooster.divineencounters.encounter.perception.GloomProfile;
import com.cleannrooster.divineencounters.encounter.perception.StareMemory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Predicate;

/// Everything a {@link CandidateSource} needs to propose positions, and a
/// {@link CandidateResolver} needs to judge them.
///
/// Bundled into one object because the parameter list is otherwise long enough to invite mistakes,
/// and because sources and the resolver must agree on exactly the same view of the world.
///
/// @param level        the server level
/// @param dimensions   the manifesting entity's size, for fit checks
/// @param target       the player the encounter is centred on
/// @param participants every player eligible to observe
/// @param profile      current visibility rules
/// @param stare        rolling look-direction memory, for the "don't appear where they're staring" rule
/// @param anchors      authored arena positions; empty when the boss was summoned without an arena
/// @param arenaBounds  the fightable volume, or null when unbounded
/// @param passable     blocks the manifesting entity may occupy despite their collision shape
public record CandidateContext(
        ServerLevel level,
        EntityDimensions dimensions,
        Player target,
        List<? extends Player> participants,
        GloomProfile profile,
        StareMemory stare,
        AnchorRegistry anchors,
        @Nullable BoundingBox arenaBounds,
        Predicate<BlockState> passable
) {
    /// Nothing is passable — an entity with no special relationship to the world.
    public static final Predicate<BlockState> SOLID_IS_SOLID = state -> false;

    public CandidateContext(ServerLevel level, EntityDimensions dimensions, Player target,
                            List<? extends Player> participants, GloomProfile profile,
                            StareMemory stare, AnchorRegistry anchors,
                            @Nullable BoundingBox arenaBounds) {
        this(level, dimensions, target, participants, profile, stare, anchors, arenaBounds,
                SOLID_IS_SOLID);
    }

    /// Whether a position is free for the entity to occupy.
    ///
    /// This is the resolver's notion of "empty", and it is deliberately narrower than the notion of
    /// "passable during movement". A boss may be able to *travel* through a tree without being
    /// allowed to *materialise* inside one — the predicate supplied here should describe only the
    /// blocks it can share space with once it has stopped.
    public boolean isFree(net.minecraft.core.BlockPos pos) {
        var state = this.level.getBlockState(pos);
        if (this.passable.test(state)) {
            return true;
        }
        return state.getCollisionShape(this.level, pos).isEmpty();
    }

    /// Whether the entity's whole box fits, treating passable blocks as empty.
    public boolean fitsWithin(net.minecraft.world.phys.AABB box) {
        var min = net.minecraft.core.BlockPos.containing(box.minX, box.minY, box.minZ);
        var max = net.minecraft.core.BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        var cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (var x = min.getX(); x <= max.getX(); x++) {
            for (var y = min.getY(); y <= max.getY(); y++) {
                for (var z = min.getZ(); z <= max.getZ(); z++) {
                    cursor.set(x, y, z);
                    var state = this.level.getBlockState(cursor);
                    if (this.passable.test(state)) {
                        continue;
                    }
                    var shape = state.getCollisionShape(this.level, cursor);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    if (shape.move(x, y, z).bounds().intersects(box)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
    public Vec3 targetPosition() {
        return this.target.position();
    }

    /// The target's horizontal facing, which is what flank and rear angles are measured against.
    public Vec3 targetLook() {
        var look = this.target.getLookAngle();
        var flat = new Vec3(look.x, 0.0, look.z);
        return flat.lengthSqr() < 1.0e-6 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
    }

    public boolean withinArena(Vec3 position) {
        if (this.arenaBounds == null) {
            return true;
        }
        return this.arenaBounds.isInside(
                net.minecraft.core.BlockPos.containing(position.x, position.y, position.z));
    }

    public static CandidateContext of(ServerLevel level, LivingEntity entity, Player target,
                                      List<? extends Player> participants, GloomProfile profile,
                                      StareMemory stare, AnchorRegistry anchors,
                                      @Nullable BoundingBox arenaBounds) {
        return of(level, entity, target, participants, profile, stare, anchors, arenaBounds,
                SOLID_IS_SOLID);
    }

    public static CandidateContext of(ServerLevel level, LivingEntity entity, Player target,
                                      List<? extends Player> participants, GloomProfile profile,
                                      StareMemory stare, AnchorRegistry anchors,
                                      @Nullable BoundingBox arenaBounds,
                                      Predicate<BlockState> passable) {
        return new CandidateContext(level, entity.getDimensions(entity.getPose()), target,
                participants, profile, stare, anchors, arenaBounds, passable);
    }
}
