package com.cleannrooster.divineencounters.encounter.structure;

import com.cleannrooster.divineencounters.encounter.structure.TemplateBlocks.LoadedTemplate;
import com.cleannrooster.divineencounters.encounter.structure.TemplateBlocks.TemplateBlock;
import com.cleannrooster.divineencounters.encounter.structure.TemplateBlocks.TemplateMarker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// A template's blocks, sorted into the batches they will be placed in.
///
/// Computed once, up front, so ticking a manifestation is just "place batch N" — no sorting or
/// searching happens while the structure is going up, and the plan can be replayed backwards for
/// retraction without recomputing anything.
///
/// Batches are equal in *block count*, not in geometric extent. That matters: a tower is wide at
/// the base and narrow at the top, so equal Y-slices would take wildly different times to place and
/// the assembly would visibly stutter. Equal counts give even pacing, and because the blocks are
/// sorted by the ordering key first, the sequence is still correct.
public final class ManifestationPlan {
    private final List<List<TemplateBlock>> batches;
    private final Vec3i size;

    private ManifestationPlan(List<List<TemplateBlock>> batches, Vec3i size) {
        this.batches = batches;
        this.size = size;
    }

    public int batchCount() {
        return this.batches.size();
    }

    public List<TemplateBlock> batch(int index) {
        return this.batches.get(index);
    }

    public Vec3i size() {
        return this.size;
    }

    public int totalBlocks() {
        var total = 0;
        for (var batch : this.batches) {
            total += batch.size();
        }
        return total;
    }

    /// Build a plan.
    ///
    /// @param batchCount how many steps the assembly takes; the engine spends one tick interval per
    ///                   batch, so this plus the tick interval is what sets the total duration
    public static ManifestationPlan build(LoadedTemplate template, ManifestationOrder order,
                                          int batchCount) {
        var blocks = new ArrayList<>(template.blocks());
        if (blocks.isEmpty()) {
            return new ManifestationPlan(List.of(), template.size());
        }
        var effective = Math.max(1, Math.min(batchCount, blocks.size()));

        if (order == ManifestationOrder.STAGED) {
            var staged = buildStaged(template, blocks, effective);
            if (staged != null) {
                return new ManifestationPlan(staged, template.size());
            }
            // No stage markers authored: fall back to something sensible rather than failing. A
            // template that forgot its markers should still build, just not in the authored order.
            order = ManifestationOrder.BOTTOM_TO_TOP;
        }

        blocks.sort(comparatorFor(order, template.size()));
        return new ManifestationPlan(split(blocks, effective), template.size());
    }

    /// Geometry-derived orderings.
    private static Comparator<TemplateBlock> comparatorFor(ManifestationOrder order, Vec3i size) {
        var centreX = (size.getX() - 1) * 0.5;
        var centreZ = (size.getZ() - 1) * 0.5;
        return switch (order) {
            case BOTTOM_TO_TOP -> Comparator.comparingInt(block -> block.localPos().getY());
            case TOP_TO_BOTTOM -> Comparator.<TemplateBlock>comparingInt(
                    block -> block.localPos().getY()).reversed();
            // Horizontal distance only: a cage should close in from the walls, and including height
            // would make the roof arrive interleaved with the perimeter instead of last.
            case OUTSIDE_IN -> Comparator.comparingDouble(
                    block -> -horizontalDistanceSqr(block.localPos(), centreX, centreZ));
            case INSIDE_OUT -> Comparator.comparingDouble(
                    block -> horizontalDistanceSqr(block.localPos(), centreX, centreZ));
            case STAGED -> Comparator.comparingInt(block -> block.localPos().getY());
        };
    }

    private static double horizontalDistanceSqr(BlockPos pos, double centreX, double centreZ) {
        var dx = pos.getX() - centreX;
        var dz = pos.getZ() - centreZ;
        return dx * dx + dz * dz;
    }

    /// Authored ordering: every block joins the stage whose marker is nearest to it.
    ///
    /// Nearest-marker assignment rather than explicit regions is a deliberate authoring choice —
    /// dropping one marker into each part of the structure is enough to choreograph the whole
    /// assembly, and moving it re-choreographs that region with no code change.
    ///
    /// Returns null when the template has no stage markers.
    private static List<List<TemplateBlock>> buildStaged(LoadedTemplate template,
                                                         List<TemplateBlock> blocks, int batchCount) {
        var markers = template.markersMatching("stage_");
        if (markers.isEmpty()) {
            return null;
        }
        markers.sort(Comparator.comparingInt(ManifestationPlan::stageIndexOf));

        var buckets = new ArrayList<List<TemplateBlock>>(markers.size());
        for (var i = 0; i < markers.size(); i++) {
            buckets.add(new ArrayList<>());
        }
        for (var block : blocks) {
            buckets.get(nearestMarker(markers, block.localPos())).add(block);
        }

        // Within a stage, still build upward — it looks like growth rather than scatter.
        var batches = new ArrayList<List<TemplateBlock>>();
        var perStage = Math.max(1, batchCount / Math.max(1, markers.size()));
        for (var bucket : buckets) {
            if (bucket.isEmpty()) {
                continue;
            }
            bucket.sort(Comparator.comparingInt(block -> block.localPos().getY()));
            batches.addAll(split(bucket, Math.min(perStage, bucket.size())));
        }
        return batches;
    }

    /// Parse the ordinal out of a `stage_<n>_<name>` marker. Unparseable markers sort last rather
    /// than throwing — a typo in a template should degrade, not crash a world.
    private static int stageIndexOf(TemplateMarker marker) {
        var parts = marker.metadata().split("_");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return Integer.MAX_VALUE;
    }

    private static int nearestMarker(List<TemplateMarker> markers, BlockPos pos) {
        var best = 0;
        var bestDistance = Double.MAX_VALUE;
        for (var i = 0; i < markers.size(); i++) {
            var distance = markers.get(i).localPos().distSqr(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /// Split an ordered list into `count` batches of near-equal size, preserving order.
    private static List<List<TemplateBlock>> split(List<TemplateBlock> ordered, int count) {
        var batches = new ArrayList<List<TemplateBlock>>(count);
        var total = ordered.size();
        var cursor = 0;
        for (var i = 0; i < count; i++) {
            // Distribute the remainder across the leading batches so none ends up empty.
            var remaining = count - i;
            var take = (total - cursor + remaining - 1) / remaining;
            if (take <= 0) {
                break;
            }
            batches.add(List.copyOf(ordered.subList(cursor, cursor + take)));
            cursor += take;
        }
        return batches;
    }
}
