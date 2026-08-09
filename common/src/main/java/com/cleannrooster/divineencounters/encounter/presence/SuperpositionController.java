package com.cleannrooster.divineencounters.encounter.presence;

import com.cleannrooster.divineencounters.encounter.anchor.AnchorRegistry;
import com.cleannrooster.divineencounters.encounter.perception.GloomProfile;
import com.cleannrooster.divineencounters.encounter.perception.ObservationTracker;
import com.cleannrooster.divineencounters.encounter.perception.StareMemory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Owns whether an entity currently has a definite position, and everything that follows from it.
///
/// This is the mechanic, in one place. Attacks do not implement it; they *request* a manifestation
/// category and wait. The AI does not implement it either; it asks whether the entity is resolved
/// and acts accordingly. Keeping it here is what stops "sometimes it teleports" logic from
/// scattering across nine attack methods where the fairness rules would inevitably diverge.
///
/// ### What the states cost
/// While `UNRESOLVED` the entity is parked at a hidden interior position and made intangible. The
/// technical position is an implementation detail and is never read as its fictional location —
/// the only code allowed to care is {@link #manifestAt}, which moves it back out.
///
/// Parking it near the arena rather than far away is deliberate: entity tracking stays alive, so
/// the boss bar remains on screen. The player should know it is still here, just not *where*.
public final class SuperpositionController {
    /// Ticks spent visibly fading before the entity actually goes. Short — its only job is to make
    /// sure a player who spins round mid-transition sees a dissipation rather than a pop.
    private static final int DISSOLVE_TICKS = 6;
    /// Ticks after arriving before an attack may begin. The player's recognition window.
    private static final int MANIFEST_TICKS = 5;
    /// How often the candidate pool is rebuilt from scratch, as opposed to cheaply refreshed.
    private static final int REBUILD_INTERVAL = 10;
    /// Minimum time unresolved before a reacquisition sweep may flush the entity out. Without it,
    /// spinning the camera would reliably produce the boss, and hiding would mean nothing.
    private static final int MIN_UNRESOLVED_TICKS = 20;

    /// Minimum time *resolved* before it may dissolve again, whatever the observation state says.
    ///
    /// This is the counterweight to the grace period, and without it the encounter collapses into
    /// hide-and-seek. The problem is structural rather than a tuning accident: a rear ambush arrives
    /// behind a player who is by definition facing the other way, so the non-observation grace period
    /// starts counting the instant it lands. It could earn a second dissolve before the player had
    /// finished turning around — and then the fight is spent hunting an absence instead of fighting a
    /// boss.
    ///
    /// A floor here means every appearance is worth something to the player. It comes to you, it is
    /// real for long enough to be answered, and only then does it get to leave.
    private static final int MIN_RESOLVED_TICKS = 70;

    /// Absolute ceiling on how long a hold can keep it present. Beyond this the observation rules
    /// are the only thing that matters, whatever anyone is still asking for.
    private static final int MAX_HELD_TICKS = 160;

    private final Mob owner;
    private final CandidateResolver resolver;
    private final ObservationTracker observation = new ObservationTracker();
    private final StareMemory stare = new StareMemory();
    private PresenceCues cues = PresenceCues.none();
    /// Authored arena positions. Empty until an encounter registers one, which is what lets an
    /// arena-less fight run with ring candidates alone.
    private AnchorRegistry anchors = new AnchorRegistry();

    private PresenceState state = PresenceState.RESOLVED;
    private int stateTicks;
    private List<ManifestationCandidate> candidates = List.of();
    private int rebuildCountdown;
    /// Where the entity is parked while it has no position. Set by the encounter, or derived.
    private @Nullable Vec3 hiddenAnchor;
    /// Set when a manifestation has been requested but not yet performed.
    private @Nullable ManifestKind pendingKind;
    private @Nullable ManifestationCandidate lastManifestation;

