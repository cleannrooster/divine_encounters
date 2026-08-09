package com.cleannrooster.divineencounters.encounter.anchor;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/// One authored position in an encounter arena, resolved from a structure template's data markers
/// into world space.
///
/// @param id       the marker's metadata string, e.g. `perch_upper_left` — stable across placements
/// @param kind     what it is for
/// @param position world position an entity would occupy here (feet)
/// @param facing   yaw in degrees pointing back toward the arena centre, so anything that occupies
///                 the anchor is already looking the right way
/// @param height   blocks above the arena floor; 0 for ground-level anchors
public record AnchorPoint(String id, AnchorKind kind, Vec3 position, float facing, double height) {
    /// Build an anchor from a resolved marker position, orienting it toward the arena centre.
    public static AnchorPoint of(String id, AnchorKind kind, BlockPos pos, Vec3 arenaCentre,
                                 double floorY) {
        var position = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        var toCentre = arenaCentre.subtract(position);
        var facing = toCentre.horizontalDistanceSqr() < 1.0e-6
                ? 0.0f
                : (float) (Mth.atan2(toCentre.z, toCentre.x) * (180.0 / Math.PI)) - 90.0f;
        return new AnchorPoint(id, kind, position, Mth.wrapDegrees(facing),
                Math.max(0.0, position.y - floorY));
    }

    public boolean isElevated() {
        return this.height >= 1.5;
    }
}
