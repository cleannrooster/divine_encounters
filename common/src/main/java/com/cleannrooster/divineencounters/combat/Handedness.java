package com.cleannrooster.divineencounters.combat;

/// Which side of the attacker a swing originates from.
///
/// This is what stops a right-handed lance from drawing its arc out of the attacker's left shoulder. It
/// biases the attack origin sideways; the swing's start and end angles in {@link SwingPath} still decide
/// which way it travels.
public enum Handedness {
    /// Weapon held in the right hand — origin offset to the attacker's right.
    RIGHT(1.0),
    /// Weapon held in the left hand.
    LEFT(-1.0),
    /// Two-handed or centred: no lateral bias.
    CENTRED(0.0);

    private final double lateralSign;

    Handedness(double lateralSign) {
        this.lateralSign = lateralSign;
    }

    /// Multiplier applied to the definition's lateral origin offset. Authoring an attack with a positive
    /// lateral offset and `LEFT` handedness mirrors the origin without editing the number.
    public double lateralSign() {
        return this.lateralSign;
    }

    public Handedness opposite() {
        return switch (this) {
            case RIGHT -> LEFT;
            case LEFT -> RIGHT;
            case CENTRED -> CENTRED;
        };
    }
}
