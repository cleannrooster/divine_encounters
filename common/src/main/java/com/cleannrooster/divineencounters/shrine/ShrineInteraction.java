package com.cleannrooster.divineencounters.shrine;

import com.cleannrooster.divineencounters.omen.OmenSavedData;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;

/// The shrine interaction: an empty-handed right-click on the lodestone submits the prayer above it.
///
/// Using the *lodestone* rather than the lectern is what keeps normal book reading intact — a
/// player can still click the lectern to read, turn pages, and take the book back, exactly as they
/// expect. Praying is a separate, deliberate gesture at the base of the shrine.
///
/// Requiring an empty hand means a player carrying a book, a map, or anything else is never
/// surprised into praying.
///
/// This class does validation, feedback and bookkeeping only. The moment it has text, it hands off
/// to the evaluator — none of the vocabulary, scoring or thresholds live here.
public final class ShrineInteraction {
    /// Whether a prayer the shrine could not resolve still costs the player their cooldown.
    ///
    /// Off, deliberately. Being told "your words were too divided" and then locked out for half an
    /// hour punishes the player for the vocabulary's limits rather than their own writing, and it
    /// makes the feature miserable to tune. Isolated here so the decision is one line to reverse.
    private static final boolean COOLDOWN_ON_UNANSWERED = false;

    private ShrineInteraction() {
    }

    public static void register() {
        InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            if (hand != InteractionHand.MAIN_HAND || player.level().isClientSide) {
                return EventResult.pass();
            }
            if (!(player instanceof ServerPlayer serverPlayer)
                    || !(player.level() instanceof ServerLevel level)) {
                return EventResult.pass();
            }
            // Anything in hand means the player is doing something else — placing, reading, using.
            if (!player.getItemInHand(hand).isEmpty()) {
                return EventResult.pass();
            }
            if (!Shrine.isShrineBase(level, pos)) {
                return EventResult.pass();
            }
            pray(level, serverPlayer, pos);
            // Consumed: a shrine base is not an ordinary lodestone once a lectern sits on it.
            return EventResult.interruptTrue();
        });
    }

    /// The full prayer sequence: validate, read, resolve, bind, report.
    public static void pray(ServerLevel level, ServerPlayer player, BlockPos lodestone) {
        var shrine = Shrine.at(level, lodestone);
        if (shrine == null) {
            // The structure is right but there is no signed book — the commonest mistake by far,
            // so it gets its own message rather than a generic refusal.
            reject(level, player, lodestone, "no_book");
            return;
        }

        var prayers = PrayerSavedData.get(level);
        var now = level.getServer().overworld().getGameTime();
        var remaining = prayers.remaining(player.getUUID(), now);
        if (remaining > 0L) {
            player.displayClientMessage(Component.translatable("shrine.divine_encounters.too_soon",
                    formatRemaining(remaining)).withStyle(ChatFormatting.GRAY), true);
            level.playSound(null, lodestone, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS,
                    0.6f, 1.4f);
            return;
        }

        // Existing omen rules own binding; the shrine never overwrites a carried omen.
        var omens = OmenSavedData.get(level);
        var existing = omens.bound(player.getUUID());
        if (existing != null) {
            player.displayClientMessage(Component.translatable(
                            "omen.divine_encounters.already_bound",
                            Component.translatable(existing.descriptionId()))
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        var reading = DivinePrayers.evaluator().evaluate(PrayerText.bodyOf(shrine.book()));
        var outcome = PrayerOutcomeResolver.resolve(reading);

        if (!outcome.isAnswered()) {
            reject(level, player, lodestone, PrayerOutcomeResolver.failureReason(reading));
            if (COOLDOWN_ON_UNANSWERED) {
                prayers.markPrayed(player.getUUID(), now);
            }
            return;
        }

        var omen = outcome.omen();
        omens.bind(player.getUUID(), omen, level.getGameTime());
        prayers.markPrayed(player.getUUID(), now);
        answer(level, player, lodestone, reading);
        player.displayClientMessage(Component.translatable(omen.descriptionId() + ".bound")
                .withStyle(ChatFormatting.DARK_PURPLE), false);
    }

    /// Success: name what the shrine found, without ever showing the player a number.
    private static void answer(ServerLevel level, ServerPlayer player, BlockPos lodestone,
                               PrayerReading reading) {
        var triumphant = reading.dispositionOn(DispositionAxis.SPIRIT) > 0.0f;
        player.displayClientMessage(Component.translatable(triumphant
                        ? "shrine.divine_encounters.found_triumph"
                        : "shrine.divine_encounters.found_malice")
                .withStyle(triumphant ? ChatFormatting.GOLD : ChatFormatting.DARK_PURPLE), false);

        var above = lodestone.above(2);
        level.sendParticles(triumphant ? ParticleTypes.END_ROD : ParticleTypes.SCULK_SOUL,
                above.getX() + 0.5, above.getY(), above.getZ() + 0.5, 60, 0.4, 0.8, 0.4, 0.06);
        level.playSound(null, lodestone,
                triumphant ? SoundEvents.BEACON_POWER_SELECT : SoundEvents.WARDEN_SONIC_CHARGE,
                SoundSource.BLOCKS, 1.2f, triumphant ? 1.2f : 0.7f);
    }

    /// Refusal, in the shrine's voice rather than an error's.
    private static void reject(ServerLevel level, ServerPlayer player, BlockPos lodestone,
                               String reason) {
        player.displayClientMessage(
                Component.translatable("shrine.divine_encounters.refused." + reason)
                        .withStyle(ChatFormatting.GRAY), true);
        level.playSound(null, lodestone, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS,
                0.5f, 0.8f);
        level.sendParticles(ParticleTypes.SMOKE, lodestone.getX() + 0.5, lodestone.getY() + 1.2,
                lodestone.getZ() + 0.5, 8, 0.3, 0.2, 0.3, 0.01);
    }

    private static Component formatRemaining(long ticks) {
        var seconds = ticks / 20L;
        var minutes = seconds / 60L;
        return Component.literal(minutes > 0 ? minutes + "m" : seconds + "s");
    }
}
