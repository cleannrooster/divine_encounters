package com.cleannrooster.divineencounters.omen;

import com.cleannrooster.divineencounters.encounter.EncounterManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/// Decides whether an arena may erupt where a player is standing.
///
/// This carries weight that worldgen used to carry for free. When arenas were pre-placed sites, the
/// terrain was flat and in-biome by construction. Now they appear wherever an omen-bearer happens
/// to be, so every one of those guarantees has to be re-established at runtime — against a world
/// the player may have altered arbitrarily.
///
/// Checks run cheapest-first, because this is evaluated on a timer for every armed player.
///
/// ### On "unmodified"
/// Minecraft does not record what a player built, so this cannot be answered exactly. What it can
/// do is look for the *evidence* of habitation — crafted blocks, light sources, workstations, beds,
/// villages — which reliably catches bases and settlements. It will not catch a hut made of dirt.
/// Given the consequence of a false positive is an arena erupting through somebody's wall, the
/// checks err strict: a refused valid site costs a short walk, a wrongly accepted one costs a base.
public final class SiteEligibility {
    /// How far apart arenas must be. Also stops a player re-triggering on the same spot.
    private static final double MIN_SEPARATION = 128.0;
    /// Vertical span sampled around the surface when looking for signs of habitation.
    private static final int SCAN_DEPTH = 4;
    /// Spacing of the habitation scan. Sampling every block over a large footprint is far more work
    /// than the answer justifies; a lattice catches anything base-sized.
    private static final int SCAN_STEP = 2;
    /// Radius, in blocks, searched for beds and village workstations.
    private static final int POI_RADIUS = 48;
    /// How far down a column may be walked looking for ground beneath vegetation. Comfortably
    /// clears a jungle canopy; bounded so a column of leaves cannot send it to bedrock.
    private static final int MAX_VEGETATION_DEPTH = 32;

    /// Why a site was refused. Returned rather than a bare boolean so a debug command can say what
    /// is wrong instead of just "no".
    public enum Result {
        OK,
        WRONG_BIOME,
        TOO_UNEVEN,
        OBSTRUCTED,
        INHABITED,
        TOO_CLOSE_TO_ANOTHER_ARENA;

        public boolean isOk() {
            return this == OK;
        }
    }

    private SiteEligibility() {
    }

    /// Full evaluation, cheapest checks first.
    public static Result evaluate(ServerLevel level, BlockPos centre, OmenType omen) {
        if (!level.getBiome(centre).is(omen.biomes())) {
            return Result.WRONG_BIOME;
        }
        if (EncounterManager.hasEncounterNear(level, centre, MIN_SEPARATION)) {
            return Result.TOO_CLOSE_TO_ANOTHER_ARENA;
        }
        var terrain = checkTerrain(level, centre, omen);
        if (!terrain.isOk()) {
            return terrain;
        }
        if (isInhabited(level, centre, omen)) {
            return Result.INHABITED;
        }
        return Result.OK;
    }

    /// Flat enough to fight in, dry, and solid.
    ///
    /// Relief is measured against the true **ground**, found by walking down past trees and
    /// vegetation. Using a raw heightmap here was wrong in a way that made the whole system look
    /// broken: `MOTION_BLOCKING_NO_LEAVES` excludes leaves but *includes logs*, so one oak trunk in
    /// the footprint read as six blocks of relief and vetoed the site. Since plains, savanna and
    /// meadow all have scattered trees, virtually every legal biome failed on its first tree.
    ///
    /// Relief is also judged between percentiles rather than absolute extremes, so a lone boulder
    /// or a one-block dip cannot veto an otherwise perfectly good clearing.
    private static Result checkTerrain(ServerLevel level, BlockPos centre, OmenType omen) {
        var radius = omen.footprint();
        var grounds = new IntArrayList();

        for (var dx = -radius; dx <= radius; dx += SCAN_STEP) {
            for (var dz = -radius; dz <= radius; dz += SCAN_STEP) {
                var x = centre.getX() + dx;
                var z = centre.getZ() + dz;
                var groundY = groundLevel(level, x, z);
                var groundPos = new BlockPos(x, groundY, z);
                var ground = level.getBlockState(groundPos);

                // Water and lava are as disqualifying as a cliff — an arena floor has to be standable.
                if (!ground.getFluidState().isEmpty()
                        || !level.getBlockState(groundPos.above()).getFluidState().isEmpty()) {
                    return Result.OBSTRUCTED;
                }
                if (ground.getCollisionShape(level, groundPos).isEmpty()) {
                    return Result.OBSTRUCTED;
                }
                grounds.add(groundY);
            }
        }
        if (grounds.isEmpty()) {
            return Result.OBSTRUCTED;
        }
        return reliefOf(grounds) > omen.maxRelief() ? Result.TOO_UNEVEN : Result.OK;
    }

