package com.cleannrooster.divineencounters.content.malice.ai;

/// What the Visage of Malice is currently doing.
///
/// Deliberately *not* the same axis as
/// {@link com.cleannrooster.divineencounters.encounter.presence.PresenceState}. Presence answers
/// "does it have a position"; this answers "what is it doing with the position it has". They are
/// synced separately because they change independently — it can be stalking or recovering while
/// resolved, and only a couple of states are meaningful while it is not.
///
/// Where the Visage of War's states describe flight, these describe a ground predator: everything
/// here happens with feet on something.
public enum MaliceState {
    /// Circling at range, watching, waiting. The default resolved behaviour.
    STALK(true),
    /// Actively trying to leave the player's view — the move that earns it the right to dissolve.
    BREAK_CONTACT(true),
    /// Telegraph.
    ATTACK_WINDUP(true),
    /// The damaging window.
    ATTACK_ACTIVE(true),
    /// Commit punish window.
    ATTACK_RECOVERY(true),
    /// Mid-leap.
    POUNCE(true),
    /// Just manifested and about to strike — the recognition window between the tell and the swing.
    AMBUSH(true),
    /// Spatially unresolved. Nothing here has a location; the animation controller idles.
    HIDDEN(false),
    /// The one-time No Witness sequence.
    NO_WITNESS(true);

    private final boolean embodied;

    MaliceState(boolean embodied) {
        this.embodied = embodied;
    }

    /// Whether the boss occupies a real place in the world in this state. False only for
    /// {@link #HIDDEN}, where reading its position means nothing.
    public boolean isEmbodied() {
        return this.embodied;
    }

    public boolean isAttacking() {
        return this == ATTACK_WINDUP || this == ATTACK_ACTIVE || this == ATTACK_RECOVERY;
    }

    /// Whether the AI is free to choose a new action.
    public boolean isIdle() {
        return this == STALK;
    }

    /// Whether this state unlocks passing through natural tree trunks.
    ///
    /// Light vegetation is always passable and is not gated here — this is only about trunks, and
    /// deliberately only about states where something supernatural is *already* happening:
    ///
    /// - {@link #HIDDEN}, where it has no position for a tree to obstruct in the first place;
    /// - {@link #AMBUSH}, the window between manifesting and striking;
    /// - {@link #POUNCE}, a committed leap that must not be stopped halfway by scenery;
    /// - {@link #BREAK_CONTACT}, the deliberate move to leave the player's view — which a forest
    ///   should assist rather than obstruct;
    /// - {@link #NO_WITNESS}, which is nothing but supernatural repositioning.
    ///
    /// Ordinary stalking is excluded on purpose. A boss that walks through every tree it meets stops
    /// looking privileged and starts looking like it has no collision — and it also loses the read
    /// that trunks are cover *from being seen*, which is a thing the encounter genuinely wants. The
    /// attack states are excluded for fairness: see the damage guard in the entity.
    public boolean allowsTrunkPhasing() {
        return this == HIDDEN || this == AMBUSH || this == POUNCE
                || this == BREAK_CONTACT || this == NO_WITNESS;
    }
}
