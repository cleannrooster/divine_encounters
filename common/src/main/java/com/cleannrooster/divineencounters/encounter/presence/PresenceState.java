package com.cleannrooster.divineencounters.encounter.presence;

/// How definite an entity's position currently is.
///
/// This is a first-class combat state, not a rendering trick. While `UNRESOLVED` the entity's
/// technical world position is *not* its canonical fictional location — it has none — and no
/// combat logic may read it as one.
///
/// ```
/// RESOLVED ──observation lost for the grace period──> DISSOLVING ──> UNRESOLVED
///    ^                                                                   │
///    └──────────────── MANIFESTING <── a resolution event ───────────────┘
/// ```
///
/// The asymmetry between the two transitions is deliberate. Dissolving is slow and conditional:
/// it has to be *earned* by genuinely breaking observation, so nobody can witness it happen.
/// Manifesting is fast but always preceded by a directional cue, so nobody is surprised by it.
public enum PresenceState {
    /// Definite position, ordinary physics, ordinary combat. Damageable and dangerous.
    RESOLVED(true, true),

    /// Briefly fading out. Still fully damageable — the window is short and exists so a player who
    /// spins round mid-transition sees a dissipation rather than an entity popping out of
    /// existence. Movement is winding down.
    DISSOLVING(true, true),

    /// No canonical position. Cannot hit and cannot be hit; a stale entity position must never be
    /// a valid target. Combat state (health, phase, cooldowns, target) is all retained.
    UNRESOLVED(false, false),

    /// Has just resolved at a chosen candidate and is becoming real again. Damageable, so the
    /// player can punish a read — but its attack has not started yet.
    MANIFESTING(true, true);

    private final boolean tangible;
    private final boolean vulnerable;

    PresenceState(boolean tangible, boolean vulnerable) {
        this.tangible = tangible;
        this.vulnerable = vulnerable;
    }

    /// Whether the entity physically exists at its position this tick — collision, contact damage,
    /// pathfinding, and attack origins all require this.
    public boolean isTangible() {
        return this.tangible;
    }

    /// Whether the entity can be damaged. Never true while unresolved: attacking where it used to
    /// be must not work.
    public boolean isVulnerable() {
        return this.vulnerable;
    }

    /// Whether the client should draw the entity at all.
    public boolean isRendered() {
        return this != UNRESOLVED;
    }

    /// Whether an attack may begin. The fairness contract in one method: damage can only ever
    /// originate from a definite position, so every attack traces back to a manifestation the
    /// player was warned about.
    public boolean allowsAttack() {
        return this == RESOLVED;
    }
}
