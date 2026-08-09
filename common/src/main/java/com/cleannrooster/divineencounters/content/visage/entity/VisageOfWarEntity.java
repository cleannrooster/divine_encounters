package com.cleannrooster.divineencounters.content.visage.entity;

import com.cleannrooster.divineencounters.combat.AttackAim;
import com.cleannrooster.divineencounters.combat.AttackRunner;
import com.cleannrooster.divineencounters.config.DivineConfig;
import com.cleannrooster.divineencounters.content.visage.ai.VisageController;
import com.cleannrooster.divineencounters.content.visage.ai.VisageState;
import com.cleannrooster.divineencounters.registry.ModSounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
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

/// The Visage of War — a hovering humanoid duellist built around a close-range one-on-one fight.
///
/// The entity itself is deliberately thin. Behaviour and movement live in {@link VisageController}; every
/// attack lives in {@link com.cleannrooster.divineencounters.content.visage.ai.VisageAttacks} and is
/// executed by the shared {@link AttackRunner}. What is left here is the physical contract: her synced
/// state, her flight, her boss bar, her phase flag, and her animation bindings.
///
/// Locomotion follows the Sculken Raven's approach — the state machine authors velocity directly and
/// vanilla pathfinding is never involved — but she is always airborne, which is what lets her skip
/// walking animations entirely and float instead.
public class VisageOfWarEntity extends Monster implements GeoEntity, AttackAim.Aiming {
    private static final VisageState[] STATES = VisageState.values();

    /// Slightly larger than a player, and narrow: she should read as a duellist you can circle, not a
    /// wall. Placeholder proportions — retune against the final model.
    private static final EntityDimensions DIMENSIONS = EntityDimensions.scalable(1.0f, 2.6f);

    // --- animation clip names (see visage_of_war.animation.json) ---------------------------------
    public static final String MOVEMENT_CONTROLLER = "movement";
    public static final String ACTION_CONTROLLER = "action";

    public static final String ANIM_THRUST = "thrust";
    public static final String ANIM_SWEEP = "sweep";
    public static final String ANIM_DESCENDING_CUT = "descending_cut";
    public static final String ANIM_HEAVY_CLEAVE = "heavy_cleave";
    public static final String ANIM_WIDE_CLEAVE = "wide_cleave";
    public static final String ANIM_SHOVE = "shove";
    public static final String ANIM_CHARGE = "charge";
    public static final String ANIM_BANK = "bank";
    public static final String ANIM_AERIAL_SWEEP = "aerial_sweep";
    public static final String ANIM_AERIAL_RISE = "aerial_rise";
    public static final String ANIM_AERIAL_DESCEND = "aerial_descend";
    public static final String ANIM_DIVINE_PURSUIT = "divine_pursuit";
    public static final String ANIM_RECOVER = "recover";

    private static final RawAnimation HOVER = loop("hover");
    private static final RawAnimation PURSUE = loop("pursue");
    private static final RawAnimation CHARGE_LOOP = loop(ANIM_CHARGE);
    private static final RawAnimation BANK_LOOP = loop(ANIM_BANK);
    private static final RawAnimation AERIAL_SWEEP_LOOP = loop(ANIM_AERIAL_SWEEP);
    private static final RawAnimation RISE_LOOP = loop(ANIM_AERIAL_RISE);
    private static final RawAnimation DESCEND_LOOP = loop(ANIM_AERIAL_DESCEND);
    private static final RawAnimation TRANSITION_LOOP = loop(ANIM_DIVINE_PURSUIT);
    private static final RawAnimation RECOVER_LOOP = loop(ANIM_RECOVER);

    private static final EntityDataAccessor<Byte> DATA_STATE =
            SynchedEntityData.defineId(VisageOfWarEntity.class, EntityDataSerializers.BYTE);
    /// Synced so the client can darken her boss bar and swap to the phase-two texture.
    private static final EntityDataAccessor<Boolean> DATA_DIVINE_PURSUIT =
            SynchedEntityData.defineId(VisageOfWarEntity.class, EntityDataSerializers.BOOLEAN);
    /// Roll, in degrees, so she visibly banks into a turn. Synced because it is pure presentation
    /// driven by a server-side decision — the controller knows which way the arc curves, the renderer
    /// does not.
    private static final EntityDataAccessor<Float> DATA_BANK =
            SynchedEntityData.defineId(VisageOfWarEntity.class, EntityDataSerializers.FLOAT);
    /// Body pitch held through aerial moves, independent of the aim pitch used by attacks.
    private static final EntityDataAccessor<Float> DATA_PITCH_OVERRIDE =
            SynchedEntityData.defineId(VisageOfWarEntity.class, EntityDataSerializers.FLOAT);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final VisageController controller = new VisageController(this);
    private final AttackRunner attacks = new AttackRunner(this)
            .animator(name -> this.triggerAnim(ACTION_CONTROLLER, name))
            // Attacks contribute velocity to the controller's steering rather than fighting it.
            .motionSink(motion -> this.controller.addAttackMotion(motion));

