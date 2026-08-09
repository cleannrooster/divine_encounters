package com.cleannrooster.divineencounters.content.malice.ai;

import com.cleannrooster.divineencounters.combat.DirectionalAttack;
import com.cleannrooster.divineencounters.content.malice.entity.VisageOfMaliceEntity;
import com.cleannrooster.divineencounters.encounter.presence.ManifestKind;
import com.cleannrooster.divineencounters.registry.ModEntities;
import com.cleannrooster.divineencounters.registry.ModSounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/// The one-time No Witness sequence, triggered at 40% health.
///
/// It is not a new mechanic — it is the existing presence system turned up. Malice spends most of
/// the sequence unresolved, several candidates leak weak evidence at once, and it resolves rapidly
/// from varied angles to strike before dissolving again. Building it on the same machinery is what
/// keeps it fair: every strike still runs the full candidate → tell → manifest → windup → damage
/// chain, because that chain is enforced centrally rather than re-implemented here.
///
/// The one addition is a hard rule that makes aggression pay: **landing a hit pins Malice visible**
/// for a stretch, after which it has to earn its next disappearance normally. A player who fights
/// back gets information; a player who only hides gets none.
public final class NoWitnessSequence {
    /// Total duration. Long enough to feel like a distinct movement of the fight, short enough that
    /// the player is never left blind for an extended stretch.
    private static final int DURATION = 360;
    /// Ticks the player keeps Malice visible by landing a hit during the sequence.
    public static final int HIT_REVEAL_TICKS = 30;
    /// Delay between ambushes. Faster than normal, but never faster than the tell can be read.
    private static final int STRIKE_INTERVAL = 55;
    /// How often a decoy silhouette flickers at the arena edge.
    private static final int ECHO_INTERVAL = 40;

    /// The rotation of manifestation angles. Fixed rather than random so the sequence is
    /// predictable enough to learn and to test, while the *positions* within each category still
    /// vary with the arena and the player's facing.
    private static final ManifestKind[] ROTATION = {
            ManifestKind.FLANK,
            ManifestKind.REAR_AMBUSH,
            ManifestKind.PERCH,
            ManifestKind.FRONTAL_REVEAL,
    };

    private final VisageOfMaliceEntity malice;
    private int ticks;
    private int strikeTimer;
    private int rotationIndex;
    private boolean finished;

    public NoWitnessSequence(VisageOfMaliceEntity malice) {
        this.malice = malice;
    }

    public boolean isFinished() {
        return this.finished;
    }

    public void begin(ServerLevel level) {
        this.malice.setMaliceState(MaliceState.NO_WITNESS);
        this.malice.markNoWitnessTriggered();
        this.ticks = 0;
        this.strikeTimer = 30;
        this.rotationIndex = 0;
        level.playSound(null, this.malice.getX(), this.malice.getY(), this.malice.getZ(),
                ModSounds.MALICE_DISSOLVE.get(), SoundSource.HOSTILE, 1.6f, 0.55f);
        level.sendParticles(ParticleTypes.SCULK_SOUL, this.malice.getX(), this.malice.getY() + 1.4,
                this.malice.getZ(), 90, 2.5, 1.5, 2.5, 0.02);
    }

    /// Returns the attack to launch this tick, or null. The caller owns actually starting it, so
    /// the sequence never bypasses the normal attack path.
    public @Nullable DirectionalAttack tick(ServerLevel level, @Nullable Player target,
                                            RandomSource random) {
        if (this.finished) {
            return null;
        }
        if (++this.ticks >= DURATION || target == null) {
            end(level);
            return null;
        }
        var presence = this.malice.presence();

        if (this.ticks % ECHO_INTERVAL == 0) {
            spawnEcho(level, target, random);
        }

        // Being pinned by a landed hit suspends the sequence's aggression: it has to become
        // unresolved again before it can strike, which is the player's reward for connecting.
        if (presence.observation().isPinned()) {
            return null;
        }
        if (this.strikeTimer > 0) {
            this.strikeTimer--;
            return null;
        }
        if (!presence.isUnresolved()) {
            // Still visible — it wants to be hidden, and the ordinary break-contact behaviour is
            // what gets it there. Nothing here shortcuts that.
            return null;
        }

        // Walk the rotation until one of the categories can actually be satisfied.
        for (var attempt = 0; attempt < ROTATION.length; attempt++) {
            var kind = ROTATION[(this.rotationIndex + attempt) % ROTATION.length];
            var attack = attackFor(kind);
            if (attack == null || !presence.canManifest(kind)) {
                continue;
            }
            if (!presence.requestManifestation(level, kind)) {
                continue;
            }
            this.rotationIndex = (this.rotationIndex + attempt + 1) % ROTATION.length;
            this.strikeTimer = STRIKE_INTERVAL;
            return attack;
        }
        return null;
    }

    private static @Nullable DirectionalAttack attackFor(ManifestKind kind) {
        return switch (kind) {
            case FLANK -> MaliceAttacks.HORN_RUSH;
            case REAR_AMBUSH -> MaliceAttacks.BACKBITE;
            case PERCH -> MaliceAttacks.POUNCE_STRIKE;
            case FRONTAL_REVEAL -> MaliceAttacks.BLACK_SWEEP;
            default -> null;
        };
    }

    /// A decoy at the arena's edge — visible, brief, harmless. It never carries the strong cue, so
    /// it can mislead a careless player without ever being mistaken for a real commit.
    private void spawnEcho(ServerLevel level, Player target, RandomSource random) {
        var angle = random.nextDouble() * Math.PI * 2.0;
        var radius = 9.0 + random.nextDouble() * 4.0;
        var x = target.getX() + Math.cos(angle) * radius;
        var z = target.getZ() + Math.sin(angle) * radius;
        var y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(x), Mth.floor(z));

        var echo = ModEntities.MALICE_ECHO.get().create(level);
        if (echo == null) {
            return;
        }
        echo.setPos(x, y, z);
        var toTarget = new Vec3(target.getX() - x, 0.0, target.getZ() - z);
        echo.setYRot(toTarget.lengthSqr() < 1.0e-6 ? 0.0f
                : (float) (Mth.atan2(toTarget.z, toTarget.x) * (180.0 / Math.PI)) - 90.0f);
        level.addFreshEntity(echo);

        // A weak cue only — deliberately the ambient sound, never the reveal.
        level.playSound(null, x, y, z, ModSounds.MALICE_SCRAPE.get(), SoundSource.HOSTILE, 0.3f, 1.3f);
    }

    private void end(ServerLevel level) {
        this.finished = true;
        // Come back into the world rather than ending the sequence still hidden.
        this.malice.presence().forceManifestation(level);
        this.malice.setMaliceState(MaliceState.STALK);
        level.playSound(null, this.malice.getX(), this.malice.getY(), this.malice.getZ(),
                ModSounds.MALICE_REVEAL.get(), SoundSource.HOSTILE, 1.3f, 0.7f);
    }
}
