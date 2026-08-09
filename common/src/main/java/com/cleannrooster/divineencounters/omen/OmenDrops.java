package com.cleannrooster.divineencounters.omen;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.config.DivineConfig;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/// Omens that arrive by killing rather than by praying.
///
/// The shrine is the deliberate route: write something, mean it, receive an answer. This is the
/// other one — an omen that finds *you*, on the strength of what you have just done. The two kinds
/// of kill are chosen to match the two bosses rather than to be convenient:
///
/// - **War** answers a boss kill — anything in its source tag, or anything with at least
///   `war_boss_health_threshold` maximum health. Something has already been beaten, and War is the
///   question of how you beat something that does not stop.
/// - **Malice** answers killing a player or a baby animal. Both are spite rather than survival, and
///   Malice is what spite looks like once it has a face.
///
/// The Visage of War is excluded from its own trigger — winning that fight should not hand back a
/// ticket to repeat it. The Visage of Malice is *not* excluded, so beating one boss can arm you for
/// the other.
///
/// ### Rules that keep it from being a farm
///
/// A single cooldown, shared by both omens and keyed to the player rather than to the victim. Thirty
/// minutes of world time by default, so it cannot be shortened by logging out and it cannot be
/// bypassed by switching which kind of kill you make. An armed player is skipped entirely — the same
/// rule the shrine follows, since two racing bindings would produce an unreadable "which arena did I
/// get?".
///
/// Everything here is configurable, chances included, and a chance of zero disables that source.
public final class OmenDrops {
    /// Entity types whose death may arm an Omen of War, regardless of how much health they had.
    ///
    /// A tag rather than a hardcoded list, because "boss" is not a concept the game has and every
    /// modpack disagrees about what counts. It still earns its place alongside the health rule
    /// below: the elder guardian is unambiguously a boss and has 80 health, so no threshold worth
    /// setting would ever catch it.
    public static final TagKey<EntityType<?>> WAR_SOURCES =
            TagKey.create(Registries.ENTITY_TYPE, DivineEncounters.id("war_omen_sources"));

    /// Entity types that may never arm an Omen of War, whatever else qualifies them.
    ///
    /// This exists because of the health rule, and it is not optional. The Visage of War has 320
    /// health and the threshold defaults to 200, so the moment "anything big enough counts" was
    /// added, War began qualifying as a source for its own omen — quietly reversing the one
    /// exclusion the feature was specified with. Leaving it out of the source tag stopped being
    /// enough to express that, so the exclusion is now stated rather than implied.
    public static final TagKey<EntityType<?>> WAR_EXCLUDED =
            TagKey.create(Registries.ENTITY_TYPE, DivineEncounters.id("war_omen_excluded"));

    private static final String SECTION = "omens_from_kills";

    private static boolean enabled;
    private static double bossChance;
    private static double playerChance;
    private static double babyAnimalChance;
    private static double bossHealthThreshold;
    private static long cooldownTicks;

    private OmenDrops() {
    }

    /// Read the tunables and start listening. Called from mod init, after the config has loaded.
    public static void register() {
        enabled = DivineConfig.flag(SECTION, "enabled", true);
        bossChance = DivineConfig.setting(SECTION, "war_chance_from_boss", 0.05);
        playerChance = DivineConfig.setting(SECTION, "malice_chance_from_player", 0.05);
        babyAnimalChance = DivineConfig.setting(SECTION, "malice_chance_from_baby_animal", 0.05);
        // Anything this tough counts as a boss without needing to be named. Zero or less disables
        // the rule and leaves the tag as the only route — deliberately, because a threshold of zero
        // read as "every mob qualifies" would be a spectacular footgun in a config file.
        bossHealthThreshold = DivineConfig.setting(SECTION, "war_boss_health_threshold", 200.0);
        cooldownTicks = (long) (DivineConfig.setting(SECTION, "cooldown_minutes", 30.0) * 60.0 * 20.0);

        EntityEvent.LIVING_DEATH.register((entity, source) -> {
            onDeath(entity, source);
            // Never cancels the death. This only observes.
            return EventResult.pass();
        });
    }

    private static void onDeath(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source) {
        if (!enabled || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        if (!(source.getEntity() instanceof ServerPlayer killer)) {
            return;
        }
        var omen = omenFor(victim);
        if (omen == null) {
            return;
        }

        var data = OmenSavedData.get(level);
        // An armed player gains nothing, and — importantly — does not spend the cooldown either.
        if (data.hasBinding(killer.getUUID())) {
            return;
        }
        var now = level.getServer().overworld().getGameTime();
        if (data.killOmenRemaining(killer.getUUID(), now, cooldownTicks) > 0L) {
            return;
        }
        if (killer.getRandom().nextDouble() >= chanceFor(victim)) {
            return;
        }

        data.bind(killer.getUUID(), omen, now);
        data.markKillOmen(killer.getUUID(), now);

        killer.displayClientMessage(
                Component.translatable("omen.divine_encounters.drawn_by_kill")
                        .withStyle(ChatFormatting.DARK_PURPLE), false);
        killer.displayClientMessage(
                Component.translatable(omen.descriptionId() + ".bound")
                        .withStyle(ChatFormatting.DARK_PURPLE), false);
        level.playSound(null, killer.getX(), killer.getY(), killer.getZ(),
                net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.6f, 0.7f);
    }

    /// What kind of kill this was, if any.
    ///
    /// Both the omen and the chance are derived from this single answer. They used to re-derive the
    /// category independently, which is a standing invitation for the two to disagree the moment
    /// either gains a clause — and the health rule below is exactly such a clause.
    private enum Source { WAR_BOSS, PLAYER, BABY_ANIMAL }

    /// Order matters, and is not arbitrary.
    ///
    /// Players are checked first so that a modpack handing players hundreds of hearts cannot turn a
    /// PvP kill into an Omen of War — killing a player is spite whatever their health bar says, and
    /// spite is Malice's. The exclusion tag is checked before anything can qualify.
    private static @Nullable Source classify(LivingEntity victim) {
        if (victim instanceof Player) {
            return Source.PLAYER;
        }
        if (victim.getType().is(WAR_EXCLUDED)) {
            return null;
        }
        if (victim.getType().is(WAR_SOURCES) || isSubstantial(victim)) {
            return Source.WAR_BOSS;
        }
        return isBabyAnimal(victim) ? Source.BABY_ANIMAL : null;
    }

    /// Big enough to count as a boss on its own merits.
    ///
    /// Max health rather than current, so wearing something down does not change what it was.
    private static boolean isSubstantial(LivingEntity victim) {
        return bossHealthThreshold > 0.0 && victim.getMaxHealth() >= bossHealthThreshold;
    }

    private static @Nullable OmenType omenFor(LivingEntity victim) {
        var source = classify(victim);
        if (source == null) {
            return null;
        }
        return source == Source.WAR_BOSS ? DivineOmens.OMEN_OF_WAR : DivineOmens.OMEN_OF_MALICE;
    }

    private static double chanceFor(LivingEntity victim) {
        var source = classify(victim);
        if (source == null) {
            return 0.0;
        }
        return switch (source) {
            case WAR_BOSS -> bossChance;
            case PLAYER -> playerChance;
            case BABY_ANIMAL -> babyAnimalChance;
        };
    }

    /// Any ageable mob that is still a child — which covers vanilla animals and modded ones without
    /// naming any of them.
    private static boolean isBabyAnimal(LivingEntity victim) {
        return victim instanceof AgeableMob ageable && ageable.isBaby();
    }
}
