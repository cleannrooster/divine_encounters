package com.cleannrooster.divineencounters.encounter.structure;

import com.cleannrooster.divineencounters.encounter.structure.TemplateBlocks.TemplateBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Builds a structure into the world a batch at a time, and takes it back out again.
///
/// The engine is deliberately boss-agnostic — it knows about templates, batches and blocks, and
/// nothing about what is being built or why. Both arenas use it, with different templates and
/// different {@link ManifestationOrder}s.
///
/// Three responsibilities beyond "place blocks":
///
/// 1. **Never suffocate anyone.** A block whose collision volume overlaps a player is deferred and
///    retried on later batches. An arena that materialises around a player must not materialise
///    *inside* one, and now that arenas erupt wherever an omen-carrying player happens to be, this
///    is a live hazard rather than a theoretical one.
/// 2. **Remember what it replaced.** Under {@link PersistenceMode#RESTORE_ORIGINAL} every
///    overwritten block state is captured before it is lost, so retraction can put the world back.
/// 3. **Survive a restart.** Progress and the captured states serialise to NBT, so a server that
///    stops halfway through does not leave a permanently half-built structure.
public final class StructureManifestation {
    /// Ticks between batches. With a 24-batch plan this puts a full assembly around 2.4 seconds —
    /// fast enough to read as a trap springing rather than a construction project.
    private static final int DEFAULT_TICK_INTERVAL = 2;
    /// Block-update flags: update neighbours so things like walls connect, but skip the neighbour
    /// *shape* updates that would make half-built structures collapse mid-assembly.
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS;
    /// How many batches a deferred block may wait before it is placed anyway. Bounded so a player
    /// standing still forever cannot leave a permanent hole in an arena wall.
    private static final int MAX_DEFERRALS = 6;

    private final ServerLevel level;
    private final BlockPos origin;
    private final ManifestationPlan plan;
    private final PersistenceMode persistence;
    private final ManifestationEffects effects;
    private final int tickInterval;

    /// Original states of every position written, for restoration. Insertion-ordered so retraction
    /// can walk it deterministically.
    private final Map<BlockPos, BlockState> captured = new LinkedHashMap<>();
    /// Blocks postponed because a player was standing in them, with how many times each has been.
    private final Map<TemplateBlock, Integer> deferred = new HashMap<>();

    private int nextBatch;
    private int cooldown;
    private boolean reversing;
    private boolean complete;

    public StructureManifestation(ServerLevel level, BlockPos origin, ManifestationPlan plan,
                                  PersistenceMode persistence, ManifestationEffects effects) {
        this(level, origin, plan, persistence, effects, DEFAULT_TICK_INTERVAL);
    }

    public StructureManifestation(ServerLevel level, BlockPos origin, ManifestationPlan plan,
                                  PersistenceMode persistence, ManifestationEffects effects,
                                  int tickInterval) {
        this.level = level;
        this.origin = origin;
        this.plan = plan;
        this.persistence = persistence;
        this.effects = effects;
        this.tickInterval = Math.max(1, tickInterval);
    }

    // --- state -----------------------------------------------------------------------------------

    public boolean isComplete() {
        return this.complete;
    }

    public boolean isReversing() {
        return this.reversing;
    }

    /// Assembly progress, 0-1.
    public float progress() {
        if (this.plan.batchCount() == 0) {
            return 1.0f;
        }
        return Math.min(1.0f, (float) this.nextBatch / this.plan.batchCount());
    }

    public BlockPos origin() {
        return this.origin;
    }

    /// World-space extent of the whole structure.
    public BoundingBox bounds() {
        var size = this.plan.size();
        return new BoundingBox(this.origin.getX(), this.origin.getY(), this.origin.getZ(),
                this.origin.getX() + size.getX() - 1,
                this.origin.getY() + size.getY() - 1,
                this.origin.getZ() + size.getZ() - 1);
    }

    // --- ticking ---------------------------------------------------------------------------------

    /// Advance the assembly (or retraction). Returns false once there is nothing left to do.
    public boolean tick() {
        if (this.complete) {
            return false;
        }
        if (--this.cooldown > 0) {
            return true;
        }
        this.cooldown = this.tickInterval;

        if (this.reversing) {
            return tickRetraction();
        }
        return tickAssembly();
    }

    private boolean tickAssembly() {
        if (this.nextBatch >= this.plan.batchCount()) {
            // One last attempt at anything a player was standing in, then done.
            flushDeferred();
            this.complete = true;
            return false;
        }
        var batch = this.plan.batch(this.nextBatch);
        var placed = new ArrayList<BlockPos>(batch.size());

        retryDeferred(placed);
        for (var block : batch) {
            placeOrDefer(block, placed);
        }

        if (!placed.isEmpty()) {
            this.effects.onBatch(this.level, boundsOf(placed), this.nextBatch,
                    this.plan.batchCount(), false);
        }
        this.nextBatch++;
        return true;
    }

    /// Begin taking the structure back out, in reverse of how it went up.
    ///
    /// Reusing the same plan backwards rather than building a separate teardown system means the
    /// canopy loosens before the walls open before the roots sink — the assembly read in reverse,
    /// for free.
    public void beginRetraction() {
        if (this.reversing) {
            return;
        }
        this.reversing = true;
        this.complete = false;
        this.deferred.clear();
        // Continue from wherever assembly actually reached, so an interrupted build retracts only
        // what it managed to place.
        this.nextBatch = Math.min(this.nextBatch, this.plan.batchCount()) - 1;
        this.cooldown = this.tickInterval;
    }

