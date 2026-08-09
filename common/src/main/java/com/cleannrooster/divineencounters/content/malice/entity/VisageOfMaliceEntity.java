package com.cleannrooster.divineencounters.content.malice.entity;

import com.cleannrooster.divineencounters.combat.AttackAim;
import com.cleannrooster.divineencounters.combat.AttackRunner;
import com.cleannrooster.divineencounters.config.DivineConfig;
import com.cleannrooster.divineencounters.content.malice.MalicePhasing;
import com.cleannrooster.divineencounters.content.malice.PhaseCollision;
import com.cleannrooster.divineencounters.content.malice.ai.MaliceController;
import com.cleannrooster.divineencounters.content.malice.ai.MaliceState;
import com.cleannrooster.divineencounters.encounter.anchor.AnchorRegistry;
import com.cleannrooster.divineencounters.encounter.perception.GloomProfile;
import com.cleannrooster.divineencounters.encounter.presence.AnchorCandidateSource;
import com.cleannrooster.divineencounters.encounter.presence.CandidateResolver;
import com.cleannrooster.divineencounters.encounter.presence.PresenceCues;
import com.cleannrooster.divineencounters.encounter.presence.PresenceState;
import com.cleannrooster.divineencounters.encounter.presence.RingCandidateSource;
import com.cleannrooster.divineencounters.encounter.presence.SuperpositionController;
import com.cleannrooster.divineencounters.registry.ModParticles;
import com.cleannrooster.divineencounters.registry.ModSounds;
import com.cleannrooster.divineencounters.particle.TintedParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;

/// The Visage of Malice — a constructed satyr effigy that hunts by controlling what the player
/// knows.
///
/// The inverse of the Visage of War in every axis that matters. War dominates space by being
/// impossible to escape; Malice dominates space by becoming difficult to locate. War's privilege
/// is flight, and it is always exactly where you can see it. Malice's privilege is that when
/// nobody is looking, it does not have a location at all.
///
/// The entity is thin on purpose. Observation and spatial indeterminacy live in
/// {@link SuperpositionController}; behaviour lives in {@link MaliceController}; attacks are data
/// in {@code MaliceAttacks} executed by the shared {@link AttackRunner}. What is left here is the
/// physical contract: synced state, the boss bar, phase selection, and the overrides that make
/// "unresolved" mean something to the engine.
public class VisageOfMaliceEntity extends Monster implements GeoEntity, AttackAim.Aiming {
    private static final MaliceState[] STATES = MaliceState.values();
    private static final PresenceState[] PRESENCE = PresenceState.values();

    /// Tall and narrow — long limbs and high horns on a hollow frame. Reads as a silhouette.
    private static final EntityDimensions DIMENSIONS = EntityDimensions.scalable(0.9f, 2.9f);

    /// How long a landed hit forces it to stay findable. Successful aggression restores
    /// information — that is the player's reward for committing while it is real.
    private static final int HIT_REVEAL_TICKS = 24;

    /// How far it will roll into a glide. Enough to read as banking, not enough to look like it is
    /// falling over.
    private static final float MAX_BANK = 26.0f;
    /// How quickly the client eases toward the synced gaze and bank. Low is smooth; this is the
    /// number that makes the motion feel mechanical rather than muscular.
    private static final float PRESENTATION_EASE = 0.22f;
    /// Instability lost per tick after a hit.
    private static final float INSTABILITY_DECAY = 0.045f;

    /// How long Black Sweep deepens the local gloom. Long enough to matter, short enough that the
    /// player is never left navigating blind for an extended stretch.
    private static final int BLACK_SWEEP_GLOOM_TICKS = 140;

    // --- animation clip names (see visage_of_malice.animation.json) ------------------------------
    public static final String MOVEMENT_CONTROLLER = "movement";
    public static final String ACTION_CONTROLLER = "action";

    public static final String ANIM_STALK = "stalk";
    public static final String ANIM_CROUCH = "crouch";
    public static final String ANIM_HOOKING_SWIPE = "hooking_swipe";
    public static final String ANIM_HORN_RUSH = "horn_rush";
    public static final String ANIM_BACKHAND = "backhand";
    public static final String ANIM_LOW_POUNCE = "low_pounce";
    public static final String ANIM_NEEDLE_THRUST = "needle_thrust";
    public static final String ANIM_BACKBITE = "backbite";
    public static final String ANIM_BLACK_SWEEP = "black_sweep";
    public static final String ANIM_GRUDGE = "grudge";
    public static final String ANIM_CRESCENT = "crescent_of_spite";
    public static final String ANIM_PERCH = "perch";
    public static final String ANIM_POUNCE = "pounce";
    public static final String ANIM_MANIFEST = "manifest";
    public static final String ANIM_NO_WITNESS = "no_witness";
    public static final String ANIM_RECOVER = "recover";

