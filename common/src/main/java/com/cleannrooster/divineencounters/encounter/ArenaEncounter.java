package com.cleannrooster.divineencounters.encounter;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.encounter.anchor.AnchorKind;
import com.cleannrooster.divineencounters.encounter.anchor.AnchorPoint;
import com.cleannrooster.divineencounters.encounter.anchor.AnchorRegistry;
import com.cleannrooster.divineencounters.encounter.structure.ManifestationPlan;
import com.cleannrooster.divineencounters.encounter.structure.StructureManifestation;
import com.cleannrooster.divineencounters.encounter.structure.TemplateBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/// One live arena: its lifecycle, its structure, its boss, and its authored positions.
///
/// The controller owns *when* things happen. It does not build anything itself — that is
/// {@link StructureManifestation} — and it does not fight anything, which is the boss's problem.
/// Keeping those apart is what lets the same lifecycle drive two arenas that look nothing alike.
public final class ArenaEncounter {
    private final ArenaDefinition definition;
    private final BlockPos origin;
    private final AnchorRegistry anchors = new AnchorRegistry();

    private EncounterState state = EncounterState.DORMANT;
    private int stateTicks;
    private @Nullable StructureManifestation manifestation;
    private @Nullable TemplateBlocks.LoadedTemplate template;
    private @Nullable UUID bossId;
    private boolean ticketsHeld;

    public ArenaEncounter(ArenaDefinition definition, BlockPos origin) {
        this.definition = definition;
        this.origin = origin;
    }

    // --- queries ---------------------------------------------------------------------------------

    public ArenaDefinition definition() {
        return this.definition;
    }

    public BlockPos origin() {
        return this.origin;
    }

    public EncounterState state() {
        return this.state;
    }

    public AnchorRegistry anchors() {
        return this.anchors;
    }

    public boolean isFinished() {
        return this.state.isFinished();
    }

    /// The arena's world extent, once its template is known. Falls back to a nominal box before
    /// then, so callers can always ask.
    public BoundingBox bounds() {
        if (this.manifestation != null) {
            return this.manifestation.bounds();
        }
        return new BoundingBox(this.origin).inflatedBy(24);
    }

    public Vec3 centre() {
        var box = bounds();
        return new Vec3((box.minX() + box.maxX()) * 0.5, box.minY(), (box.minZ() + box.maxZ()) * 0.5);
    }

    // --- lifecycle -------------------------------------------------------------------------------

    /// Advance one tick. Returns false once the encounter is done and can be dropped.
    public boolean tick(ServerLevel level) {
        this.stateTicks++;
        return switch (this.state) {
            case DORMANT -> tickWarning(level);
            case MANIFESTING -> tickManifesting(level);
            case SEALED -> tickSealed(level);
            case ACTIVE -> tickActive(level);
            case RETRACTING -> tickRetracting(level);
            case COMPLETED -> false;
        };
    }