    private final ServerBossEvent bossEvent = (ServerBossEvent) new ServerBossEvent(
            this.getDisplayName(), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS)
            .setDarkenScreen(true);

    private int stateTimer;
    /// Client-side interpolation of the synced roll and pitch, so banking eases rather than steps.
    private float clientBank;
    private float clientBankPrevious;
    private float clientPitch;
    private float clientPitchPrevious;
    /// Which way she slides when a player body-blocks her. Flipped periodically so she can't be herded.
    private int slipDirection = 1;
    /// True once the transition has been triggered; prevents it re-firing as she keeps taking damage.
    private boolean divinePursuitStarted;

    public VisageOfWarEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.xpReward = 120;
        this.setNoGravity(true);
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
                .add(Attributes.MAX_HEALTH, DivineConfig.attribute("visage_of_war", "max_health", 320.0))
                .add(Attributes.ATTACK_DAMAGE, DivineConfig.attribute("visage_of_war", "attack_damage", 8.0))
                .add(Attributes.MOVEMENT_SPEED, DivineConfig.attribute("visage_of_war", "movement_speed", 0.3))
                // Near-total. She should acknowledge a hit without being moved by one — being visibly
                // shoved is the fastest way for something to read as light, and this whole pass is
                // about her mass being disproportionate to her size. Not quite 1.0: a sliver of give
                // is what keeps a landed hit feeling like contact rather than like hitting terrain.
                .add(Attributes.KNOCKBACK_RESISTANCE, DivineConfig.attribute("visage_of_war", "knockback_resistance", 0.96))
                .add(Attributes.FOLLOW_RANGE, DivineConfig.attribute("visage_of_war", "follow_range", 48.0))
                .add(Attributes.ARMOR, DivineConfig.attribute("visage_of_war", "armor", 10.0))
                // 1.6x. Set through the vanilla scale attribute rather than a render-only
                // override, because that is the one lever the whole game already respects: it
                // scales the model (GeckoLib applies getScale), the hitbox, and eye height
                // together. AttackGeometry reads the same value, so reach and arc origins grow
                // with the body and the visual-equals-damage invariant survives the change.
                .add(Attributes.SCALE, DivineConfig.attribute("visage_of_war", "scale", 1.6));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STATE, (byte) VisageState.PURSUIT.ordinal());
        builder.define(DATA_DIVINE_PURSUIT, false);
        builder.define(DATA_BANK, 0.0f);
        builder.define(DATA_PITCH_OVERRIDE, 0.0f);
    }

    /// Target selection only. Nothing here moves her — the controller is the sole authority over motion,
    /// exactly as with the Raven, so no vanilla goal can fight the state machine for the steering wheel.
    @Override
    protected void registerGoals() {
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // --- state -----------------------------------------------------------------------------------

    public VisageState getVisageState() {
        return STATES[this.entityData.get(DATA_STATE)];
    }

    public void setVisageState(VisageState state) {
        if (getVisageState() != state) {
            this.entityData.set(DATA_STATE, (byte) state.ordinal());
        }
    }

    public int stateTimer() {
        return this.stateTimer;
    }

    public void resetStateTimer() {
        this.stateTimer = 0;
    }

    public AttackRunner attacks() {
        return this.attacks;
    }

    public int slipDirection() {
        return this.slipDirection;
    }

    // --- banking ---------------------------------------------------------------------------------

    /// Server-side setter for the roll the controller wants her at. Only writes on a meaningful change,
    /// so a constant value costs no sync traffic.
    public void setBank(float degrees) {
        var clamped = Mth.clamp(degrees, -70.0f, 70.0f);
        // Snap the tail of a decay to zero. Without this the change threshold below stops updating while
        // a fraction of a degree of roll is still applied, and she stays permanently, subtly tilted.
        if (Math.abs(clamped) < 0.5f) {
            clamped = 0.0f;
        }
        if (clamped != getBank() && (clamped == 0.0f || Math.abs(clamped - getBank()) > 0.35f)) {
            this.entityData.set(DATA_BANK, clamped);
        }
    }

    public float getBank() {
        return this.entityData.get(DATA_BANK);
    }

    /// Body pitch for aerial moves — a nose-up climb, a nose-down dive. Separate from the aim pitch the
    /// attacks use so one can't fight the other.
    public void setPitchOverride(float degrees) {
        var clamped = Mth.clamp(degrees, -80.0f, 80.0f);
        if (Math.abs(clamped) < 0.5f) {
            clamped = 0.0f;
        }
        var current = this.entityData.get(DATA_PITCH_OVERRIDE);
        if (clamped != current && (clamped == 0.0f || Math.abs(clamped - current) > 0.35f)) {
            this.entityData.set(DATA_PITCH_OVERRIDE, clamped);
        }
    }

    /// Interpolated roll for the renderer.
    public float bankAngle(float partialTick) {
        return Mth.lerp(partialTick, this.clientBankPrevious, this.clientBank);
    }

    /// Interpolated body pitch for the renderer.
    public float bodyPitch(float partialTick) {
        return Mth.lerp(partialTick, this.clientPitchPrevious, this.clientPitch);
    }

    // --- Divine Pursuit --------------------------------------------------------------------------

    public boolean isDivinePursuit() {
        return this.entityData.get(DATA_DIVINE_PURSUIT);
    }

    /// The phase change fires once, when she first drops below the threshold.
    public boolean shouldEnterDivinePursuit() {
        return !this.divinePursuitStarted
                && this.getHealth() / this.getMaxHealth() <= VisageController.DIVINE_PURSUIT_THRESHOLD;
    }

    public void setDivinePursuitStarted() {
        this.divinePursuitStarted = true;
    }

    /// Activated at the end of the transition. Permanent: pursuit speed, cooldowns, recoveries and
    /// chaining all read this. Attack geometry and telegraph lengths deliberately do not.
    public void setDivinePursuitActive() {
        this.entityData.set(DATA_DIVINE_PURSUIT, true);
        this.bossEvent.setColor(BossEvent.BossBarColor.RED);
        if (this.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.END_ROD, this.getX(), this.getY() + 1.6, this.getZ(),
                    80, 1.2, 1.2, 1.2, 0.25);
        }
    }

    /// Multiplier applied to pursuit and charge speeds. The point of Divine Pursuit is more pressure, not
    /// faster animations, so this only touches movement.
    public float aggressionScale() {
        return isDivinePursuit() ? 1.28f : 1.0f;
    }

    /// Called from the Judgment Fall impact attack's hook, once the radial damage resolves.
    public void onJudgmentImpact() {
        if (this.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.EXPLOSION, this.getX(), this.getY() + 0.2, this.getZ(),
                    2, 0.4, 0.1, 0.4, 0.0);
            level.sendParticles(ParticleTypes.CLOUD, this.getX(), this.getY() + 0.1, this.getZ(),
                    30, 1.4, 0.15, 1.4, 0.06);
        }
    }

    // --- ticking ---------------------------------------------------------------------------------

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        this.stateTimer++;
        if (this.tickCount % 47 == 0) {
            this.slipDirection = this.random.nextBoolean() ? 1 : -1;
        }
        this.controller.serverTick();
        this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
    }

    @Override
    public void tick() {
        super.tick();
        // Yaw is synced but body rotation is not, so mirror it client-side; the GeoEntity renderer
        // follows body yaw. Same approach the Sculken Raven uses.
        if (this.level().isClientSide) {
            this.yBodyRotO = this.yBodyRot = this.getYRot();
            this.yHeadRotO = this.yHeadRot = this.getYRot();

            // Ease toward the synced roll/pitch. The sync is coarse by design; this is what makes the
            // bank read as a smooth lean into the arc rather than a series of steps.
            this.clientBankPrevious = this.clientBank;
            this.clientPitchPrevious = this.clientPitch;
            this.clientBank += (getBank() - this.clientBank) * 0.28f;
            this.clientPitch += (this.entityData.get(DATA_PITCH_OVERRIDE) - this.clientPitch) * 0.22f;
        } else {
            emitContainedEnergy();
        }
    }

    /// Sparse sparks shed while she holds position.
    ///
    /// The composure is the point of this boss, and composure is hard to read: a thing that is
    /// perfectly still and perfectly silent just looks idle. These exist so the stillness is legibly
    /// *held* rather than empty — a small, continuous leak that implies there is a great deal more
    /// being kept in.
    ///
    /// Deliberately rarer while she is committed to something. During an attack the attack's own
    /// effects say everything, and the contrast — quiet sparks between actions, nothing during them,
    /// then the arc — is what gives the stillness/force/stillness rhythm its shape.
    private void emitContainedEnergy() {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        var interval = this.getVisageState().isAttacking()
                ? CONTAINED_SPARK_INTERVAL * 3
                : CONTAINED_SPARK_INTERVAL;
        if (this.random.nextInt(interval) != 0) {
            return;
        }
        var width = this.getBbWidth() * 0.5;
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                this.getX(), this.getY() + this.getBbHeight() * 0.55, this.getZ(),
                1, width, this.getBbHeight() * 0.35, width, 0.0);
    }

    /// Average ticks between contained-energy sparks. High: this is a hint, not an aura.
    private static final int CONTAINED_SPARK_INTERVAL = 11;

    /// The controller authors velocity directly; vanilla gravity and friction never get a say.
    @Override
    public void travel(Vec3 travelVector) {
        if (!this.level().isClientSide && this.getVisageState().isSteered()) {
            this.move(MoverType.SELF, this.getDeltaMovement());
            return;
        }
        super.travel(travelVector);
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /// She glides past bodies instead of being shoved by them. Without this a player can pin a hovering
    /// boss against themselves and stall the whole fight.
    @Override
    protected void pushEntities() {
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
    }

    @Override
    protected EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        return DIMENSIONS;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
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

    @Override
    protected @Nullable SoundEvent getAmbientSound() {
        return ModSounds.VISAGE_IDLE.get();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.VISAGE_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.VISAGE_DEATH.get();
    }

    // --- persistence -----------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("DivinePursuitStarted", this.divinePursuitStarted);
        tag.putBoolean("DivinePursuit", isDivinePursuit());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.divinePursuitStarted = tag.getBoolean("DivinePursuitStarted");
        if (tag.getBoolean("DivinePursuit")) {
            this.entityData.set(DATA_DIVINE_PURSUIT, true);
            this.bossEvent.setColor(BossEvent.BossBarColor.RED);
        }
        // A reload always lands her back in the pursuit loop rather than mid-swing.
        setVisageState(VisageState.PURSUIT);
    }

    // --- GeckoLib --------------------------------------------------------------------------------

    /// Two controllers so locomotion and attacks can play at once:
    ///
    /// - `movement` loops from the synced {@link VisageState}. Because she floats, there is no walk cycle
    ///   here — hover and pursue are the whole locomotion set.
    /// - `action` holds every attack as a triggerable one-shot, fired by the attack runner when a swing
    ///   starts. That is the only coupling between the combat system and the animation library.
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, MOVEMENT_CONTROLLER, 8, this::movementAnimation));

        var action = new AnimationController<VisageOfWarEntity>(this, ACTION_CONTROLLER, 3,
                state -> PlayState.STOP);
        action.triggerableAnim(ANIM_THRUST, once(ANIM_THRUST))
                .triggerableAnim(ANIM_SWEEP, once(ANIM_SWEEP))
                .triggerableAnim(ANIM_DESCENDING_CUT, once(ANIM_DESCENDING_CUT))
                .triggerableAnim(ANIM_HEAVY_CLEAVE, once(ANIM_HEAVY_CLEAVE))
                .triggerableAnim(ANIM_WIDE_CLEAVE, once(ANIM_WIDE_CLEAVE))
                .triggerableAnim(ANIM_SHOVE, once(ANIM_SHOVE))
                .triggerableAnim(ANIM_CHARGE, once(ANIM_CHARGE))
                .triggerableAnim(ANIM_AERIAL_DESCEND, once(ANIM_AERIAL_DESCEND))
                .triggerableAnim(ANIM_RECOVER, once(ANIM_RECOVER));
        controllers.add(action);
    }

    private PlayState movementAnimation(AnimationState<VisageOfWarEntity> state) {
        return state.setAndContinue(switch (getVisageState()) {
            case PURSUIT -> isMovingHorizontally() ? PURSUE : HOVER;
            case REPOSITION -> BANK_LOOP;
            case AERIAL_REPOSITION -> AERIAL_SWEEP_LOOP;
            case ATTACK_WINDUP, ATTACK_ACTIVE -> HOVER;
            case ATTACK_RECOVERY, REACQUIRE -> RECOVER_LOOP;
            case CHARGE -> CHARGE_LOOP;
            case AERIAL_RISE -> RISE_LOOP;
            case AERIAL_DIVE -> DESCEND_LOOP;
            case DIVINE_PURSUIT_TRANSITION -> TRANSITION_LOOP;
        });
    }

    private boolean isMovingHorizontally() {
        var dx = this.getX() - this.xo;
        var dz = this.getZ() - this.zo;
        return dx * dx + dz * dz > 1.0e-4;
    }

    private static RawAnimation loop(String name) {
        return RawAnimation.begin().thenLoop("animation.visage_of_war." + name);
    }

    private static RawAnimation once(String name) {
        return RawAnimation.begin().thenPlay("animation.visage_of_war." + name);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