    private boolean tickRetraction() {
        if (this.nextBatch < 0) {
            finishRetraction();
            return false;
        }
        var batch = this.plan.batch(this.nextBatch);
        var cleared = new ArrayList<BlockPos>(batch.size());
        for (var block : batch) {
            var worldPos = this.origin.offset(block.localPos());
            if (restoreOne(worldPos)) {
                cleared.add(worldPos);
            }
        }
        if (!cleared.isEmpty()) {
            this.effects.onBatch(this.level, boundsOf(cleared), this.nextBatch,
                    this.plan.batchCount(), true);
        }
        this.nextBatch--;
        return true;
    }

    /// Restore anything the batched pass missed — positions captured but not covered by a plan
    /// batch, which can happen if two template blocks share a position.
    private void finishRetraction() {
        if (this.persistence.restores()) {
            for (var entry : new ArrayList<>(this.captured.entrySet())) {
                this.level.setBlock(entry.getKey(), entry.getValue(), PLACE_FLAGS);
            }
        }
        this.captured.clear();
        this.complete = true;
    }

    // --- placement -------------------------------------------------------------------------------

    private void placeOrDefer(TemplateBlock block, List<BlockPos> placed) {
        var worldPos = this.origin.offset(block.localPos());
        if (wouldTrapPlayer(worldPos, block.state())) {
            this.deferred.merge(block, 1, Integer::sum);
            return;
        }
        place(block, worldPos);
        placed.add(worldPos);
    }

    private void retryDeferred(List<BlockPos> placed) {
        if (this.deferred.isEmpty()) {
            return;
        }
        var iterator = this.deferred.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var block = entry.getKey();
            var worldPos = this.origin.offset(block.localPos());
            // Either the player moved, or it has waited long enough that leaving a gap in the
            // structure is the worse outcome.
            if (!wouldTrapPlayer(worldPos, block.state()) || entry.getValue() >= MAX_DEFERRALS) {
                place(block, worldPos);
                placed.add(worldPos);
                iterator.remove();
            } else {
                entry.setValue(entry.getValue() + 1);
            }
        }
    }

    private void flushDeferred() {
        for (var block : new ArrayList<>(this.deferred.keySet())) {
            place(block, this.origin.offset(block.localPos()));
        }
        this.deferred.clear();
    }

    private void place(TemplateBlock block, BlockPos worldPos) {
        if (this.persistence.restores()) {
            // Capture before the original is lost, and only the first time — a position written
            // twice must still restore to what was there before the *first* write.
            this.captured.putIfAbsent(worldPos, this.level.getBlockState(worldPos));
        }
        this.level.setBlock(worldPos, block.state(), PLACE_FLAGS);
        if (block.nbt() != null) {
            var blockEntity = this.level.getBlockEntity(worldPos);
            if (blockEntity != null) {
                var tag = block.nbt().copy();
                tag.putInt("x", worldPos.getX());
                tag.putInt("y", worldPos.getY());
                tag.putInt("z", worldPos.getZ());
                blockEntity.loadWithComponents(tag, this.level.registryAccess());
            }
        }
    }

    private boolean restoreOne(BlockPos worldPos) {
        if (this.persistence.restores()) {
            var original = this.captured.remove(worldPos);
            if (original != null) {
                this.level.setBlock(worldPos, original, PLACE_FLAGS);
                return true;
            }
            return false;
        }
        // Nothing captured to go back to, so simply clear it out.
        this.level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
        return true;
    }

    /// Whether placing this state here would put a solid block inside a player.
    private boolean wouldTrapPlayer(BlockPos pos, BlockState state) {
        if (state.getCollisionShape(this.level, pos).isEmpty()) {
            return false;
        }
        var box = new AABB(pos);
        for (var player : this.level.getEntitiesOfClass(Player.class, box)) {
            if (!player.isSpectator()) {
                return true;
            }
        }
        return false;
    }

    private BoundingBox boundsOf(List<BlockPos> positions) {
        var first = positions.get(0);
        var box = new BoundingBox(first);
        for (var pos : positions) {
            box.encapsulate(pos);
        }
        return box;
    }

    // --- persistence -----------------------------------------------------------------------------

    /// Serialise progress and captured terrain, so a restart mid-assembly can pick up where it left
    /// off instead of stranding a half-built structure in the world.
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("NextBatch", this.nextBatch);
        tag.putBoolean("Reversing", this.reversing);
        tag.putBoolean("Complete", this.complete);
        tag.put("Origin", NbtUtils.writeBlockPos(this.origin));

        var list = new ListTag();
        for (var entry : this.captured.entrySet()) {
            var element = new CompoundTag();
            element.put("Pos", NbtUtils.writeBlockPos(entry.getKey()));
            element.put("State", NbtUtils.writeBlockState(entry.getValue()));
            list.add(element);
        }
        tag.put("Captured", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.nextBatch = tag.getInt("NextBatch");
        this.reversing = tag.getBoolean("Reversing");
        this.complete = tag.getBoolean("Complete");
        this.captured.clear();

        var blockLookup = this.level.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
        var list = tag.getList("Captured", Tag.TAG_COMPOUND);
        for (var i = 0; i < list.size(); i++) {
            var element = list.getCompound(i);
            var pos = NbtUtils.readBlockPos(element, "Pos");
            if (pos.isEmpty()) {
                continue;
            }
            this.captured.put(pos.get(), NbtUtils.readBlockState(blockLookup,
                    element.getCompound("State")));
        }
    }
}
