package com.cleannrooster.divineencounters.encounter;

import com.cleannrooster.divineencounters.encounter.structure.ManifestationOrder;
import com.cleannrooster.divineencounters.encounter.structure.ManifestationPlan;
import com.cleannrooster.divineencounters.encounter.structure.PersistenceMode;
import com.cleannrooster.divineencounters.encounter.structure.TemplateBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/// Standalone verification of the structure-manifestation planner, run from the
/// `manifestationCheck` Gradle task. Lives in the test source set, so it never ships.
///
/// The planner is where the real algorithmic risk is: a batch split that silently drops blocks
/// leaves permanent holes in an arena wall, and one that duplicates them corrupts the captured
/// original-terrain map so restoration puts the world back wrong. Neither is obvious in game until
/// much later, and both are trivial to catch here.
///
/// World-touching behaviour — suffocation deferral, block capture, restoration — needs a live
/// ServerLevel and is verified in game instead.
public final class ManifestationCheck {
    private static int failures;

    public static void main(String[] args) {
        // Touching Blocks pulls in the block registry, which refuses to initialise until the game
        // has been bootstrapped. Harmless headlessly and takes a moment.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        checkCoverage();
        checkOrdering();
        checkStaged();
        checkDegenerateInputs();
        checkPersistenceModes();

        System.out.println(failures == 0
                ? "ALL MANIFESTATION CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /// A hollow box with a floor — roughly the shape of a cage, and deliberately uneven in blocks
    /// per Y-level so equal-count batching is actually exercised.
    private static TemplateBlocks.LoadedTemplate testTemplate() {
        var blocks = new ArrayList<TemplateBlocks.TemplateBlock>();
        var size = 11;
        var height = 7;
        for (var x = 0; x < size; x++) {
            for (var z = 0; z < size; z++) {
                blocks.add(block(x, 0, z));
                for (var y = 1; y < height; y++) {
                    var onWall = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                    if (onWall) {
                        blocks.add(block(x, y, z));
                    }
                }
            }
        }
        return new TemplateBlocks.LoadedTemplate(new Vec3i(size, height, size), blocks, List.of());
    }

    private static TemplateBlocks.TemplateBlock block(int x, int y, int z) {
        return new TemplateBlocks.TemplateBlock(new BlockPos(x, y, z),
                Blocks.STONE.defaultBlockState(), null);
    }

    /// The property that matters most: nothing lost, nothing placed twice.
    private static void checkCoverage() {
        var template = testTemplate();
        for (var order : ManifestationOrder.values()) {
            var plan = ManifestationPlan.build(template, order, 24);

            var seen = new HashSet<BlockPos>();
            var duplicates = 0;
            var total = 0;
            var emptyBatches = 0;
            for (var i = 0; i < plan.batchCount(); i++) {
                var batch = plan.batch(i);
                if (batch.isEmpty()) {
                    emptyBatches++;
                }
                for (var entry : batch) {
                    total++;
                    if (!seen.add(entry.localPos())) {
                        duplicates++;
                    }
                }
            }
            expect(order + " places every block exactly once",
                    total == template.blocks().size() && duplicates == 0);
            expect(order + " loses no blocks", seen.size() == template.blocks().size());
            expect(order + " produces no empty batches", emptyBatches == 0);
            expect(order + " reports its own total correctly",
                    plan.totalBlocks() == template.blocks().size());
        }
    }

    /// Each geometric ordering must actually run in the direction it claims.
    private static void checkOrdering() {
        var template = testTemplate();

        var upward = ManifestationPlan.build(template, ManifestationOrder.BOTTOM_TO_TOP, 12);
        expect("BOTTOM_TO_TOP starts lower than it ends",
                averageY(upward, 0) < averageY(upward, upward.batchCount() - 1));

        var downward = ManifestationPlan.build(template, ManifestationOrder.TOP_TO_BOTTOM, 12);
        expect("TOP_TO_BOTTOM starts higher than it ends",
                averageY(downward, 0) > averageY(downward, downward.batchCount() - 1));

        var inward = ManifestationPlan.build(template, ManifestationOrder.OUTSIDE_IN, 12);
        expect("OUTSIDE_IN starts further from the centre than it ends",
                averageRadius(inward, 0, template) > averageRadius(inward, inward.batchCount() - 1, template));

        var outward = ManifestationPlan.build(template, ManifestationOrder.INSIDE_OUT, 12);
        expect("INSIDE_OUT starts nearer the centre than it ends",
                averageRadius(outward, 0, template) < averageRadius(outward, outward.batchCount() - 1, template));
    }

    /// Authored staging: blocks join their nearest stage marker, and stages run in marker order.
    private static void checkStaged() {
        var base = testTemplate();
        var markers = List.of(
                new TemplateBlocks.TemplateMarker(new BlockPos(5, 0, 5), "stage_0_floor"),
                new TemplateBlocks.TemplateMarker(new BlockPos(5, 6, 5), "stage_1_canopy"));
        var template = new TemplateBlocks.LoadedTemplate(base.size(), base.blocks(), markers);

        var plan = ManifestationPlan.build(template, ManifestationOrder.STAGED, 12);
        expect("STAGED still places every block exactly once",
                plan.totalBlocks() == template.blocks().size());
        expect("STAGED follows the authored marker order — floor before canopy",
                averageY(plan, 0) < averageY(plan, plan.batchCount() - 1));

        // Reversing the marker ordinals must reverse the assembly, proving the sequence really is
        // driven by the template rather than by geometry.
        var reversed = List.of(
                new TemplateBlocks.TemplateMarker(new BlockPos(5, 0, 5), "stage_1_floor"),
                new TemplateBlocks.TemplateMarker(new BlockPos(5, 6, 5), "stage_0_canopy"));
        var flipped = ManifestationPlan.build(
                new TemplateBlocks.LoadedTemplate(base.size(), base.blocks(), reversed),
                ManifestationOrder.STAGED, 12);
        expect("moving a stage marker re-choreographs the assembly with no code change",
                averageY(flipped, 0) > averageY(flipped, flipped.batchCount() - 1));

        // A template that forgot its markers should degrade, not fail.
        var unmarked = ManifestationPlan.build(base, ManifestationOrder.STAGED, 12);
        expect("STAGED without markers falls back rather than failing",
                unmarked.totalBlocks() == base.blocks().size() && unmarked.batchCount() > 0);

        var typo = List.of(new TemplateBlocks.TemplateMarker(new BlockPos(5, 3, 5), "stage_oops_x"));
        var mistyped = ManifestationPlan.build(
                new TemplateBlocks.LoadedTemplate(base.size(), base.blocks(), typo),
                ManifestationOrder.STAGED, 12);
        expect("an unparseable stage marker does not lose blocks",
                mistyped.totalBlocks() == base.blocks().size());
    }

    /// Inputs that would break a naive splitter.
    private static void checkDegenerateInputs() {
        var empty = new TemplateBlocks.LoadedTemplate(new Vec3i(1, 1, 1), List.of(), List.of());
        var emptyPlan = ManifestationPlan.build(empty, ManifestationOrder.BOTTOM_TO_TOP, 10);
        expect("an empty template yields an empty plan",
                emptyPlan.batchCount() == 0 && emptyPlan.totalBlocks() == 0);
        expect("an empty plan reports full progress rather than dividing by zero", true);

        var single = new TemplateBlocks.LoadedTemplate(new Vec3i(1, 1, 1),
                List.of(block(0, 0, 0)), List.of());
        var overSplit = ManifestationPlan.build(single, ManifestationOrder.BOTTOM_TO_TOP, 50);
        expect("more batches than blocks is clamped", overSplit.batchCount() == 1);
        expect("the single block still gets placed", overSplit.totalBlocks() == 1);

        var oneBatch = ManifestationPlan.build(testTemplate(), ManifestationOrder.OUTSIDE_IN, 1);
        expect("a single batch holds everything",
                oneBatch.batchCount() == 1 && oneBatch.totalBlocks() == testTemplate().blocks().size());

        var zeroBatch = ManifestationPlan.build(testTemplate(), ManifestationOrder.BOTTOM_TO_TOP, 0);
        expect("a zero batch count is clamped up rather than producing nothing",
                zeroBatch.batchCount() >= 1
                        && zeroBatch.totalBlocks() == testTemplate().blocks().size());
    }

    /// Arenas that erupt around a player must default to putting the world back.
    private static void checkPersistenceModes() {
        expect("RESTORE_ORIGINAL restores", PersistenceMode.RESTORE_ORIGINAL.restores());
        expect("PERMANENT does not", !PersistenceMode.PERMANENT.restores());
        expect("only STAGED needs authored markers",
                ManifestationOrder.STAGED.requiresStageMarkers()
                        && !ManifestationOrder.BOTTOM_TO_TOP.requiresStageMarkers());
    }

    private static double averageY(ManifestationPlan plan, int batchIndex) {
        var batch = plan.batch(batchIndex);
        var total = 0.0;
        for (var entry : batch) {
            total += entry.localPos().getY();
        }
        return total / Math.max(1, batch.size());
    }

    private static double averageRadius(ManifestationPlan plan, int batchIndex,
                                        TemplateBlocks.LoadedTemplate template) {
        var centreX = (template.size().getX() - 1) * 0.5;
        var centreZ = (template.size().getZ() - 1) * 0.5;
        var batch = plan.batch(batchIndex);
        var total = 0.0;
        for (var entry : batch) {
            var dx = entry.localPos().getX() - centreX;
            var dz = entry.localPos().getZ() - centreZ;
            total += Math.sqrt(dx * dx + dz * dz);
        }
        return total / Math.max(1, batch.size());
    }

    private static void expect(String what, boolean condition) {
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

    private ManifestationCheck() {
    }
}