    private static final RawAnimation STALK_LOOP = loop(ANIM_STALK);
    private static final RawAnimation CROUCH_LOOP = loop(ANIM_CROUCH);
    private static final RawAnimation PERCH_LOOP = loop(ANIM_PERCH);
    private static final RawAnimation POUNCE_LOOP = loop(ANIM_POUNCE);
    private static final RawAnimation RECOVER_LOOP = loop(ANIM_RECOVER);
    private static final RawAnimation NO_WITNESS_LOOP = loop(ANIM_NO_WITNESS);

    private static final EntityDataAccessor<Byte> DATA_STATE =
            SynchedEntityData.defineId(VisageOfMaliceEntity.class, EntityDataSerializers.BYTE);
    /// Synced so the client can suppress rendering outright while it has no position.
    private static final EntityDataAccessor<Byte> DATA_PRESENCE =
            SynchedEntityData.defineId(VisageOfMaliceEntity.class, EntityDataSerializers.BYTE);
    /// 0-2, the darkness phase. Drives the client's peripheral fade.
    private static final EntityDataAccessor<Byte> DATA_PHASE =
            SynchedEntityData.defineId(VisageOfMaliceEntity.class, EntityDataSerializers.BYTE);
    /// Where it is *looking*, independent of which way the body is turned.
    ///
    /// This is the gyroscope. The body is free to drift, bank and swivel beneath a head that stays
    /// locked on you — and that only works if gaze is its own channel rather than being derived
    /// from body yaw the way an ordinary mob's head is.
    private static final EntityDataAccessor<Float> DATA_GAZE_YAW =
            SynchedEntityData.defineId(VisageOfMaliceEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_GAZE_PITCH =
            SynchedEntityData.defineId(VisageOfMaliceEntity.class, EntityDataSerializers.FLOAT);
    /// Roll, in degrees. It banks into a glide like something mounted on a bearing, not something
    /// leaning to keep its balance.
    private static final EntityDataAccessor<Float> DATA_BANK =
            SynchedEntityData.defineId(VisageOfMaliceEntity.class, EntityDataSerializers.FLOAT);
    /// 0-1 manifestation instability, spiked by damage. Drives a render flicker instead of a
    /// flinch: it acknowledges the hit without appearing to feel it.
    private static final EntityDataAccessor<Float> DATA_INSTABILITY =
            SynchedEntityData.defineId(VisageOfMaliceEntity.class, EntityDataSerializers.FLOAT);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final SuperpositionController presence = new SuperpositionController(this,
            new CandidateResolver(new RingCandidateSource(), new AnchorCandidateSource()))
            // Only leaves. It can travel through a trunk; it may not resolve inside one.
            .passableBlocks(MalicePhasing::phasesAlways);
    private final MaliceController controller = new MaliceController(this);
    private final AttackRunner attacks = new AttackRunner(this)
            .animator(name -> this.triggerAnim(ACTION_CONTROLLER, name));

    private final ServerBossEvent bossEvent = (ServerBossEvent) new ServerBossEvent(
            this.getDisplayName(), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS)
            .setDarkenScreen(true);

    private int stateTimer;
    private boolean noWitnessTriggered;
    /// Client-side easing of the synced presentation channels, so gaze and bank glide rather than
    /// stepping once per network tick. The smoothness *is* the character here.
    private float clientBank, clientBankPrevious;
    private float clientGazeYaw, clientGazeYawPrevious;
    private float clientGazePitch, clientGazePitchPrevious;
    /// Ticks remaining of Black Sweep's temporary light suppression.
    private int gloomSurge;

    public VisageOfMaliceEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.xpReward = 130;
        // It hovers. Its legs are scenery, not propulsion.
        this.setNoGravity(true);
        this.presence.cues(new PresenceCues(
                ModSounds.MALICE_SCRAPE, ModSounds.MALICE_REVEAL,
                () -> new TintedParticleOptions(ModParticles.MALICE_MOTE.get(), 0.5f, 0.25f, 0.7f, 0.14f),
                () -> new TintedParticleOptions(ModParticles.MALICE_MOTE.get(), 0.8f, 0.4f, 0.95f, 0.24f),
                0.45f, 14));
    }