    public SuperpositionController(Mob owner, CandidateResolver resolver) {
        this.owner = owner;
        this.resolver = resolver;
    }

    public SuperpositionController cues(PresenceCues cues) {
        this.cues = cues;
        return this;
    }

    /// Blocks the owner may resolve inside despite their collision shape.
    ///
    /// Defaults to none, which is the right answer for anything that relates to the world normally.
    /// A boss that treats some blocks as empty space supplies its own predicate here — and should
    /// supply the *narrow* one: what it may materialise inside, not everything it can travel
    /// through. Those are different permissions, and conflating them is how a boss ends up
    /// manifesting inside a tree.
    public SuperpositionController passableBlocks(java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> passable) {
        this.passableBlocks = passable;
        // The same blocks also stop concealing it. If foliage is thin enough for the boss to drift
        // through, it is thin enough for a player watching to still be watching — and observation is
        // the only counterplay the encounter offers, so letting a leaf silently break it would hand
        // the boss a free dissolve every time it crossed a treeline in plain view.
        this.observation.seeThrough(passable);
        return this;
    }

    private java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> passableBlocks =
            CandidateContext.SOLID_IS_SOLID;

    /// Where to park the entity while unresolved — normally the arena centre, below the floor.
    public void setHiddenAnchor(@Nullable Vec3 anchor) {
        this.hiddenAnchor = anchor;
    }

    // --- queries ---------------------------------------------------------------------------------

    public PresenceState state() {
        return this.state;
    }

    public ObservationTracker observation() {
        return this.observation;
    }

    public boolean isResolved() {
        return this.state == PresenceState.RESOLVED;
    }

    public boolean isUnresolved() {
        return this.state == PresenceState.UNRESOLVED;
    }

    /// The fairness gate, asked by the AI before every attack. Damage can only ever originate from
    /// a definite position.
    public boolean mayAttack() {
        return this.state.allowsAttack();
    }

    public int ticksInState() {
        return this.stateTicks;
    }

    public List<ManifestationCandidate> candidates() {
        return this.candidates;
    }

    public @Nullable ManifestationCandidate lastManifestation() {
        return this.lastManifestation;
    }

    /// Whether a category of manifestation is currently achievable — lets the AI check an action is
    /// possible before choosing it, instead of committing and then failing.
    public boolean canManifest(ManifestKind kind) {
        return this.resolver.canSatisfy(this.candidates, kind);
    }

    // --- ticking ---------------------------------------------------------------------------------

    /// Advance one server tick. `participants` are the players eligible to observe.
    public void tick(ServerLevel level, List<? extends Player> participants, GloomProfile profile,
                     @Nullable Player focus) {
        this.stateTicks++;
        this.stare.tick(participants);
        this.observation.tick(this.owner, participants, profile);
        if (this.holdTicks > 0) {
            this.holdTicks--;
        }

        // Candidates only mean anything relative to somebody to hide from.
        if (focus != null) {
            maintainCandidates(level, participants, profile, focus);
        }

        switch (this.state) {
            case RESOLVED -> tickResolved(profile);
            case DISSOLVING -> tickDissolving();
            case UNRESOLVED -> tickUnresolved(level);
            case MANIFESTING -> tickManifesting();
        }
    }

    private void maintainCandidates(ServerLevel level, List<? extends Player> participants,
                                    GloomProfile profile, Player focus) {
        var context = CandidateContext.of(level, this.owner, focus, participants, profile,
                this.stare, this.anchors, null, this.passableBlocks);
        if (this.rebuildCountdown-- <= 0) {
            this.candidates = this.resolver.build(context);
            this.rebuildCountdown = REBUILD_INTERVAL;
        } else if (this.state == PresenceState.UNRESOLVED) {
            // While unresolved the pool must stay honest every tick — a player walking toward a
            // candidate should invalidate it immediately, not up to half a second later.
            this.candidates = this.resolver.refresh(context, this.candidates);
        }
    }

