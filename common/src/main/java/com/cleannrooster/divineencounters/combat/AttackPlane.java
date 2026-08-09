package com.cleannrooster.divineencounters.combat;

/// The plane a swing travels through, named after how it reads on screen.
///
/// The plane is a rotation about the attacker's facing vector — `planeRoll` in {@link SwingPath} — so
/// horizontal, diagonal and vertical arcs are the same maths at different angles. These constants exist
/// so definitions can say what they mean instead of carrying a bare number, and so the animation and the
/// rendered arc can be authored against the same vocabulary.
public enum AttackPlane {
    /// Flat cut across the front, in the plane containing the facing vector and the attacker's right.
    HORIZONTAL(0.0f),
    /// Shallow diagonal, high on one side and low on the other.
    DIAGONAL_SHALLOW(35.0f),
    /// Steep diagonal — the usual shape for a shoulder-to-hip cut.
    DIAGONAL_STEEP(68.0f),
    /// Straight up and down through the facing vector.
    VERTICAL(90.0f),
    /// The plane roll comes from the definition rather than the constant.
    CUSTOM(0.0f);

    private final float defaultRoll;

    AttackPlane(float defaultRoll) {
        this.defaultRoll = defaultRoll;
    }

    /// Rotation of the swing plane about the facing vector, in degrees.
    public float defaultRoll() {
        return this.defaultRoll;
    }
}
