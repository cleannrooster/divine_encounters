package com.cleannrooster.divineencounters.combat.net;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.combat.AttackFrame;
import com.cleannrooster.divineencounters.combat.DirectionalAttack;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

/// Server-to-client dispatch for attack visuals.
///
/// One packet per swing, and it is small on purpose: the id of a definition both sides already hold, the
/// attacker's entity id, and the {@link AttackFrame}. Everything else — arc span, reach, blade density
/// curve, ribbon colour, particle type — is looked up locally from the shared registry. No helper
/// entities are spawned, and nothing about the attack's shape travels over the wire, so a 170-degree
/// cleave costs the same bandwidth as a shove.
public final class AttackNetwork {
    public static final ResourceLocation SWING = DivineEncounters.id("attack_swing");

    /// Players further than this from the attacker never see the swing, so a long fight doesn't spam the
    /// whole dimension. Comfortably beyond the reach of any attack plus normal render distance.
    private static final double BROADCAST_RANGE = 96.0;

    private AttackNetwork() {
    }

    /// Called from common init. On a dedicated server the payload type has to be declared explicitly; on
    /// a client, registering the receiver declares it as a side effect.
    @SuppressWarnings("removal")
    public static void register() {
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(SWING);
        }
    }

    /// Tell every nearby client to play this swing. Called once, when the attack's damaging window opens,
    /// so the visual and the hitbox start on the same tick.
    @SuppressWarnings("removal")
    public static void broadcastSwing(LivingEntity attacker, DirectionalAttack definition,
                                      AttackFrame frame) {
        if (!(attacker.level() instanceof ServerLevel level)) {
            return;
        }
        var audience = new ArrayList<ServerPlayer>();
        for (var player : level.players()) {
            if (player.distanceToSqr(attacker) <= BROADCAST_RANGE * BROADCAST_RANGE) {
                audience.add(player);
            }
        }
        if (audience.isEmpty()) {
            return;
        }
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        write(buffer, attacker.getId(), definition.id(), frame);
        NetworkManager.sendToPlayers(audience, SWING, buffer);
    }

    // --- wire format -----------------------------------------------------------------------------

    public static void write(RegistryFriendlyByteBuf buffer, int attackerId, ResourceLocation attackId,
                             AttackFrame frame) {
        buffer.writeResourceLocation(attackId);
        buffer.writeVarInt(attackerId);
        buffer.writeDouble(frame.origin().x);
        buffer.writeDouble(frame.origin().y);
        buffer.writeDouble(frame.origin().z);
        buffer.writeFloat(frame.yaw());
        buffer.writeFloat(frame.pitch());
        buffer.writeFloat(frame.rollOffset());
        buffer.writeBoolean(frame.mirrored());
        buffer.writeFloat(frame.scale());
    }

    /// Decoded swing, as it arrives on the client.
    public record Swing(ResourceLocation attackId, int attackerId, AttackFrame frame) {
    }

    public static Swing read(RegistryFriendlyByteBuf buffer) {
        var attackId = buffer.readResourceLocation();
        var attackerId = buffer.readVarInt();
        var origin = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        var yaw = buffer.readFloat();
        var pitch = buffer.readFloat();
        var roll = buffer.readFloat();
        var mirrored = buffer.readBoolean();
        var scale = buffer.readFloat();
        return new Swing(attackId, attackerId, new AttackFrame(origin, yaw, pitch, roll, mirrored, scale));
    }
}