    /// Attach an arena's authored positions, upgrading the candidate pool with perches and edges.
    public void setAnchors(AnchorRegistry anchors) {
        this.anchors = anchors;
    }

    private void tickResolved(GloomProfile profile) {
        if (holdsPresence(this.stateTicks, this.holdTicks)) {
            return;
        }
        // Dissolution has to be *earned*: the grace period must elapse with nobody watching, which
        // by construction means nobody is in a position to see it happen.
        if (this.observation.hasLostObservation(profile)) {
            enter(PresenceState.DISSOLVING);
        }
    }

    /// Ticks of guaranteed presence still owed on top of the minimum.
    private int holdTicks;

    /// Refuse to dissolve for at least this many more ticks.
    ///
    /// Additive to {@link #MIN_RESOLVED_TICKS} rather than a replacement for it, and only ever
    /// extends — a later call with a smaller value cannot cut a hold short. Callers use it to keep
    /// the boss present through something the player is owed: an attack it committed to, and the
    /// punish window afterwards.
    public void holdResolved(int ticks) {
        this.holdTicks = Math.max(this.holdTicks, ticks);
    }

    /// Whether it is currently forbidden from leaving. Diagnostic, and useful to AI that wants to
    /// know whether pressing the attack is safe.
    public boolean isHeldResolved() {
        return this.state == PresenceState.RESOLVED
                && holdsPresence(this.stateTicks, this.holdTicks);
    }

    /// The dwell rule, as a pure function of the two counters.
    ///
    /// Split out and made public so it can be verified without a live level. That is not incidental
    /// tidiness: the rule has already failed once in a way no headless test could reach, because a
    /// caller renewing a hold every tick meant the window never drained and the boss stopped
    /// dissolving at all. Expressed like this, the whole space — including "hold is always
    /// non-zero" — is checkable in a loop.
    ///
    /// Two clauses, and they do different jobs. The floor guarantees every appearance is worth
    /// something to the player. The ceiling guarantees no caller can make presence permanent,
    /// because a caller that renews forever is easy to write and impossible to notice.
    public static boolean holdsPresence(int resolvedTicks, int holdTicks) {
        if (resolvedTicks < MIN_RESOLVED_TICKS) {
            return true;
        }
        return holdTicks > 0 && resolvedTicks < MAX_HELD_TICKS;
    }

    private void tickDissolving() {
        // Being seen mid-fade cancels it outright — observation always wins.
        if (this.observation.isObserved()) {
            enter(PresenceState.RESOLVED);
            return;
        }
        if (this.stateTicks >= DISSOLVE_TICKS) {
            dissolve();
        }
    }

    private void tickUnresolved(ServerLevel level) {
        if (this.cues.weakInterval() > 0 && this.stateTicks % this.cues.weakInterval() == 0) {
            this.cues.emitWeak(level, this.candidates, this.owner.getRandom());
        }
        // Keep the parked body pinned; nothing should be able to nudge it.
        if (this.hiddenAnchor != null) {
            this.owner.setPos(this.hiddenAnchor.x, this.hiddenAnchor.y, this.hiddenAnchor.z);
            this.owner.setDeltaMovement(Vec3.ZERO);
        }
    }

    private void tickManifesting() {
        if (this.stateTicks >= MANIFEST_TICKS) {
            enter(PresenceState.RESOLVED);
        }
    }

    // --- transitions -----------------------------------------------------------------------------

    private void dissolve() {
        var anchor = this.hiddenAnchor != null
                ? this.hiddenAnchor
                : this.owner.position().add(0.0, -64.0, 0.0);
        this.hiddenAnchor = anchor;
        this.owner.setPos(anchor.x, anchor.y, anchor.z);
        this.owner.setDeltaMovement(Vec3.ZERO);
        this.owner.setNoGravity(true);
        this.owner.noPhysics = true;
        this.owner.setInvisible(true);
        this.owner.setInvulnerable(true);
        enter(PresenceState.UNRESOLVED);
    }