    /// Spread between the 5th and 95th percentile of ground heights. Ignoring the extremes is what
    /// lets a natural clearing qualify despite the odd hummock.
    private static int reliefOf(IntArrayList grounds) {
        var sorted = grounds.toIntArray();
        java.util.Arrays.sort(sorted);
        var low = sorted[(int) (sorted.length * 0.05)];
        var high = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95))];
        return high - low;
    }

    /// The real ground under a column: start at the surface and step down through anything a tree
    /// or a meadow is made of.
    private static int groundLevel(ServerLevel level, int x, int z) {
        var y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        var floor = level.getMinBuildHeight();
        for (var steps = 0; steps < MAX_VEGETATION_DEPTH && y > floor; steps++) {
            var pos = new BlockPos(x, y, z);
            var state = level.getBlockState(pos);
            if (isGround(level, pos, state)) {
                return y;
            }
            y--;
        }
        return y;
    }

    /// Whether a block is terrain rather than something growing out of it.
    private static boolean isGround(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.FLOWERS) || state.is(BlockTags.REPLACEABLE)
                || state.is(BlockTags.SNOW) || state.is(BlockTags.WOOL)) {
            return false;
        }
        return !state.getCollisionShape(level, pos).isEmpty();
    }

    /// Evidence that somebody lives here.
    ///
    /// Two independent signals, because each misses what the other catches: block-level evidence
    /// finds a base with no villagers, and the POI search finds a village whose blocks are all
    /// natural-looking.
    private static boolean isInhabited(ServerLevel level, BlockPos centre, OmenType omen) {
        if (hasNearbyPointsOfInterest(level, centre)) {
            return true;
        }
        return hasBuiltBlocks(level, centre, omen.footprint());
    }

    private static boolean hasNearbyPointsOfInterest(ServerLevel level, BlockPos centre) {
        var pois = level.getPoiManager();
        // Beds and workstations are the strongest available signal of habitation, player or villager.
        var found = pois.getInRange(holder -> holder.is(PoiTypes.HOME)
                        || holder.is(PoiTypes.ARMORER) || holder.is(PoiTypes.FARMER)
                        || holder.is(PoiTypes.LIBRARIAN) || holder.is(PoiTypes.TOOLSMITH)
                        || holder.is(PoiTypes.WEAPONSMITH) || holder.is(PoiTypes.MASON),
                centre, POI_RADIUS,
                net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY);
        return found.findAny().isPresent();
    }

    /// Scan the footprint for blocks that do not occur naturally.
    ///
    /// A handful of tolerated hits absorbs the odd naturally-generated structure block — a jungle
    /// temple's mossy stone, a ruined portal — without letting an actual build through.
    private static boolean hasBuiltBlocks(ServerLevel level, BlockPos centre, int radius) {
        var suspicious = 0;
        for (var dx = -radius; dx <= radius; dx += SCAN_STEP) {
            for (var dz = -radius; dz <= radius; dz += SCAN_STEP) {
                var x = centre.getX() + dx;
                var z = centre.getZ() + dz;
                var surface = groundLevel(level, x, z) + 1;
                for (var dy = -1; dy < SCAN_DEPTH; dy++) {
                    var pos = new BlockPos(x, surface + dy, z);
                    if (looksBuilt(level.getBlockState(pos))) {
                        if (++suspicious >= 6) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /// Whether a block state is evidence of a builder rather than of the world generator.
    private static boolean looksBuilt(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        // A block entity in the open is almost always placed: chests, furnaces, signs, beds.
        if (state.hasBlockEntity()) {
            return true;
        }
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.STAIRS)
                || state.is(BlockTags.SLABS)
                || state.is(BlockTags.WALLS)
                || state.is(BlockTags.BEDS)
                || state.is(BlockTags.RAILS)
                || state.is(BlockTags.WOOL)
                || state.is(BlockTags.CANDLES)
                || state.is(BlockTags.IMPERMEABLE)
                || state.getLightEmission() > 0;
    }
}