    /// The warning beat. Conditions have been met and something is clearly about to happen, but the
    /// arena has not committed — a player who leaves now is not trapped.
    ///
    /// This exists because an arena that erupts the instant you wander into a flat plains biome
    /// would be a hostile surprise rather than a dramatic one.
    private boolean tickWarning(ServerLevel level) {
        if (this.stateTicks == 1) {
            level.playSound(null, this.origin, net.minecraft.sounds.SoundEvents.DEEPSLATE_BREAK,
                    net.minecraft.sounds.SoundSource.HOSTILE, 2.0f, 0.4f);
        }
        if (this.stateTicks % 5 == 0) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    this.origin.getX() + 0.5, this.origin.getY(), this.origin.getZ() + 0.5,
                    12, 6.0, 0.2, 6.0, 0.01);
        }
        if (this.stateTicks < this.definition.warningTicks()) {
            return true;
        }
        return beginManifestation(level);
    }

    /// Load the template and start building. Failing here aborts cleanly rather than leaving a
    /// half-registered encounter behind.
    private boolean beginManifestation(ServerLevel level) {
        var loaded = TemplateBlocks.load(level, this.definition.templateId());
        if (loaded.isEmpty() || loaded.get().isEmpty()) {
            DivineEncounters.LOGGER.warn("Arena {} has no usable template {}; aborting",
                    this.definition.id(), this.definition.templateId());
            this.state = EncounterState.COMPLETED;
            return false;
        }
        this.template = loaded.get();
        var plan = ManifestationPlan.build(this.template, this.definition.order(),
                this.definition.batchCount());
        this.manifestation = new StructureManifestation(level, this.origin, plan,
                this.definition.persistence(), this.definition.effects(),
                this.definition.tickInterval());

        acquireTickets(level);
        enter(EncounterState.MANIFESTING);
        return true;
    }

    private boolean tickManifesting(ServerLevel level) {
        var manifestation = this.manifestation;
        if (manifestation == null) {
            this.state = EncounterState.COMPLETED;
            return false;
        }
        if (manifestation.tick()) {
            return true;
        }
        resolveAnchors(level);
        enter(EncounterState.SEALED);
        return true;
    }

    /// Turn the template's authored markers into world positions the boss can query.
    ///
    /// Doing this once, here, is why a boss never searches cage blocks for somewhere to perch — it
    /// asks the registry.
    private void resolveAnchors(ServerLevel level) {
        var template = this.template;
        if (template == null) {
            return;
        }
        var centre = centre();
        var floorY = this.origin.getY();
        for (var marker : template.markers()) {
            if (marker.metadata().startsWith("stage_")) {
                // Staging markers choreograph the assembly; they are not places to stand.
                continue;
            }
            var world = this.origin.offset(marker.localPos());
            this.anchors.add(AnchorPoint.of(marker.metadata(), AnchorKind.fromMarker(marker.metadata()),
                    world, centre, floorY));
        }
    }

    private boolean tickSealed(ServerLevel level) {
        spawnBoss(level);
        enter(EncounterState.ACTIVE);
        return true;
    }

    private void spawnBoss(ServerLevel level) {
        var type = this.definition.boss().get();
        var spawnAnchor = this.anchors.single(AnchorKind.BOSS_SPAWN);
        var spawn = spawnAnchor != null ? spawnAnchor.position() : centre();

        var boss = type.create(level);
        if (boss == null) {
            DivineEncounters.LOGGER.warn("Arena {} could not create its boss", this.definition.id());
            return;
        }
        boss.moveTo(spawn.x, spawn.y, spawn.z, level.getRandom().nextFloat() * 360.0f, 0.0f);
        boss.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(spawn)),
                MobSpawnType.EVENT, null);
        level.addFreshEntity(boss);
        this.bossId = boss.getUUID();
        bindArena(boss);
    }

    /// Hand the boss its arena. Bosses that do not use one are unaffected — this is a capability,
    /// not a requirement.
    private void bindArena(Mob boss) {
        if (boss instanceof com.cleannrooster.divineencounters.content.malice.entity.VisageOfMaliceEntity malice) {
            var hidden = centre().add(0.0, -3.0, 0.0);
            malice.bindArena(this.anchors, hidden);
        }
    }

    private boolean tickActive(ServerLevel level) {
        var boss = this.bossId == null ? null : level.getEntity(this.bossId);
        if (boss != null && boss.isAlive()) {
            return true;
        }
        // Give a moment before the walls come down, so the kill lands rather than being cut off.
        if (this.stateTicks < 40) {
            return true;
        }
        beginRetraction();
        return true;
    }

    /// Start taking the arena apart. Public so an encounter can be ended early — a debug command,
    /// or a player who logs out and abandons it.
    public void beginRetraction() {
        if (this.manifestation != null) {
            this.manifestation.beginRetraction();
        }
        this.anchors.releaseAll();
        enter(EncounterState.RETRACTING);
    }

    private boolean tickRetracting(ServerLevel level) {
        var manifestation = this.manifestation;
        if (manifestation != null && manifestation.tick()) {
            return true;
        }
        complete(level);
        return false;
    }

    private void complete(ServerLevel level) {
        releaseTickets(level);
        this.anchors.clear();
        enter(EncounterState.COMPLETED);
    }

    /// Tear down immediately — server shutdown, or an encounter being cancelled. Restores terrain
    /// rather than abandoning it, so a cancelled arena never becomes permanent scenery.
    public void abort(ServerLevel level) {
        if (this.manifestation != null && !this.manifestation.isComplete()) {
            this.manifestation.beginRetraction();
            // Drain the retraction synchronously; there is no next tick to rely on.
            var guard = 0;
            while (this.manifestation.tick() && guard++ < 10_000) {
                // intentionally empty
            }
        }
        complete(level);
    }

    private void acquireTickets(ServerLevel level) {
        if (!this.ticketsHeld) {
            EncounterTickets.acquire(level, bounds());
            this.ticketsHeld = true;
        }
    }

    private void releaseTickets(ServerLevel level) {
        if (this.ticketsHeld) {
            EncounterTickets.release(level, bounds());
            this.ticketsHeld = false;
        }
    }

    private void enter(EncounterState next) {
        this.state = next;
        this.stateTicks = 0;
    }

    /// Players currently inside the arena footprint — the fight's participants.
    public List<Player> participants(ServerLevel level) {
        var box = bounds();
        return level.getEntitiesOfClass(Player.class,
                new net.minecraft.world.phys.AABB(box.minX(), box.minY(), box.minZ(),
                        box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1),
                player -> player.isAlive() && !player.isSpectator());
    }

    // --- persistence -----------------------------------------------------------------------------

    public CompoundTag save(CompoundTag tag) {
        tag.putString("Arena", this.definition.id().toString());
        tag.put("Origin", NbtUtils.writeBlockPos(this.origin));
        tag.putString("State", this.state.name());
        tag.putInt("StateTicks", this.stateTicks);
        if (this.bossId != null) {
            tag.putUUID("Boss", this.bossId);
        }
        if (this.manifestation != null) {
            tag.put("Manifestation", this.manifestation.save(new CompoundTag()));
        }
        return tag;
    }

    /// Restore a saved encounter.
    ///
    /// The template and plan are rebuilt from the definition rather than persisted — they are
    /// derived data, and rebuilding them means a template edited between sessions takes effect
    /// rather than a stale copy being replayed.
    public void load(ServerLevel level, CompoundTag tag) {
        this.state = parseState(tag.getString("State"));
        this.stateTicks = tag.getInt("StateTicks");
        this.bossId = tag.hasUUID("Boss") ? tag.getUUID("Boss") : null;

        if (this.state == EncounterState.DORMANT || this.state.isFinished()) {
            return;
        }
        var loaded = TemplateBlocks.load(level, this.definition.templateId());
        if (loaded.isEmpty()) {
            this.state = EncounterState.COMPLETED;
            return;
        }
        this.template = loaded.get();
        var plan = ManifestationPlan.build(this.template, this.definition.order(),
                this.definition.batchCount());
        this.manifestation = new StructureManifestation(level, this.origin, plan,
                this.definition.persistence(), this.definition.effects(),
                this.definition.tickInterval());
        if (tag.contains("Manifestation")) {
            this.manifestation.load(tag.getCompound("Manifestation"));
        }
        resolveAnchors(level);
        acquireTickets(level);
    }

    private static EncounterState parseState(String name) {
        try {
            return EncounterState.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return EncounterState.COMPLETED;
        }
    }
}
