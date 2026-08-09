package com.cleannrooster.divineencounters.combat;

/// Lifecycle of a single attack execution. An attack always runs WINDUP -> ACTIVE -> RECOVERY -> FINISHED
/// in order; damage only ever resolves during ACTIVE.
public enum AttackPhase {
    /// Telegraph. No damage. The attacker may still be turning to track its target.
    WINDUP,
    /// The damaging window. Hit detection runs every tick here.
    ACTIVE,
    /// Commit punish window — no damage, and the owning AI should not start another attack.
    RECOVERY,
    /// Terminal state; the runner drops the execution on the next tick.
    FINISHED
}
