package com.cleannrooster.divineencounters.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/// The world's reaction to a strike passing through it.
///
/// Everything else in the attack pipeline describes the attack. This describes what the attack does
/// to the room, and it is deliberately the least interesting effect on screen: a scatter of dust
/// lifted off whatever is underneath, thrown the way the force was going.
///
/// ### Why ground dust rather than a bespoke effect
///
/// The dust is sampled from the actual block beneath the strike, so it is grass in a field, sand in
/// a desert, stone in the arena. That does two things a hand-picked particle cannot. It reads as the
/// *world* responding rather than as more of the boss's own light show — which is the whole point of
/// "environmental" feedback — and it costs nothing to make it work for a boss with a completely
/// different palette, because it never had a palette of its own to clash with.
///
/// It also solves the specific problem of a hovering attacker. War's feet never touch the ground, so
/// nothing about her movement disturbs anything; a sweep that kicks dust off the floor a metre below
/// her is the only cue that the force went somewhere other than into the air.
///
/// ### Restraint
///
/// Scaled by the attack's own damage and skipped entirely for light ones, so this stays an accent on
/// heavy blows rather than a constant haze. It emits once per swing, on the first damaging tick, not
/// per tick and not per victim — the goal is to imply displacement, not to fill the arena.
public final class ForceFeedback {
    /// Attacks below this damage produce nothing. A jab should not shake the room.
    private static final float MIN_DAMAGE = 6.0f;
    /// Damage at which the effect is at full strength.
    private static final float FULL_DAMAGE = 16.0f;
    /// How far below the strike a floor can be and still be disturbed. Beyond this the force is
    /// happening in open air and there is nothing to kick up.
    private static final int MAX_DROP = 4;
    private static final int MIN_MOTES = 6, MAX_MOTES = 26;

    private ForceFeedback() {
    }

    /// Kick dust off the surface beneath a swing, thrown along the strike's own direction.
    ///
    /// @param origin the attack frame's origin — where the force is centred
    /// @param travel the direction the strike is moving; dust is thrown this way
    public static void displace(ServerLevel level, DirectionalAttack definition, Vec3 origin, Vec3 travel) {
        var intensity = Mth.clamp(
                (definition.damage() - MIN_DAMAGE) / (FULL_DAMAGE - MIN_DAMAGE), 0.0f, 1.0f);
        if (definition.damage() < MIN_DAMAGE) {
            return;
        }

        var floor = surfaceBelow(level, origin);
        if (floor == null) {
            return;
        }
        var state = level.getBlockState(floor);
        if (state.isAir()) {
            return;
        }

        // Centred a little way along the strike rather than under the attacker: the dust should mark
        // where the force went, not where it came from.
        var lateral = travel.multiply(1.0, 0.0, 1.0);
        var reach = definition.range() * 0.45;
        var centre = lateral.lengthSqr() < 1.0e-6
                ? origin
                : origin.add(lateral.normalize().scale(reach));

        var count = (int) Mth.lerp(intensity, MIN_MOTES, MAX_MOTES);
        var spread = definition.range() * 0.35;
        var speed = 0.12 + 0.22 * intensity;

        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                centre.x, floor.getY() + 1.05, centre.z,
                count, spread, 0.06, spread, speed);

        // A short, low puff of the strike's own direction on top of the dust, so the displacement
        // reads as directional rather than as a uniform cloud.
        if (intensity > 0.5f && lateral.lengthSqr() > 1.0e-6) {
            var push = lateral.normalize();
            level.sendParticles(ParticleTypes.POOF,
                    centre.x, floor.getY() + 1.15, centre.z, 0,
                    push.x * 0.6, 0.02, push.z * 0.6, 0.35);
        }
    }

    /// First solid surface within {@link #MAX_DROP} blocks below `origin`, or null if there is none.
    ///
    /// Scanned rather than read from the heightmap, for the same reason the Malice hover scan is: the
    /// heightmap reports the top of the column, which inside a roofed arena is the ceiling.
    private static BlockPos surfaceBelow(ServerLevel level, Vec3 origin) {
        var pos = BlockPos.containing(origin);
        for (var drop = 0; drop <= MAX_DROP; drop++) {
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return pos;
            }
            pos = pos.below();
            if (level.isOutsideBuildHeight(pos)) {
                return null;
            }
        }
        return null;
    }
}
