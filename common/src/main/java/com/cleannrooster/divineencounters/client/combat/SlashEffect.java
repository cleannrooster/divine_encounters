package com.cleannrooster.divineencounters.client.combat;

import com.cleannrooster.divineencounters.combat.AttackFrame;
import com.cleannrooster.divineencounters.combat.DirectionalAttack;
import net.minecraft.util.Mth;

/// One slash currently playing on the client.
///
/// It holds no geometry of its own — just the definition, the frame it was swung from, and how far
/// through it is. Both the ribbon mesh and the particles are regenerated from
/// {@link com.cleannrooster.divineencounters.combat.AttackGeometry} every frame using exactly the numbers
/// the server used for hit detection, which is what keeps the drawn arc honest.
public final class SlashEffect {
    private final DirectionalAttack definition;
    private AttackFrame frame;
    private final int attackerId;
    private final int lifetime;

    private int age;

    SlashEffect(DirectionalAttack definition, AttackFrame frame, int attackerId) {
        this.definition = definition;
        this.frame = frame;
        this.attackerId = attackerId;
        this.lifetime = Math.max(1, definition.activeTicks()) + definition.slash().fadeOutTicks();
    }

    public DirectionalAttack definition() {
        return this.definition;
    }

    public AttackFrame frame() {
        return this.frame;
    }

    void setFrame(AttackFrame frame) {
        this.frame = frame;
    }

    public int attackerId() {
        return this.attackerId;
    }

    public int age() {
        return this.age;
    }

    void tick() {
        this.age++;
    }

    public boolean isExpired() {
        return this.age >= this.lifetime;
    }

    /// True while the corresponding server-side damage window is open.
    public boolean isSwinging(float partialTick) {
        return this.age + partialTick <= this.definition.activeTicks();
    }

    /// Leading edge of the swing, 0-1. Advancing this every frame is what animates the arc through its
    /// sweep instead of popping a finished crescent into existence.
    public float leadingEdge(float partialTick) {
        var active = Math.max(1, this.definition.activeTicks());
        return Mth.clamp((this.age + partialTick) / active, 0.0f, 1.0f);
    }

    /// Trailing edge of the visible ribbon — the leading edge minus the profile's trail length.
    public float trailingEdge(float partialTick) {
        if (!this.definition.shape().isSwept()) {
            return 0.0f; // cones and impacts show their whole span at once
        }
        return Math.max(0.0f, leadingEdge(partialTick) - this.definition.slash().trail());
    }

    /// Fade applied once the damaging window has closed, so the arc dissipates rather than vanishing.
    public float fade(float partialTick) {
        var fadeTicks = this.definition.slash().fadeOutTicks();
        var elapsed = this.age + partialTick - this.definition.activeTicks();
        if (elapsed <= 0.0f || fadeTicks <= 0) {
            return 1.0f;
        }
        return Mth.clamp(1.0f - elapsed / fadeTicks, 0.0f, 1.0f);
    }
}
