package com.cleannrooster.divineencounters.content.visage.ai;

/// Authoritative behaviour states for the Visage of War.
///
/// Adapted from the Sculken Raven's `MoveState`: one synced enum is the single source of truth for what
/// the boss is doing, and each constant carries the physical contract it enforces. The client derives her
/// animation from the same value, so posture and motion can never disagree.
///
/// Unlike the Raven — which alternates between a grounded and an airborne posture — the Visage is always
/// flying, so every state is steered. That is deliberate: it means she never needs a walk cycle and never
/// fights vanilla pathfinding.
///
/// Repositioning is a first-class state rather than movement hidden inside an attack. That is what lets
/// the fight read as `pressure -> reposition -> attack` instead of one long approach.
public enum VisageState {
    /// Closing distance and holding pressure. The only state that may start a new action.
    PURSUIT(true),
    /// Curving around the target on a flat arc to a new attack angle. Deals no damage.
    REPOSITION(true),
    /// Sweeping up and over the target, ending above them. Deals no damage.
    AERIAL_REPOSITION(true),
    /// Telegraph. Movement is the attack's, not pursuit's; turning is capped by the attack definition.
    ATTACK_WINDUP(true),
    /// The damaging window. She is committed.
    ATTACK_ACTIVE(false),
    /// Commit punish window.
    ATTACK_RECOVERY(false),
    /// Travelling along a locked heading during Valkyrie's Passage.
    CHARGE(false),
    /// Climbing before Judgment Fall, still tracking her target.
    AERIAL_RISE(true),
    /// Falling onto the locked position. Nothing redirects her now.
    AERIAL_DIVE(false),
    /// Deliberate blind spot: after a pass or a heavy commit she takes a moment to find her target again.
    REACQUIRE(false),
    /// The one-time Divine Pursuit phase change.
    DIVINE_PURSUIT_TRANSITION(false);

    private final boolean tracksTarget;

    VisageState(boolean tracksTarget) {
        this.tracksTarget = tracksTarget;
    }

    /// Every state authors velocity directly; vanilla gravity and friction never get a say.
    public boolean isSteered() {
        return true;
    }

    /// True when she turns toward her target at more than a token rate.
    public boolean tracksTarget() {
        return this.tracksTarget;
    }

    /// True while an attack owns her movement and no new action may begin.
    public boolean isAttacking() {
        return this == ATTACK_WINDUP || this == ATTACK_ACTIVE || this == ATTACK_RECOVERY;
    }

    /// True while she is repositioning rather than attacking.
    public boolean isRepositioning() {
        return this == REPOSITION || this == AERIAL_REPOSITION;
    }

    /// True during states the AI must not interrupt.
    public boolean isBusy() {
        return this != PURSUIT;
    }

    /// States that hold the player's Y level as a hard floor. Attack and aerial states set their own
    /// desired height and are allowed to dip below it while committed.
    public boolean respectsCombatFloor() {
        return this != AERIAL_DIVE && this != DIVINE_PURSUIT_TRANSITION;
    }
}
