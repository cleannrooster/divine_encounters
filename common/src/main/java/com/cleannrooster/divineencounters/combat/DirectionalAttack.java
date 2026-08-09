package com.cleannrooster.divineencounters.combat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.Function;

/// A complete, reusable description of one directional melee attack: its geometry, its damage, its
/// timing, how the attacker moves and turns while performing it, and how it looks and sounds.
///
/// This is the unit the whole combat system is built around. A boss does not implement "wing sweep" as a
/// block of entity code — it builds a `DirectionalAttack` once, registers it, and asks its
/// {@link AttackRunner} to execute it. {@link AttackExecution} then owns the timing, the hit detection,
/// the sounds, the slash visual and the particles for every attack in the game, so none of that is ever
/// written twice.
///
/// Definitions are immutable and shared. Anything that varies per swing (where it started, which way it
/// was aimed, which direction the arc travelled) lives in the {@link AttackFrame} captured at commit time.
///
/// ### Geometry
/// All shapes are evaluated by {@link AttackGeometry} using the same two parameters — `s`, progress
/// through the swing, and `t`, distance from the root of the blade to its tip. Damage, the rendered arc
/// and the particles all read from that one function, which is why the visible shape is the damaging
/// shape rather than an approximation of it.
///
/// The swing's orientation and direction of travel live in a {@link SwingPath}: which plane it cuts
/// through, which side it starts on, and which way it travels. That is what lets the rendered arc match
/// the weapon animation rather than being inferred from entity facing, and it is why horizontal,
/// vertical and diagonal arcs are one code path instead of three.
public final class DirectionalAttack {
    /// Damage source used when a definition doesn't ask for anything special.
    public static final Function<LivingEntity, DamageSource> MOB_ATTACK =
            attacker -> attacker.damageSources().mobAttack(attacker);

    private final ResourceLocation id;
    private final AttackShape shape;

    // --- geometry ---
    private final double range;
    private final double innerRadius;
    private final double width;
    private final double verticalExtent;
    private final SwingPath swing;
    private final double offsetForward;
    private final double offsetLateral;
    private final double offsetVertical;

    // --- damage ---
    private final float damage;
    private final double knockback;
    private final double knockbackVertical;
    private final float armorPierce;
    private final Function<LivingEntity, DamageSource> damageSource;
    private final boolean multiHit;
    private final int multiHitCooldown;

    // --- timing ---
    private final int windupTicks;
    private final int activeTicks;
    private final int recoveryTicks;

    // --- attacker motion ---
    private final double advanceDistance;
    private final double advanceLift;
    private final TrackingMode windupTracking;
    private final TrackingMode activeTracking;

    // --- presentation ---
    private final String animation;
    private final SoundProfile sounds;
    private final ParticleProfile particles;
    private final SlashProfile slash;
    private final AttackHooks hooks;

    private DirectionalAttack(Builder builder) {
        this.id = builder.id;
        this.shape = builder.shape;
        this.range = builder.range;
        this.innerRadius = builder.innerRadius;
        this.width = builder.width;
        this.verticalExtent = builder.verticalExtent;
        this.swing = builder.swing;
        this.offsetForward = builder.offsetForward;
        this.offsetLateral = builder.offsetLateral;
        this.offsetVertical = builder.offsetVertical;
        this.damage = builder.damage;
        this.knockback = builder.knockback;
        this.knockbackVertical = builder.knockbackVertical;
        this.armorPierce = builder.armorPierce;
        this.damageSource = builder.damageSource;
        this.multiHit = builder.multiHit;
        this.multiHitCooldown = builder.multiHitCooldown;
        this.windupTicks = builder.windupTicks;
        this.activeTicks = builder.activeTicks;
        this.recoveryTicks = builder.recoveryTicks;
        this.advanceDistance = builder.advanceDistance;
        this.advanceLift = builder.advanceLift;
        this.windupTracking = builder.windupTracking;
        this.activeTracking = builder.activeTracking;
        this.animation = builder.animation;
        this.sounds = builder.sounds;
        this.particles = builder.particles;
        this.slash = builder.slash;
        this.hooks = builder.hooks;
    }

    public static Builder builder(ResourceLocation id, AttackShape shape) {
        return new Builder(id, shape);
    }

    // --- accessors -------------------------------------------------------------------------------

    public ResourceLocation id() {
        return this.id;
    }

    public AttackShape shape() {
        return this.shape;
    }

    /// Outer reach, in blocks, measured from the attack origin.
    public double range() {
        return this.range;
    }