    /// Resolve at a candidate suitable for `kind`, or return false when none qualifies.
    ///
    /// Returning false is a normal outcome the caller must handle by choosing something else —
    /// never by forcing a position, which is how "it teleported into my face" bugs are born.
    public boolean requestManifestation(ServerLevel level, ManifestKind kind) {
        if (this.state != PresenceState.UNRESOLVED) {
            return false;
        }
        var chosen = this.resolver.select(this.candidates, kind, this.owner.getRandom());
        if (chosen == null) {
            return false;
        }
        // The tell fires *before* the entity arrives — this is the player's warning, and the first
        // half of the fairness contract.
        this.cues.emitStrong(level, chosen.cueOrigin());
        manifestAt(chosen);
        this.pendingKind = kind;
        return true;
    }

    /// Force a return to a definite position without a category — used when something external
    /// (a landed hit, an encounter script) demands the entity be real right now.
    public boolean forceManifestation(ServerLevel level) {
        if (this.state != PresenceState.UNRESOLVED) {
            return false;
        }
        for (var kind : new ManifestKind[]{ManifestKind.FLANK, ManifestKind.REAR_AMBUSH,
                ManifestKind.ARENA_EDGE, ManifestKind.REACQUIRE}) {
            if (requestManifestation(level, kind)) {
                return true;
            }
        }
        return false;
    }

    /// Whether a reacquisition sweep is currently allowed to flush the entity out. Gated on a
    /// minimum hidden duration so searching the arena still costs the player time.
    public boolean mayResolveOnSearch(RandomSource random, float chance) {
        return this.state == PresenceState.UNRESOLVED
                && this.stateTicks >= MIN_UNRESOLVED_TICKS
                && random.nextFloat() < chance;
    }

    private void manifestAt(ManifestationCandidate candidate) {
        this.owner.setPos(candidate.position().x, candidate.position().y, candidate.position().z);
        this.owner.setYRot(Mth.wrapDegrees(candidate.facing()));
        this.owner.setYBodyRot(this.owner.getYRot());
        this.owner.setYHeadRot(this.owner.getYRot());
        this.owner.setDeltaMovement(Vec3.ZERO);
        this.owner.noPhysics = false;
        this.owner.setNoGravity(false);
        this.owner.setInvisible(false);
        this.owner.setInvulnerable(false);
        this.lastManifestation = candidate;
        // Arriving counts as being seen: it must re-earn the next disappearance from scratch.
        this.observation.reset();
        enter(PresenceState.MANIFESTING);
    }

    /// The kind of the manifestation currently in progress, so the AI can chain the matching
    /// attack once the recognition window closes.
    public @Nullable ManifestKind consumePendingKind() {
        var kind = this.pendingKind;
        this.pendingKind = null;
        return kind;
    }

    /// Landing a hit restores information: the entity is pinned visible for a while and, if it was
    /// hiding, dragged back into the world.
    public void onDamaged(ServerLevel level, int pinTicks) {
        this.observation.pin(pinTicks);
        if (this.state == PresenceState.UNRESOLVED) {
            forceManifestation(level);
        } else if (this.state == PresenceState.DISSOLVING) {
            enter(PresenceState.RESOLVED);
        }
    }

    /// Return to a normal, definite, visible state — on spawn, on death, and whenever an encounter
    /// wants the mechanic switched off.
    public void reset() {
        this.owner.noPhysics = false;
        this.owner.setNoGravity(false);
        this.owner.setInvisible(false);
        this.owner.setInvulnerable(false);
        this.observation.reset();
        this.stare.clear();
        this.candidates = List.of();
        this.pendingKind = null;
        enter(PresenceState.RESOLVED);
    }

    private void enter(PresenceState next) {
        if (this.state != next) {
            this.state = next;
            this.stateTicks = 0;
        }
    }
}