    // --- vertical aim ----------------------------------------------------------------------------
    /// Pitch committed attacks are thrown at, in degrees. Negative is up.
    ///
    /// A field of its own rather than the entity's `xRot`, because vanilla's `LookControl` resets
    /// `xRot` to zero every tick after custom AI runs — see {@link AttackAim}. Server-side only: it
    /// is read at the moment an attack commits and travels to clients inside the attack frame, so
    /// there is nothing to sync.
    private float attackPitch;

    @Override
    public float attackPitch() {
        return this.attackPitch;
    }

    /// Ease the aim toward a target's elevation, curved and capped by {@link AttackAim}.
    public void aimAt(@Nullable net.minecraft.world.entity.Entity target) {
        if (target == null) {
            this.attackPitch = AttackAim.ease(this.attackPitch, 0.0f, AIM_EASE);
            return;
        }
        this.attackPitch = AttackAim.ease(this.attackPitch,
                AttackAim.pitchToward(this, target), AIM_EASE);
    }

    /// How fast the aim tracks. Brisk enough to follow a player up a step, slow enough that a jump
    /// does not yank the whole boss's aim skyward for the two ticks the player is airborne.
    private static final float AIM_EASE = 0.35f;

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, DivineConfig.attribute("visage_of_malice", "max_health", 280.0))
                .add(Attributes.ATTACK_DAMAGE, DivineConfig.attribute("visage_of_malice", "attack_damage", 8.0))
                // Genuinely fast on foot: it has to be able to cross behind a turning player.
                .add(Attributes.MOVEMENT_SPEED, DivineConfig.attribute("visage_of_malice", "movement_speed", 0.42))
                // Total. Nothing shoves a concept, and being visibly pushed around is the single
                // most mortal thing a mob does.
                .add(Attributes.KNOCKBACK_RESISTANCE, DivineConfig.attribute("visage_of_malice", "knockback_resistance", 1.0))
                .add(Attributes.FOLLOW_RANGE, DivineConfig.attribute("visage_of_malice", "follow_range", 48.0))
                .add(Attributes.ARMOR, DivineConfig.attribute("visage_of_malice", "armor", 8.0))
                .add(Attributes.STEP_HEIGHT, DivineConfig.attribute("visage_of_malice", "step_height", 1.2))
                // 1.6x. Set through the vanilla scale attribute rather than a render-only
                // override, because that is the one lever the whole game already respects: it
                // scales the model (GeckoLib applies getScale), the hitbox, and eye height
                // together. AttackGeometry reads the same value, so reach and arc origins grow
                // with the body and the visual-equals-damage invariant survives the change.
                .add(Attributes.SCALE, DivineConfig.attribute("visage_of_malice", "scale", 1.25));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STATE, (byte) MaliceState.STALK.ordinal());
        builder.define(DATA_PRESENCE, (byte) PresenceState.RESOLVED.ordinal());
        builder.define(DATA_PHASE, (byte) 0);
        builder.define(DATA_GAZE_YAW, 0.0f);
        builder.define(DATA_GAZE_PITCH, 0.0f);
        builder.define(DATA_BANK, 0.0f);
        builder.define(DATA_INSTABILITY, 0.0f);
    }

    /// Target selection only — the controller is the sole authority over movement, so no vanilla
    /// goal can fight the state machine for the steering wheel.
    @Override
    protected void registerGoals() {
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // --- state -----------------------------------------------------------------------------------

    public MaliceState getMaliceState() {
        return STATES[this.entityData.get(DATA_STATE)];
    }

    public void setMaliceState(MaliceState state) {
        if (getMaliceState() != state) {
            this.entityData.set(DATA_STATE, (byte) state.ordinal());
        }
    }

    public PresenceState getPresenceState() {
        return PRESENCE[this.entityData.get(DATA_PRESENCE)];
    }

    public int getPhase() {
        return this.entityData.get(DATA_PHASE);
    }

    /// Aim the gaze. Called by the controller every tick it has a target; the body follows its own,
    /// much lazier rotation elsewhere.
    public void setGaze(float yaw, float pitch) {
        // Deadbanded: the controller recomputes the gaze every tick, and writing an entity data
        // accessor marks it dirty for every tracking player. Below a quarter-degree the client's
        // own easing covers the difference anyway.
        var wrapped = Mth.wrapDegrees(yaw);
        if (Math.abs(Mth.wrapDegrees(wrapped - gazeYaw())) > GAZE_SYNC_DEADBAND) {
            this.entityData.set(DATA_GAZE_YAW, wrapped);
        }
        var clampedPitch = Mth.clamp(pitch, -60.0f, 60.0f);
        if (Math.abs(clampedPitch - gazePitch()) > GAZE_SYNC_DEADBAND) {
            this.entityData.set(DATA_GAZE_PITCH, clampedPitch);
        }
    }

    private static final float GAZE_SYNC_DEADBAND = 0.25f;

    /// How far the head may lead the body before it stops following.
    ///
    /// Far past anatomical, which is the point — but not unbounded. The body turns at 4.5°/tick and
    /// the gaze at 42°/tick, so with no limit the head reaches a full 180° whenever the body is
    /// caught facing away, and the neck geometry visibly inverts through the torso. That reads as a
    /// broken rig rather than as a wrong creature, and a rig that looks broken gets no credit for
    /// being deliberate.
    private static final float MAX_GAZE_OFFSET = 150.0f;

    public float gazeYaw() {
        return this.entityData.get(DATA_GAZE_YAW);
    }

    public float gazePitch() {
        return this.entityData.get(DATA_GAZE_PITCH);
    }

    /// Interpolated gaze offset relative to the body, in degrees — what the head bone is rotated by.
    public float gazeOffsetYaw(float partialTick) {
        var gaze = Mth.rotLerp(partialTick, this.clientGazeYawPrevious, this.clientGazeYaw);
        var body = Mth.rotLerp(partialTick, this.yBodyRotO, this.yBodyRot);
        return Mth.clamp(Mth.wrapDegrees(gaze - body), -MAX_GAZE_OFFSET, MAX_GAZE_OFFSET);
    }

    public float gazeOffsetPitch(float partialTick) {
        return Mth.lerp(partialTick, this.clientGazePitchPrevious, this.clientGazePitch);
    }

    public void setBank(float degrees) {
        var clamped = Mth.clamp(degrees, -MAX_BANK, MAX_BANK);
        if (Math.abs(clamped - this.entityData.get(DATA_BANK)) > 0.05f) {
            this.entityData.set(DATA_BANK, clamped);
        }
    }

    public float bankAngle(float partialTick) {
        return Mth.lerp(partialTick, this.clientBankPrevious, this.clientBank);
    }

    /// 0-1. Spiked by damage, decaying steadily — the renderer reads it as a flicker.
    public float instability() {
        return this.entityData.get(DATA_INSTABILITY);
    }

    public SuperpositionController presence() {
        return this.presence;
    }

    public AttackRunner attacks() {
        return this.attacks;
    }

    public int stateTimer() {
        return this.stateTimer;
    }

    public void resetStateTimer() {
        this.stateTimer = 0;
    }

    public boolean noWitnessTriggered() {
        return this.noWitnessTriggered;
    }

    public void markNoWitnessTriggered() {
        this.noWitnessTriggered = true;
    }

    /// Hand the boss its arena. Without one it still fights — it simply loses the perch options,
    /// because {@link AnchorCandidateSource} contributes nothing from an empty registry.
    public void bindArena(AnchorRegistry anchors, @Nullable Vec3 hiddenAnchor) {
        this.presence.setAnchors(anchors);
        this.presence.setHiddenAnchor(hiddenAnchor);
    }

    /// The visibility rules for the current health phase. Darkness makes observation harder; it is
    /// never what relocates the boss. Those stay separate systems on purpose — a dark room does not
    /// move Malice, it only makes it easier for Malice to earn its own disappearance.
    public GloomProfile gloom() {
        if (getMaliceState() == MaliceState.NO_WITNESS) {
            return GloomProfile.NO_WITNESS;
        }
        var base = switch (getPhase()) {
            case 0 -> GloomProfile.STALKING;
            case 1 -> GloomProfile.DEEPENING;
            default -> GloomProfile.DEEP;
        };
        if (this.gloomSurge <= 0) {
            return base;
        }
        // A Black Sweep temporarily deepens the gloom by one step rather than blinding outright.
        return base == GloomProfile.STALKING ? GloomProfile.DEEPENING
                : base == GloomProfile.DEEPENING ? GloomProfile.DEEP : GloomProfile.NO_WITNESS;
    }

    /// Black Sweep's aftermath: the light drops locally for a few seconds.
    ///
    /// Temporary suppression only — no player-placed light is ever destroyed. Mechanically it is
    /// the gloom step above; the vanilla Darkness effect rides along purely as the visual, which is
    /// why its duration matches the surge rather than driving it.
    public void onBlackSweep() {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        this.gloomSurge = BLACK_SWEEP_GLOOM_TICKS;
        for (var player : participants()) {
            if (player.distanceToSqr(this) <= 20.0 * 20.0) {
                player.addEffect(new MobEffectInstance(MobEffects.DARKNESS,
                        BLACK_SWEEP_GLOOM_TICKS, 0, false, false, true));
            }
        }
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.SQUID_INK,
                this.getX(), this.getY() + 1.2, this.getZ(), 60, 3.0, 1.0, 3.0, 0.02);
        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                ModSounds.MALICE_DISSOLVE.get(), net.minecraft.sounds.SoundSource.HOSTILE, 1.4f, 0.6f);
    }

    /// Players eligible to observe. Anyone alive and not spectating within tracking range counts —
    /// in multiplayer, one person keeping eyes on it is enough to pin it.
    public List<Player> participants() {
        return this.level().getEntitiesOfClass(Player.class,
                this.getBoundingBox().inflate(64.0),
                player -> player.isAlive() && !player.isSpectator());
    }

    // --- ticking ---------------------------------------------------------------------------------

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        this.stateTimer++;
        if (this.gloomSurge > 0) {
            this.gloomSurge--;
        }
        updatePhase();
        this.controller.serverTick();

        // Mirror presence into synced data so the client can stop drawing it.
        var current = (byte) this.presence.state().ordinal();
        if (this.entityData.get(DATA_PRESENCE) != current) {
            this.entityData.set(DATA_PRESENCE, current);
        }
        this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
    }

    private void updatePhase() {
        var ratio = this.getHealth() / this.getMaxHealth();
        var phase = (byte) (ratio > 0.7f ? 0 : ratio > 0.4f ? 1 : 2);
        if (this.entityData.get(DATA_PHASE) != phase) {
            this.entityData.set(DATA_PHASE, phase);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            // Body rotation follows the synced yaw; the *head* deliberately does not — the renderer
            // rotates it from the gaze channel instead, which is what lets the face stay locked on
            // while the body swivels beneath it.
            this.yBodyRotO = this.yBodyRot;
            this.yBodyRot = this.getYRot();
            this.yHeadRotO = this.yHeadRot = this.getYRot();
            easePresentation();
        } else {
            if (instability() > 0.0f) {
                this.entityData.set(DATA_INSTABILITY, Math.max(0.0f, instability() - INSTABILITY_DECAY));
            }
            emitHoverMotes();
            tickPhasing();
        }
    }

    /// Maintain the phasing grace window, and mark whether the body is currently inside a trunk.
    ///
    /// Evaluated once per tick and cached, because both the damage guard and the grace window ask
    /// the same question and the answer involves scanning every block the boss overlaps.
    private void tickPhasing() {
        this.embeddedInTrunk = PhaseCollision.embeddedInPhaseable(this);
        emitFoliageMotes();
    }

    /// It does not suffocate.
    ///
    /// `LivingEntity#baseTick` deals a point of damage per tick to anything whose eye sits inside a
    /// suffocating block, and logs suffocate — so the moment trunk phasing was added, passing through
    /// a tree started slowly killing the boss. The traversal was working; the world was just charging
    /// it rent for being there.
    ///
    /// Suppressed outright rather than only while phasing, for two reasons. Suffocation is the most
    /// biological damage in the game — it models *not breathing*, and a great deal of work has gone
    /// into establishing that this thing does not breathe, does not flinch, and is not pushed. And a
    /// boss that can be damaged by being enclosed is a boss that can be trapped and killed by a
    /// player with a bucket of gravel, which is a fight nobody designed.
    ///
    /// This is the only hook needed: `isInWall` has no other consumer for a monster.
    @Override
    public boolean isInWall() {
        return false;
    }

    /// Movement-path counters, for `/divineencounters phasing`.
    ///
    /// The one thing the diagnostic could not previously answer: whether the filtered collision path
    /// is being taken at all, and whether it is still stopping the boss when it is. Everything else
    /// about the rule can be read from tags and state; this cannot be read from anywhere.
    private int phaseMoves;
    private int vanillaMoves;
    private boolean lastPhaseBlocked;

    public String movementPathSummary() {
        var summary = "phase-filtered " + this.phaseMoves + " / vanilla " + this.vanillaMoves
                + " moves (last filtered move was "
                + (this.lastPhaseBlocked ? "still obstructed" : "unobstructed") + ")";
        this.phaseMoves = 0;
        this.vanillaMoves = 0;
        return summary;
    }

    /// True while the boss's body overlaps a natural trunk. Refreshed once per server tick.
    private boolean embeddedInTrunk;

    /// Whether the boss is currently inside a trunk, and therefore not in a position to be fought.
    ///
    /// The fairness half of the phasing rule. Passing through a tree is a traversal privilege; it is
    /// not a firing position. A player cannot see, read or answer a strike that comes out of solid
    /// wood, so while this is true the boss does not attack — see the controller's attack gate.
    public boolean isEmbeddedInTrunk() {
        return this.embeddedInTrunk;
    }

    /// A faint disturbance where the boss meets the foliage.
    ///
    /// Sparse on purpose. The job is to say "this is passing through" rather than "this is clipping
    /// through a bug you found" — and one mote is enough to say it. Anything denser would read as an
    /// effect the boss is producing, when the intended read is that the forest is barely registering
    /// it at all. Nothing is broken and nothing is dropped.
    private void emitFoliageMotes() {
        if (!(this.level() instanceof ServerLevel level)
                || getPresenceState() == PresenceState.UNRESOLVED
                || this.random.nextInt(FOLIAGE_MOTE_INTERVAL) != 0) {
            return;
        }
        var touching = PhaseCollision.sampleIntersecting(this, phasesTrunks());
        if (touching == null) {
            return;
        }
        var soul = this.random.nextInt(3) == 0;
        level.sendParticles(
                soul ? net.minecraft.core.particles.ParticleTypes.SCULK_SOUL
                        : new TintedParticleOptions(ModParticles.MALICE_MOTE.get(),
                                0.58f, 0.28f, 0.82f, 0.16f),
                touching.getX() + 0.5, touching.getY() + 0.5, touching.getZ() + 0.5,
                1, 0.35, 0.35, 0.35, 0.0);
    }

    private static final int FOLIAGE_MOTE_INTERVAL = 4;

    /// The ambient drift beneath a hovering body.
    ///
    /// Deliberately sparse — a couple of motes a second, never a plume. A dense effect would read
    /// as a jet or an aura, i.e. as *propulsion*, and give the hover a mechanism. Sparse motes read
    /// instead as the thing shedding, which suggests it is being held up by something that has
    /// nothing to do with its body.
    ///
    /// They fall. Everything about Malice ignores gravity, so the one part of it that obeys gravity
    /// makes the rest look deliberate rather than unimplemented.
    ///
    /// Suppressed entirely while unresolved: in that state it is genuinely nowhere, and a trail of
    /// particles at its parked anchor would leak its position — the one thing the whole encounter
    /// is built to withhold.
    private void emitHoverMotes() {
        if (!(this.level() instanceof ServerLevel level)
                || getPresenceState() == PresenceState.UNRESOLVED
                || this.random.nextInt(HOVER_MOTE_INTERVAL) != 0) {
            return;
        }
        var soul = this.random.nextInt(3) == 0;
        var width = this.getBbWidth() * 0.45;
        level.sendParticles(
                soul ? net.minecraft.core.particles.ParticleTypes.SCULK_SOUL
                        : new TintedParticleOptions(ModParticles.MALICE_MOTE.get(),
                                0.62f, 0.30f, 0.86f, 0.20f),
                this.getX(), this.getY() + this.getBbHeight() * 0.35, this.getZ(),
                1, width, this.getBbHeight() * 0.3, width, 0.0);
    }

    /// Average ticks between ambient motes. High on purpose; see `emitHoverMotes`.
    private static final int HOVER_MOTE_INTERVAL = 9;

    /// Ease the synced presentation channels toward their targets. Doing this on the client, per
    /// frame-tick, is what turns a value that updates a few times a second into continuous motion.
    private void easePresentation() {
        this.clientBankPrevious = this.clientBank;
        this.clientGazeYawPrevious = this.clientGazeYaw;
        this.clientGazePitchPrevious = this.clientGazePitch;

        this.clientBank += (this.entityData.get(DATA_BANK) - this.clientBank) * PRESENTATION_EASE;
        this.clientGazeYaw += Mth.wrapDegrees(gazeYaw() - this.clientGazeYaw) * PRESENTATION_EASE;
        this.clientGazePitch += (gazePitch() - this.clientGazePitch) * PRESENTATION_EASE;
    }

    /// The controller authors velocity directly; gravity and friction never get a say. This is what
    /// makes the movement read as translation rather than locomotion.
    @Override
    public void travel(Vec3 travelVector) {
        if (!this.level().isClientSide) {
            this.move(MoverType.SELF, this.getDeltaMovement());
            return;
        }
        super.travel(travelVector);
    }

    // --- moving through the forest ----------------------------------------------------------------

    /// Whether trunk traversal is currently unlocked.
    ///
    /// The state gate decides when it *starts*; the embedded check decides when it stops. Without
    /// the second half, a pounce that ends with the boss halfway inside an oak would drop back to
    /// ordinary collision on the same tick it was overlapping a solid block — leaving it stuck, or
    /// being ejected by whatever resolves the overlap. Holding phasing open until it is clear means
    /// the traversal always finishes, and the switch back is a single clean transition rather than a
    /// per-tick fight with collision.
    public boolean phasesTrunks() {
        if (getMaliceState().allowsTrunkPhasing() || getPresenceState() == PresenceState.UNRESOLVED) {
            return true;
        }
        // Mid-exit: keep it on until the body is out.
        //
        // Deliberately not a countdown. A timed grace window looks safer and is not: if the boss is
        // still inside a trunk when the timer runs out, ordinary collision resumes around a body
        // that is inside a solid block, and it is stranded. Keying it on "am I actually inside one"
        // has no expiry to be stranded by, and cannot be exploited, because the only ways to be
        // inside a trunk are to have phased in legitimately or to have been buried in one.
        return this.embeddedInTrunk;
    }

    /// Collision, minus whatever this boss is currently allowed to ignore.
    ///
    /// Delegates straight to vanilla whenever nothing phaseable is nearby, which is the overwhelming
    /// majority of ticks — see {@link PhaseCollision#collide}.
    @Override
    public void move(MoverType type, Vec3 movement) {
        if (this.level().isClientSide || this.noPhysics || movement.lengthSqr() < 1.0e-12) {
            super.move(type, movement);
            return;
        }

        var resolved = PhaseCollision.collide(this, movement, phasesTrunks());
        if (resolved == null) {
            this.vanillaMoves++;
            super.move(type, movement);
            return;
        }
        this.phaseMoves++;
        this.lastPhaseBlocked = !Mth.equal(movement.x, resolved.x)
                || !Mth.equal(movement.y, resolved.y)
                || !Mth.equal(movement.z, resolved.z);

        // The vector is already collided, so `move` must not collide it again.
        //
        // Note this takes vanilla's noPhysics branch, which is a bare setPos — the block-entity
        // stepping and movement sounds `move` would otherwise do are skipped for this tick. That is
        // an acceptable trade for a hovering boss that has neither footsteps nor step-up, but it is
        // not the "everything else still runs" this comment used to claim.
        //
        // Saved and restored rather than assumed false: the presence system sets noPhysics while
        // unresolved, and hardcoding the restore would silently re-enable collision on a boss that
        // is meant to have none.
        var hadNoPhysics = this.noPhysics;
        this.noPhysics = true;
        try {
            super.move(type, resolved);
        } finally {
            this.noPhysics = hadNoPhysics;
        }
        // `move` derives these by comparing what it was given against what it produced, and with
        // noPhysics set those are the same vector. Restore them from the real comparison so anything
        // reading them sees the truth.
        this.horizontalCollision = !Mth.equal(movement.x, resolved.x) || !Mth.equal(movement.z, resolved.z);
        this.verticalCollision = !Mth.equal(movement.y, resolved.y);
    }

    // --- what "unresolved" means to the engine ---------------------------------------------------

    /// Attacking where it used to be must not work. This is the other half of the fairness
    /// contract: it cannot hurt you from nowhere, and you cannot hurt it in nowhere either.
    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Sources that bypass invulnerability must still land, or an unresolved boss becomes
        // unremovable: LivingEntity.kill() routes through hurt(), so /kill would silently do
        // nothing and a stuck encounter would need the world edited to clear it.
        if (!this.presence.state().isVulnerable()
                && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        var hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide && source.getEntity() instanceof Player
                && this.level() instanceof ServerLevel serverLevel) {
            // Landing a hit restores information — it is pinned findable for a moment. During No
            // Witness the pin is longer, because that is precisely when information is scarcest and
            // aggression most deserves to be rewarded.
            var reveal = getMaliceState() == MaliceState.NO_WITNESS
                    ? com.cleannrooster.divineencounters.content.malice.ai.NoWitnessSequence.HIT_REVEAL_TICKS
                    : HIT_REVEAL_TICKS;
            this.presence.onDamaged(serverLevel, reveal);
            // Instability instead of injury. A struck concept destabilises; it does not wince.
            this.entityData.set(DATA_INSTABILITY,
                    Math.min(1.0f, instability() + 0.55f));
        }
        if (hurt) {
            // Cancel the vanilla flinch outright. The red flash and the recoil animation are the
            // most mortal thing a mob does, and everything else in this pass is undone by them.
            this.hurtTime = 0;
            this.hurtDuration = 0;
        }
        return hurt;
    }

    /// Not clickable, not targetable, and not something a projectile can collide with while it has
    /// no position.
    @Override
    public boolean isPickable() {
        return this.presence.state().isTangible() && super.isPickable();
    }

    @Override
    public boolean canBeCollidedWith() {
        return this.presence.state().isTangible();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /// No ordinary hurt animation. `hurt()` already zeroes the timer; this stops anything else
    /// re-arming it.
    @Override
    public void animateHurt(float yaw) {
    }

    /// Hovering means never falling.
    @Override
    protected boolean isAffectedByFluids() {
        return false;
    }

    @Override
    protected void pushEntities() {
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        return DIMENSIONS;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void die(DamageSource source) {
        // Never leave a corpse in the hidden parking spot.
        this.presence.reset();
        super.die(source);
    }

    // --- boss bar --------------------------------------------------------------------------------

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        this.bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    @Override
    public void setCustomName(@Nullable Component name) {
        super.setCustomName(name);
        this.bossEvent.setName(this.getDisplayName());
    }

    // --- sounds ----------------------------------------------------------------------------------

    /// Its voice does not come out of its face.
    ///
    /// Vanilla plays `getAmbientSound()` at the entity, which is what makes a mob's noise feel like
    /// it is *coming from that mob* — a throat, in a place, some distance away. Returning null here
    /// and playing the sound ourselves in `playAmbientVoice` breaks the association: the idle voice
    /// is emitted at each listening player's own position instead, so it arrives with no direction
    /// and no falloff, closer than the body it belongs to.
    ///
    /// Hurt and death stay attached to the body. Those are the two moments the player has earned
    /// positional information, and taking it away would be unfair rather than unsettling.
    @Override
    protected @Nullable SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    public void baseTick() {
        super.baseTick();
        if (!this.level().isClientSide && this.presence.state().isTangible()
                && this.random.nextInt(AMBIENT_VOICE_INTERVAL) == 0) {
            playAmbientVoice();
        }
    }

    /// Play the idle voice at each nearby player rather than at the boss.
    ///
    /// Deliberately quiet and pitched low. It is not meant to be identified as a sound effect —
    /// only to make the room feel occupied.
    private void playAmbientVoice() {
        for (var player : participants()) {
            if (player.distanceToSqr(this) > VOICE_RANGE * VOICE_RANGE) {
                continue;
            }
            // At the listener's own position, so it has no direction to locate the boss by.
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    ModSounds.MALICE_IDLE.get(), net.minecraft.sounds.SoundSource.HOSTILE,
                    0.55f, 0.62f + this.random.nextFloat() * 0.1f);
        }
    }

    /// Average ticks between idle voices, and how far it reaches.
    private static final int AMBIENT_VOICE_INTERVAL = 190;
    private static final double VOICE_RANGE = 36.0;

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.MALICE_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.MALICE_DEATH.get();
    }

    // --- persistence -----------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("NoWitnessTriggered", this.noWitnessTriggered);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.noWitnessTriggered = tag.getBoolean("NoWitnessTriggered");
        // Always reload resolved and visible: a boss must never come back from a save still parked
        // in its hidden spot, which would leave it permanently intangible.
        this.presence.reset();
        setMaliceState(MaliceState.STALK);
    }

    // --- GeckoLib --------------------------------------------------------------------------------

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, MOVEMENT_CONTROLLER, 6, this::movementAnimation));

        var action = new AnimationController<VisageOfMaliceEntity>(this, ACTION_CONTROLLER, 3,
                state -> PlayState.STOP);
        for (var clip : new String[]{ANIM_HOOKING_SWIPE, ANIM_HORN_RUSH, ANIM_BACKHAND,
                ANIM_LOW_POUNCE, ANIM_NEEDLE_THRUST, ANIM_BACKBITE, ANIM_BLACK_SWEEP, ANIM_GRUDGE,
                ANIM_CRESCENT, ANIM_MANIFEST, ANIM_RECOVER}) {
            action.triggerableAnim(clip, once(clip));
        }
        controllers.add(action);
    }

    private PlayState movementAnimation(AnimationState<VisageOfMaliceEntity> state) {
        return state.setAndContinue(switch (getMaliceState()) {
            case STALK, BREAK_CONTACT -> STALK_LOOP;
            case ATTACK_WINDUP, AMBUSH -> CROUCH_LOOP;
            case ATTACK_ACTIVE -> STALK_LOOP;
            case ATTACK_RECOVERY -> RECOVER_LOOP;
            case POUNCE -> POUNCE_LOOP;
            // Nothing is drawn while hidden, but the controller still needs a valid clip.
            case HIDDEN -> PERCH_LOOP;
            case NO_WITNESS -> NO_WITNESS_LOOP;
        });
    }

    private static RawAnimation loop(String name) {
        return RawAnimation.begin().thenLoop("animation.visage_of_malice." + name);
    }

    private static RawAnimation once(String name) {
        return RawAnimation.begin().thenPlay("animation.visage_of_malice." + name);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