    /// Dead zone at the root of the blade — nothing inside this is damaged or drawn.
    public double innerRadius() {
        return this.innerRadius;
    }

    /// Full lane/path width for {@link AttackShape#THRUST_LANE} and {@link AttackShape#CHARGE_PATH}.
    public double width() {
        return this.width;
    }

    /// Full thickness of the damage volume perpendicular to the swing plane.
    public double verticalExtent() {
        return this.verticalExtent;
    }

    /// How the swing is oriented and which way it travels — the authority for both hit detection and the
    /// rendered arc.
    public SwingPath swing() {
        return this.swing;
    }

    /// Angular span of the arc family, in degrees.
    public float arcDegrees() {
        return this.swing.sweepDegrees();
    }

    /// Roll of the swing plane about the facing vector: 0 horizontal, 90 vertical.
    public float planeRoll() {
        return this.swing.planeRoll();
    }

    public double offsetForward() {
        return this.offsetForward;
    }

    public double offsetLateral() {
        return this.offsetLateral;
    }

    public double offsetVertical() {
        return this.offsetVertical;
    }

    public float damage() {
        return this.damage;
    }

    public double knockback() {
        return this.knockback;
    }

    public double knockbackVertical() {
        return this.knockbackVertical;
    }

    /// Fraction of the victim's armour ignored, 0-1.
    public float armorPierce() {
        return this.armorPierce;
    }

    public DamageSource damageSource(LivingEntity attacker) {
        return this.damageSource.apply(attacker);
    }

    /// False (the default) means each entity takes at most one hit per execution.
    public boolean multiHit() {
        return this.multiHit;
    }

    /// Ticks before a already-hit entity may be hit again; only meaningful when {@link #multiHit()}.
    public int multiHitCooldown() {
        return this.multiHitCooldown;
    }

    public int windupTicks() {
        return this.windupTicks;
    }

    public int activeTicks() {
        return this.activeTicks;
    }

    public int recoveryTicks() {
        return this.recoveryTicks;
    }

    public int totalTicks() {
        return this.windupTicks + this.activeTicks + this.recoveryTicks;
    }

    /// Blocks the attacker is carried forward across the active window.
    public double advanceDistance() {
        return this.advanceDistance;
    }

    /// Blocks the attacker rises during the windup — the little hop before a descending cut.
    public double advanceLift() {
        return this.advanceLift;
    }

    public TrackingMode windupTracking() {
        return this.windupTracking;
    }

    public TrackingMode activeTracking() {
        return this.activeTracking;
    }

    /// Animation clip name handed to the attacker's animation controller. Placeholder-friendly: the
    /// attack system never inspects it.
    public String animation() {
        return this.animation;
    }

    public SoundProfile sounds() {
        return this.sounds;
    }

    public ParticleProfile particles() {
        return this.particles;
    }

    public SlashProfile slash() {
        return this.slash;
    }

    public AttackHooks hooks() {
        return this.hooks;
    }

    @Override
    public String toString() {
        return "DirectionalAttack[" + this.id + " " + this.shape + "]";
    }

    // --- builder ---------------------------------------------------------------------------------

    public static final class Builder {
        private final ResourceLocation id;
        private final AttackShape shape;

        private double range = 4.0;
        private double innerRadius = 0.6;
        private double width = 2.0;
        private double verticalExtent = 2.0;
        private SwingPath swing;
        private double offsetForward;
        private double offsetLateral;
        private double offsetVertical = 1.0;

        private float damage = 6.0f;
        private double knockback = 0.4;
        private double knockbackVertical = 0.1;
        private float armorPierce;
        private Function<LivingEntity, DamageSource> damageSource = MOB_ATTACK;
        private boolean multiHit;
        private int multiHitCooldown = 10;

        private int windupTicks = 8;
        private int activeTicks = 4;
        private int recoveryTicks = 8;

        private double advanceDistance;
        private double advanceLift;
        private TrackingMode windupTracking = TrackingMode.REDUCED;
        private TrackingMode activeTracking = TrackingMode.LOCKED;

        private String animation = "idle";
        private SoundProfile sounds = SoundProfile.SILENT;
        private ParticleProfile particles;
        private SlashProfile slash = SlashProfile.builder().build();
        private AttackHooks hooks = AttackHooks.NONE;

