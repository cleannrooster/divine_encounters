package com.cleannrooster.divineencounters.content.malice;

import com.cleannrooster.divineencounters.DivineEncounters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/// Which blocks the forest is allowed to stop the Visage of Malice with.
///
/// The thematic rule this implements: **the forest can hide Malice, but it cannot contain Malice.**
/// Leaves are not cover. A trunk blocks your view of it and nothing else. A wall you built is still
/// a wall.
///
/// ### Three tags, and why there are three
///
/// - `malice_phase_always` — leaves and light vegetation. Passable in every state, resolved or not.
///   These are the blocks that should never cost the boss a single tick of hesitation.
/// - `malice_phase_supernatural` — natural trunks and heavy vegetation. Passable only while it is
///   doing something already supernatural; see {@code MaliceState#allowsTrunkPhasing}. Ordinary
///   visible stalking is still obstructed by a tree, which is what keeps the phasing legible as a
///   *privilege* rather than as the boss simply not colliding with things.
/// - `malice_phase_denied` — wins over both. This is the safety net.
///
/// The deny tag exists because the natural/constructed line does not survive contact with modded
/// content. The obvious mod-compatibility move is to drop `#minecraft:logs` into the supernatural
/// tag and be done with it — but that tag contains every *stripped* log and every six-sided wood
/// block, which are player-processed by definition, and doing so would quietly make log cabins
/// transparent. Seeding the deny tag with those means the broad addition is safe to make: a
/// datapack can widen `phase_supernatural` as far as it likes without also opening up
/// anything a player is likely to have built with.
///
/// This is also why the check is never "is the material wood". Planks, doors, chests, ladders and
/// stripped logs are all wood, and every one of them is somebody's house.
public final class MalicePhasing {
    /// Leaves and light vegetation. Passable in every state.
    public static final TagKey<Block> PHASE_ALWAYS =
            TagKey.create(Registries.BLOCK, DivineEncounters.id("malice_phase_always"));
    /// Natural trunks. Passable only during supernatural movement states.
    public static final TagKey<Block> PHASE_SUPERNATURAL =
            TagKey.create(Registries.BLOCK, DivineEncounters.id("malice_phase_supernatural"));
    /// Never passable, whatever else claims otherwise. Processed and player-placed wood.
    public static final TagKey<Block> PHASE_DENIED =
            TagKey.create(Registries.BLOCK, DivineEncounters.id("malice_phase_denied"));

    private MalicePhasing() {
    }

    /// Light vegetation — passable regardless of what the boss is doing.
    public static boolean phasesAlways(BlockState state) {
        return !state.is(PHASE_DENIED) && state.is(PHASE_ALWAYS);
    }

    /// A natural trunk — passable only while `supernatural` movement is in effect.
    public static boolean phasesWhenSupernatural(BlockState state) {
        return !state.is(PHASE_DENIED) && state.is(PHASE_SUPERNATURAL);
    }

    /// Whether this block should be ignored for collision right now.
    ///
    /// @param supernatural whether the current movement state unlocks trunk traversal
    public static boolean canPhase(BlockState state, boolean supernatural) {
        if (state.is(PHASE_DENIED)) {
            return false;
        }
        return state.is(PHASE_ALWAYS) || (supernatural && state.is(PHASE_SUPERNATURAL));
    }

    /// Whether a block prevents Malice from *resolving* into the space it occupies.
    ///
    /// Deliberately stricter than {@link #canPhase}: only light vegetation is transparent to a
    /// manifestation. A trunk can be travelled through and still must not be materialised inside,
    /// because a boss that resolves inside a tree is a boss that attacks from inside a tree — the
    /// player cannot see it, cannot read the tell, and cannot answer it.
    ///
    /// Phase through obstacles; resolve into valid space.
    ///
    /// Takes the level and position rather than the state alone because a block's collision shape
    /// can depend on both — reading it from an empty block getter gives the wrong answer for
    /// anything context-sensitive.
    public static boolean blocksManifestation(BlockGetter level, BlockPos pos, BlockState state) {
        return !phasesAlways(state) && !state.getCollisionShape(level, pos).isEmpty();
    }
}
