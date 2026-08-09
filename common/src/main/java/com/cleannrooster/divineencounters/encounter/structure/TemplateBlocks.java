package com.cleannrooster.divineencounters.encounter.structure;

import com.cleannrooster.divineencounters.DivineEncounters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Reads a structure template into a flat list of blocks that can be placed a few at a time.
///
/// Vanilla only offers all-or-nothing placement — `StructureTemplate.placeInWorld` writes the whole
/// thing in one call — and the field holding its block list is private. The usual workaround is an
/// accessor mixin, which this project deliberately does not have and should not gain for one read.
///
/// The way around it: `StructureTemplate.save(CompoundTag)` is public, so a loaded template can be
/// round-tripped back to NBT and parsed with entirely public API. The format is stable and simple —
/// a palette of block states plus a list of `{pos, state, nbt}` entries — which is what makes staged
/// manifestation possible without touching the game's internals.
///
/// Markers come out separately, via `filterBlocks`, so authored metadata (perch points, hazard
/// segments, stage groupings) is available without polluting the block list.
public final class TemplateBlocks {
    /// Structure blocks are authoring metadata. They carry markers and must never be placed.
    private static final String MODE_TAG = "mode";
    private static final String METADATA_TAG = "metadata";
    private static final String DATA_MODE = "DATA";

    private TemplateBlocks() {
    }

    /// One block of a template, in template-local coordinates.
    public record TemplateBlock(BlockPos localPos, BlockState state, @Nullable CompoundTag nbt) {
    }

    /// An authored marker: a structure block in DATA mode, carrying a metadata string such as
    /// `perch_upper_left` or `stage_1_ribs`.
    public record TemplateMarker(BlockPos localPos, String metadata) {
    }

    /// A template decomposed into everything the manifestation engine needs.
    public record LoadedTemplate(Vec3i size, List<TemplateBlock> blocks, List<TemplateMarker> markers) {
        public boolean isEmpty() {
            return this.blocks.isEmpty();
        }

        /// Markers whose metadata begins with a prefix, e.g. `stage_` or `perch`.
        public List<TemplateMarker> markersMatching(String prefix) {
            var matched = new ArrayList<TemplateMarker>();
            for (var marker : this.markers) {
                if (marker.metadata().startsWith(prefix)) {
                    matched.add(marker);
                }
            }
            return matched;
        }
    }

    /// Whether a template exists, without paying to decompose it. Used as a pre-flight so an
    /// encounter is never started — and an omen never consumed — for an arena that cannot be built.
    public static boolean exists(ServerLevel level, ResourceLocation templateId) {
        return level.getStructureManager().get(templateId).isPresent();
    }

    /// Load and decompose a template. Empty when the template is missing, which callers should
    /// treat as "this arena cannot be built" rather than as an error worth crashing over.
    public static Optional<LoadedTemplate> load(ServerLevel level, ResourceLocation templateId) {
        var template = level.getStructureManager().get(templateId);
        if (template.isEmpty()) {
            DivineEncounters.LOGGER.warn("Structure template {} not found", templateId);
            return Optional.empty();
        }
        return Optional.of(decompose(level, template.get()));
    }

    private static LoadedTemplate decompose(ServerLevel level, StructureTemplate template) {
        var tag = template.save(new CompoundTag());
        var blocks = readBlocks(level, tag);
        var markers = readMarkers(template);
        return new LoadedTemplate(template.getSize(), blocks, markers);
    }

    /// Parse the palette and block list out of a saved template.
    ///
    /// Handles both `palette` (the usual single-palette form) and `palettes` (produced when a
    /// template carries per-variant palettes) — the first entry is used, matching what vanilla
    /// placement would pick by default.
    private static List<TemplateBlock> readBlocks(ServerLevel level, CompoundTag tag) {
        var blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        var paletteTag = resolvePalette(tag);
        var palette = new ArrayList<BlockState>(paletteTag.size());
        for (var i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(i)));
        }

        var blockList = tag.getList(StructureTemplate.BLOCKS_TAG, Tag.TAG_COMPOUND);
        var blocks = new ArrayList<TemplateBlock>(blockList.size());
        for (var i = 0; i < blockList.size(); i++) {
            var entry = blockList.getCompound(i);
            var stateIndex = entry.getInt(StructureTemplate.BLOCK_TAG_STATE);
            if (stateIndex < 0 || stateIndex >= palette.size()) {
                continue;
            }
            var state = palette.get(stateIndex);
            // Structure blocks are authoring metadata; air is not worth a placement slot.
            if (state.is(Blocks.STRUCTURE_BLOCK) || state.isAir()) {
                continue;
            }
            var pos = readPos(entry.getList(StructureTemplate.BLOCK_TAG_POS, Tag.TAG_INT));
            if (pos == null) {
                continue;
            }
            var blockEntity = entry.contains(StructureTemplate.BLOCK_TAG_NBT, Tag.TAG_COMPOUND)
                    ? entry.getCompound(StructureTemplate.BLOCK_TAG_NBT)
                    : null;
            blocks.add(new TemplateBlock(pos, state, blockEntity));
        }
        return blocks;
    }

    private static ListTag resolvePalette(CompoundTag tag) {
        if (tag.contains(StructureTemplate.PALETTE_TAG, Tag.TAG_LIST)) {
            return tag.getList(StructureTemplate.PALETTE_TAG, Tag.TAG_COMPOUND);
        }
        var palettes = tag.getList(StructureTemplate.PALETTE_LIST_TAG, Tag.TAG_LIST);
        return palettes.isEmpty() ? new ListTag() : palettes.getList(0);
    }

    private static @Nullable BlockPos readPos(ListTag list) {
        if (list.size() != 3) {
            return null;
        }
        return new BlockPos(list.getInt(0), list.getInt(1), list.getInt(2));
    }

    /// Authored markers, read through the public filter rather than the saved NBT — this is the one
    /// thing vanilla exposes directly, and it already resolves the block entity data for us.
    private static List<TemplateMarker> readMarkers(StructureTemplate template) {
        var settings = new net.minecraft.world.level.levelgen.structure.templatesystem
                .StructurePlaceSettings();
        var found = template.filterBlocks(BlockPos.ZERO, settings, Blocks.STRUCTURE_BLOCK);
        var markers = new ArrayList<TemplateMarker>(found.size());
        for (var info : found) {
            var nbt = info.nbt();
            if (nbt == null || !DATA_MODE.equals(nbt.getString(MODE_TAG))) {
                continue;
            }
            var metadata = nbt.getString(METADATA_TAG);
            if (!metadata.isEmpty()) {
                markers.add(new TemplateMarker(info.pos(), metadata));
            }
        }
        return markers;
    }
}