        private Builder(ResourceLocation id, AttackShape shape) {
            this.id = id;
            this.shape = shape;
            // A sensible swing for the shape, so a definition only states its orientation when it cares.
            // Arc shapes default to travelling right-to-left through their natural plane.
            this.swing = switch (shape) {
                case HORIZONTAL_ARC -> SwingPath.rightToLeft(100.0f);
                case VERTICAL_ARC -> SwingPath.overhead(100.0f, AttackPlane.VERTICAL);
                case DIAGONAL_ARC -> SwingPath.overhead(100.0f, AttackPlane.DIAGONAL_STEEP);
                case FRONTAL_CONE, RADIAL_IMPACT -> SwingPath.fixedSpan(100.0f);
                case THRUST_LANE, CHARGE_PATH -> SwingPath.lane(0.0f);
            };
        }

        /// State the swing outright: its plane, which side the blade starts on, and which way it travels.
        /// Use this whenever the attack has an animation the arc has to agree with.
        public Builder swing(SwingPath swing) {
            this.swing = swing;
            return this;
        }

        /// Outer reach in blocks.
        public Builder range(double range) {
            this.range = range;
            return this;
        }

        /// Dead zone at the root of the blade.
        public Builder innerRadius(double innerRadius) {
            this.innerRadius = innerRadius;
            return this;
        }

        /// Full width for lane and path shapes.
        public Builder width(double width) {
            this.width = width;
            return this;
        }

        /// Rescale the current swing to a new angular span, keeping its plane and direction of travel.
        /// Shorthand for definitions happy with the shape's default direction.
        public Builder arc(float degrees) {
            var half = degrees * 0.5f;
            var sign = this.swing.travelsPositive() ? 1.0f : -1.0f;
            this.swing = new SwingPath(this.swing.plane(), -half * sign, half * sign,
                    this.swing.planeRoll(), this.swing.yawOffset(), this.swing.pitchOffset(),
                    this.swing.handedness());
            return this;
        }

        /// Full thickness perpendicular to the swing plane.
        public Builder verticalExtent(double extent) {
            this.verticalExtent = extent;
            return this;
        }

        /// Roll of the swing plane about the facing vector, in degrees.
        public Builder planeRoll(float degrees) {
            this.swing = new SwingPath(this.swing.plane(), this.swing.startAngle(), this.swing.endAngle(),
                    degrees, this.swing.yawOffset(), this.swing.pitchOffset(), this.swing.handedness());
            return this;
        }

        /// Where the attack originates relative to the attacker: forward along its facing, lateral to its
        /// right, and vertical from its feet.
        public Builder origin(double forward, double lateral, double vertical) {
            this.offsetForward = forward;
            this.offsetLateral = lateral;
            this.offsetVertical = vertical;
            return this;
        }

        public Builder damage(float damage) {
            this.damage = damage;
            return this;
        }

        public Builder knockback(double horizontal, double vertical) {
            this.knockback = horizontal;
            this.knockbackVertical = vertical;
            return this;
        }

        /// Fraction of the victim's armour to ignore, 0-1.
        public Builder armorPierce(float fraction) {
            this.armorPierce = fraction;
            return this;
        }

        public Builder damageSource(Function<LivingEntity, DamageSource> factory) {
            this.damageSource = factory;
            return this;
        }

        /// Allow the same entity to be hit repeatedly, no more often than `cooldown` ticks apart. Off by
        /// default: one execution, one hit per target.
        public Builder multiHit(int cooldown) {
            this.multiHit = true;
            this.multiHitCooldown = cooldown;
            return this;
        }

        public Builder timing(int windup, int active, int recovery) {
            this.windupTicks = windup;
            this.activeTicks = active;
            this.recoveryTicks = recovery;
            return this;
        }

        /// Blocks the attacker travels forward across the active window.
        public Builder advance(double blocks) {
            this.advanceDistance = blocks;
            return this;
        }

        /// Blocks the attacker rises across the windup.
        public Builder lift(double blocks) {
            this.advanceLift = blocks;
            return this;
        }

        public Builder tracking(TrackingMode windup, TrackingMode active) {
            this.windupTracking = windup;
            this.activeTracking = active;
            return this;
        }

        public Builder animation(String animation) {
            this.animation = animation;
            return this;
        }

        public Builder sounds(SoundProfile sounds) {
            this.sounds = sounds;
            return this;
        }

        public Builder particles(ParticleProfile particles) {
            this.particles = particles;
            return this;
        }

        public Builder slash(SlashProfile slash) {
            this.slash = slash;
            return this;
        }

        public Builder hooks(AttackHooks hooks) {
            this.hooks = hooks;
            return this;
        }

        public DirectionalAttack build() {
            if (this.particles == null) {
                this.particles = AttackVisuals.defaultParticles();
            }
            return new DirectionalAttack(this);
        }
    }
}
