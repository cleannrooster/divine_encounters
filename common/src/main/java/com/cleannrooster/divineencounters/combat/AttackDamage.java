package com.cleannrooster.divineencounters.combat;

import com.cleannrooster.divineencounters.DivineEncounters;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/// Damage application shared by every attack: the armour-pierce trick and knockback, in one place so no
/// attack has to reimplement them.
public final class AttackDamage {
    private static final ResourceLocation ARMOR_PIERCE_ID = DivineEncounters.id("attack_armor_pierce");

    private AttackDamage() {
    }

    /// Hurt `victim` while temporarily ignoring `pierce` (0-1) of its armour. The modifier is added and
    /// removed around the single hurt call, so it can never leak onto a later hit.
    public static boolean hurt(LivingEntity victim, DamageSource source, float amount, float pierce) {
        var armor = victim.getAttribute(Attributes.ARMOR);
        var applied = armor != null && pierce > 0.0f;
        if (applied) {
            armor.addTransientModifier(new AttributeModifier(
                    ARMOR_PIERCE_ID, -pierce, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        try {
            return victim.hurt(source, amount);
        } finally {
            if (applied) {
                armor.removeModifier(ARMOR_PIERCE_ID);
            }
        }
    }

    /// Push `victim` away from `from`, with an optional vertical component. Falls back to the attacker's
    /// facing when the two are stacked exactly on top of each other.
    public static void knockback(LivingEntity victim, net.minecraft.world.phys.Vec3 from,
                                 net.minecraft.world.phys.Vec3 fallbackDirection,
                                 double horizontal, double vertical) {
        knockback(victim, from, fallbackDirection, horizontal, vertical, null, 0.0);
    }

    /// Knockback blended between "away from the source" and "along the direction the strike was
    /// travelling".
    ///
    /// Purely radial knockback is what a *hitbox* does — it pushes you off the thing that touched
    /// you, and it feels the same whether you were clipped by a spear or hit by a cleave at full
    /// swing. Real force has a direction of its own. Blending in the strike's travel vector means a
    /// horizontal sweep throws you sideways along the arc, a thrust drives you straight back, and a
    /// charge carries you the way it was going.
    ///
    /// The radial component is never removed entirely: it is what stops a victim being flung
    /// *through* the attacker, and it keeps the push legible as "that hit me" rather than as a
    /// scripted shove.
    ///
    /// @param travel  world-space direction the strike was moving, or null for purely radial
    /// @param blend   0 = fully radial, 1 = fully along `travel`
    public static void knockback(LivingEntity victim, net.minecraft.world.phys.Vec3 from,
                                 net.minecraft.world.phys.Vec3 fallbackDirection,
                                 double horizontal, double vertical,
                                 net.minecraft.world.phys.Vec3 travel, double blend) {
        if (horizontal <= 0.0 && vertical <= 0.0) {
            return;
        }
        var dx = victim.getX() - from.x;
        var dz = victim.getZ() - from.z;
        if (dx * dx + dz * dz < 1.0e-4) {
            dx = fallbackDirection.x;
            dz = fallbackDirection.z;
        }

        if (travel != null && blend > 0.0) {
            var travelLength = Math.sqrt(travel.x * travel.x + travel.z * travel.z);
            if (travelLength > 1.0e-4) {
                var radialLength = Math.sqrt(dx * dx + dz * dz);
                var mix = Mth.clamp(blend, 0.0, 1.0);
                dx = (dx / radialLength) * (1.0 - mix) + (travel.x / travelLength) * mix;
                dz = (dz / radialLength) * (1.0 - mix) + (travel.z / travelLength) * mix;
                // A near-perfect cancellation would otherwise leave a direction of almost zero
                // length, which vanilla's knockback normalises into an arbitrary heading.
                if (dx * dx + dz * dz < 1.0e-6) {
                    dx = travel.x / travelLength;
                    dz = travel.z / travelLength;
                }
            }
        }

        if (horizontal > 0.0) {
            // knockback() takes the direction *from* the victim toward the source, hence the negation.
            victim.knockback(horizontal, -dx, -dz);
        }
        if (vertical > 0.0) {
            victim.setDeltaMovement(victim.getDeltaMovement().add(0.0, vertical, 0.0));
            victim.hurtMarked = true;
        }
    }
}
