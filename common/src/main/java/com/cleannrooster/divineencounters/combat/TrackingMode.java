package com.cleannrooster.divineencounters.combat;

/// How much an attacker is allowed to re-aim at its target during an attack, expressed as a cap on body
/// yaw change per tick.
///
/// The point of the low settings is readability: once an attack commits, the player has to be able to
/// sidestep it. A boss that keeps turning at full speed through its own active frames is effectively
/// homing, which makes every telegraph meaningless.
public enum TrackingMode {
    /// Full pursuit turning — used before an attack commits.
    FULL(20.0f),
    /// Slow correction. Enough to look alive, not enough to follow a strafe.
    REDUCED(4.0f),
    /// Barely any correction — heavy attacks during their last windup ticks.
    MINIMAL(1.0f),
    /// Facing is frozen at whatever it was when this mode took effect.
    LOCKED(0.0f);

    private final float degreesPerTick;

    TrackingMode(float degreesPerTick) {
        this.degreesPerTick = degreesPerTick;
    }

    public float degreesPerTick() {
        return this.degreesPerTick;
    }
}
