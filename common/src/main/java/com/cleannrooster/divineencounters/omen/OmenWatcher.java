package com.cleannrooster.divineencounters.omen;

import com.cleannrooster.divineencounters.encounter.EncounterManager;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/// Watches armed players and fires an arena when the world will accept one.
///
/// Throttled hard on purpose. Site evaluation samples a large footprint, and running that every
/// tick for every armed player would be a real cost for a check that almost always says no. Once a
/// second is far more often than a walking player can change their answer.
public final class OmenWatcher {
    /// Ticks between evaluations per player.
    private static final int CHECK_INTERVAL = 20;

    /// How long a binding lasts before it fades, in ticks. Read once, from config.
    ///
    /// An omen that never expires makes the shrine's own cooldown meaningless — bind one, put it in
    /// your back pocket, and the half-hour wait has been paid once forever. A lifetime turns a
    /// binding into something you have to act on.
    private static long lifetimeTicks = 60L * 60L * 20L;
    /// How long before the same refusal is reported again. Frequent enough to guide someone who is
    /// walking and looking for a site, rare enough not to nag.
    private static final int REPORT_INTERVAL = 200;

    /// The last refusal reported to each player, and when. Without this a bound omen is completely
    /// silent about why nothing is happening, which is indistinguishable from the feature being
    /// broken — and was exactly how it read in testing.
    private static final Map<UUID, SiteEligibility.Result> LAST_REPORT = new HashMap<>();
    private static final Map<UUID, Long> LAST_REPORT_TIME = new HashMap<>();

    private OmenWatcher() {
    }

    public static void register() {
        lifetimeTicks = (long) (com.cleannrooster.divineencounters.config.DivineConfig
                .setting("omens", "binding_lifetime_minutes", 60.0) * 60.0 * 20.0);
        TickEvent.SERVER_LEVEL_POST.register(OmenWatcher::tickLevel);
    }

    private static void tickLevel(ServerLevel level) {
        if (level.getGameTime() % CHECK_INTERVAL != 0) {
            return;
        }
        var data = OmenSavedData.get(level);
        for (var player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            var omen = data.bound(player.getUUID());
            if (omen == null) {
                continue;
            }
            if (expired(level, player, omen, data)) {
                continue;
            }
            consider(level, player, omen, data);
        }
    }

    /// Drop a binding that has outlived its hour, and say so.
    ///
    /// Announced rather than silent: a player who has been hunting for a site all this time needs to
    /// know why they can stop, and an omen that vanishes without comment is indistinguishable from
    /// the one bug this system has already produced once — a bound omen doing nothing, with no way
    /// to tell whether that was the rules or a fault.
    ///
    /// Checked here rather than in the store, so the expiry runs on the same throttle as everything
    /// else about a bound omen and cannot fire while nobody is looking.
    private static boolean expired(ServerLevel level, ServerPlayer player, OmenType omen,
                                   OmenSavedData data) {
        if (lifetimeTicks <= 0L) {
            return false;
        }
        var now = level.getGameTime();
        var boundAt = data.boundAt(player.getUUID());
        if (boundAt == 0L) {
            // Restored from a save made before omens expired; start its clock now.
            data.stamp(player.getUUID(), now);
            return false;
        }
        // A rolled-back world can leave a stamp in the future. Re-stamp rather than expire instantly.
        if (boundAt > now) {
            data.stamp(player.getUUID(), now);
            return false;
        }
        if (now - boundAt < lifetimeTicks) {
            return false;
        }
        data.clear(player.getUUID());
        LAST_REPORT.remove(player.getUUID());
        player.displayClientMessage(
                Component.translatable("omen.divine_encounters.faded",
                                Component.translatable(omen.descriptionId()))
                        .withStyle(ChatFormatting.DARK_GRAY), false);
        return true;
    }

    private static void consider(ServerLevel level, ServerPlayer player, OmenType omen,
                                 OmenSavedData data) {
        // Standing on the surface under open sky — an arena should not try to rise inside a cave.
        var centre = groundBelow(level, player.blockPosition());
        if (!level.canSeeSky(centre.above())) {
            return;
        }
        var result = SiteEligibility.evaluate(level, centre, omen);
        if (!result.isOk()) {
            report(level, player, result);
            return;
        }
        LAST_REPORT.remove(player.getUUID());
        var arena = omen.arena().get();
        var encounter = EncounterManager.start(level, arena, footprintOrigin(centre, arena, omen));
        if (encounter == null) {
            return;
        }
        // Consumed only once an arena has actually been created, so a refusal never costs the omen.
        data.clear(player.getUUID());
        player.displayClientMessage(Component.translatable(omen.descriptionId() + ".triggered")
                .withStyle(ChatFormatting.DARK_PURPLE), true);
    }

    /// Tell the player which rule refused this spot, so carrying an omen is a search with feedback
    /// rather than a guess. Repeats only when the reason changes or enough time has passed.
    private static void report(ServerLevel level, ServerPlayer player, SiteEligibility.Result result) {
        var id = player.getUUID();
        var now = level.getGameTime();
        var changed = LAST_REPORT.get(id) != result;
        var due = now - LAST_REPORT_TIME.getOrDefault(id, Long.MIN_VALUE) >= REPORT_INTERVAL;
        if (!changed && !due) {
            return;
        }
        LAST_REPORT.put(id, result);
        LAST_REPORT_TIME.put(id, now);
        player.displayClientMessage(Component.translatable(
                "omen.divine_encounters.refused." + result.name().toLowerCase(java.util.Locale.ROOT))
                .withStyle(ChatFormatting.DARK_GRAY), true);
    }

    private static BlockPos groundBelow(ServerLevel level, BlockPos pos) {
        var surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), surface, pos.getZ());
    }

    /// Templates are placed from their minimum corner, so centre the footprint on the player rather
    /// than starting it there — otherwise every arena would appear off to one side.
    private static BlockPos footprintOrigin(BlockPos centre,
                                            com.cleannrooster.divineencounters.encounter.ArenaDefinition arena,
                                            OmenType omen) {
        return centre.offset(-omen.footprint(), 0, -omen.footprint());
    }
}
