package com.cleannrooster.divineencounters.omen;

import com.cleannrooster.divineencounters.content.malice.MalicePhasing;
import com.cleannrooster.divineencounters.content.malice.entity.VisageOfMaliceEntity;
import com.cleannrooster.divineencounters.encounter.ArenaRegistry;
import com.cleannrooster.divineencounters.encounter.EncounterManager;
import com.cleannrooster.divineencounters.encounter.EncounterSavedData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;

/// Operator commands for driving and inspecting encounters.
///
/// These exist because the natural path — bind an omen, then walk until the world accepts it — is
/// a poor iteration loop. `start` skips eligibility entirely so an arena can be tested on demand,
/// and `check` reports *why* a location was refused rather than just failing silently, which is the
/// difference between debugging site rules in minutes and in hours.
public final class OmenCommand {
    private OmenCommand() {
    }

    public static void register() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> build(dispatcher));
    }

    private static void build(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("divineencounters")
                .requires(source -> source.hasPermission(2))

                // Force an arena to build here, ignoring every site rule.
                .then(Commands.literal("arena")
                        .then(Commands.literal("start")
                                .then(Commands.argument("arena", StringArgumentType.string())
                                        .executes(context -> startArena(context.getSource(),
                                                StringArgumentType.getString(context, "arena")))))
                        .then(Commands.literal("stop")
                                .executes(context -> stopArena(context.getSource())))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource()))))

                // Bind an omen without needing the item.
                .then(Commands.literal("omen")
                        .then(Commands.literal("bind")
                                .then(Commands.argument("omen", StringArgumentType.string())
                                        .executes(context -> bind(context.getSource(),
                                                StringArgumentType.getString(context, "omen")))))
                        .then(Commands.literal("clear")
                                .executes(context -> clear(context.getSource())))
                        // Explain the current location against an omen's rules.
                        .then(Commands.literal("check")
                                .then(Commands.argument("omen", StringArgumentType.string())
                                        .executes(context -> check(context.getSource(),
                                                StringArgumentType.getString(context, "omen"))))))

                // Prayer tuning. The vocabulary is the part most likely to need iteration, and
                // guessing why a prayer scored the way it did is hopeless without this.
                .then(Commands.literal("prayer")
                        .then(Commands.literal("inspect")
                                .executes(context -> inspectPrayer(context.getSource())))
                        .then(Commands.literal("resetcooldown")
                                .executes(context -> resetPrayerCooldown(context.getSource()))))

                // Why is (or isn't) Malice going through that?
                .then(Commands.literal("phasing")
                        .executes(context -> inspectPhasing(context.getSource()))));
    }

    /// Report everything the vegetation-phasing rule depends on, at the caller's position.
    ///
    /// Written after two rounds of diagnosing this by inference, both wrong. The rule has four
    /// independent ways to fail silently and they need completely different fixes:
    ///
    /// 1. the tags did not load, or loaded empty — the predicate says no to everything;
    /// 2. the tags loaded but the block underfoot is not in them — a content gap, likely modded;
    /// 3. the tags and the block are fine but the boss's state does not grant trunk phasing —
    ///    working as designed, and the answer is "watch it during a pounce, not while it stalks";
    /// 4. everything above is fine and it still collides — then it is the collision path.
    ///
    /// Each line below distinguishes one of those. Reading it takes a few seconds; deducing it from
    /// in-game behaviour evidently does not work.
    private static int inspectPhasing(CommandSourceStack source) {
        var level = source.getLevel();
        var origin = net.minecraft.core.BlockPos.containing(source.getPosition());

        // 1. Did the tags load at all? A tag that failed to load is not an error at this point —
        //    it is simply empty, and every lookup against it quietly returns false.
        var registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BLOCK);
        report(source, "tag malice_phase_always      = "
                + registry.getTag(MalicePhasing.PHASE_ALWAYS).map(t -> t.size() + " blocks").orElse("MISSING"));
        report(source, "tag malice_phase_supernatural = "
                + registry.getTag(MalicePhasing.PHASE_SUPERNATURAL).map(t -> t.size() + " blocks").orElse("MISSING"));
        report(source, "tag malice_phase_denied      = "
                + registry.getTag(MalicePhasing.PHASE_DENIED).map(t -> t.size() + " blocks").orElse("MISSING"));

        // 2. Classify what is actually around the caller, so a content gap shows up as a named block.
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (var pos : net.minecraft.core.BlockPos.betweenClosed(
                origin.offset(-3, -1, -3), origin.offset(3, 4, 3))) {
            var state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            var label = MalicePhasing.phasesAlways(state) ? "always"
                    : MalicePhasing.phasesWhenSupernatural(state) ? "supernatural"
                    : state.is(MalicePhasing.PHASE_DENIED) ? "DENIED"
                    : "solid";
            var name = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock()).getPath();
            counts.merge(label + "  " + name, 1, Integer::sum);
        }
        report(source, "-- blocks within 3 of you --");
        counts.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> report(source, "  " + entry.getKey() + " x" + entry.getValue()));

        // 3. And what the nearest boss currently believes it is allowed to do.
        var malice = level.getEntitiesOfClass(VisageOfMaliceEntity.class,
                new net.minecraft.world.phys.AABB(origin).inflate(48.0));
        if (malice.isEmpty()) {
            report(source, "-- no Visage of Malice within 48 blocks --");
            return 1;
        }
        for (var boss : malice) {
            report(source, "-- malice at " + boss.blockPosition() + " --");
            report(source, "  state          = " + boss.getMaliceState()
                    + " (trunk phasing granted: " + boss.getMaliceState().allowsTrunkPhasing() + ")");
            report(source, "  presence       = " + boss.getPresenceState());
            report(source, "  phasesTrunks() = " + boss.phasesTrunks());
            report(source, "  embedded       = " + boss.isEmbeddedInTrunk());
            report(source, "  intersecting   = " + describeIntersecting(level, boss));
            report(source, "  movement       = " + boss.movementPathSummary());
            report(source, "  hover          = at y=" + String.format("%.2f", boss.getY())
                    + ", target y=" + (boss.getTarget() == null ? "none"
                            : String.format("%.2f", boss.getTarget().getY())));
        }
        return 1;
    }

    /// Every non-air block the boss's own box currently overlaps, with its phase classification.
    /// If this says "always leaves" and the boss is still being stopped, the collision path is at
    /// fault and nothing else is.
    private static String describeIntersecting(net.minecraft.server.level.ServerLevel level,
                                               VisageOfMaliceEntity boss) {
        var box = boss.getBoundingBox();
        var found = new java.util.LinkedHashSet<String>();
        for (var pos : net.minecraft.core.BlockPos.betweenClosed(
                net.minecraft.core.BlockPos.containing(box.minX, box.minY, box.minZ),
                net.minecraft.core.BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            var state = level.getBlockState(pos);
            if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            var label = MalicePhasing.canPhase(state, boss.phasesTrunks()) ? "PHASEABLE" : "solid";
            found.add(label + " " + net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock()).getPath());
        }
        return found.isEmpty() ? "nothing (it is in open air)" : String.join(", ", found);
    }

    private static void report(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static int startArena(CommandSourceStack source, String name) {
        var level = source.getLevel();
        var definition = ArenaRegistry.get(resolve(name));
        if (definition == null) {
            source.sendFailure(Component.literal("Unknown arena: " + name));
            return 0;
        }
        var pos = source.getPlayer() != null
                ? source.getPlayer().blockPosition() : net.minecraft.core.BlockPos.containing(source.getPosition());
        var surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        var origin = new net.minecraft.core.BlockPos(pos.getX() - 16, surface, pos.getZ() - 16);

        var encounter = EncounterManager.start(level, definition, origin);
        if (encounter == null) {
            source.sendFailure(Component.literal("Another encounter is already nearby."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Started " + definition.id() + " at " + origin), true);
        return 1;
    }

    private static int stopArena(CommandSourceStack source) {
        var level = source.getLevel();
        var pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        var encounter = EncounterManager.encounterAt(level, pos);
        if (encounter == null) {
            source.sendFailure(Component.literal("No encounter here."));
            return 0;
        }
        encounter.beginRetraction();
        source.sendSuccess(() -> Component.literal("Retracting " + encounter.definition().id()), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        var encounters = EncounterSavedData.get(source.getLevel()).encounters();
        if (encounters.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No live encounters."), false);
            return 0;
        }
        for (var encounter : encounters) {
            source.sendSuccess(() -> Component.literal("  " + encounter.definition().id()
                    + " " + encounter.state() + " at " + encounter.origin()), false);
        }
        return encounters.size();
    }

    private static int bind(CommandSourceStack source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        var omen = OmenType.get(resolve(name));
        if (omen == null) {
            source.sendFailure(Component.literal("Unknown omen: " + name));
            return 0;
        }
        OmenSavedData.get(source.getLevel())
                .bind(player.getUUID(), omen, source.getLevel().getGameTime());
        source.sendSuccess(() -> Component.literal("Bound " + omen.id()), true);
        return 1;
    }

    private static int clear(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        OmenSavedData.get(source.getLevel()).clear(player.getUUID());
        source.sendSuccess(() -> Component.literal("Cleared bound omen"), true);
        return 1;
    }

    /// The useful one. Reports the first rule that refused this spot, so site tuning is a
    /// conversation rather than guesswork.
    private static int check(CommandSourceStack source, String name) {
        var omen = OmenType.get(resolve(name));
        if (omen == null) {
            source.sendFailure(Component.literal("Unknown omen: " + name));
            return 0;
        }
        var level = source.getLevel();
        var pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        var surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        var centre = new net.minecraft.core.BlockPos(pos.getX(), surface, pos.getZ());
        var result = SiteEligibility.evaluate(level, centre, omen);
        source.sendSuccess(() -> Component.literal(omen.id() + " here: " + result), false);
        return result.isOk() ? 1 : 0;
    }

    /// Dump the full scoring of the nearest shrine's book: every term that fired, what it
    /// contributed after repetition decay, the totals, and the outcome. This is the tool for tuning
    /// the lexicon — the usual question is "why did that count for so little", and the answer is
    /// almost always visible in the occurrence column.
    private static int inspectPrayer(CommandSourceStack source) {
        var level = source.getLevel();
        var origin = net.minecraft.core.BlockPos.containing(source.getPosition());
        var shrine = com.cleannrooster.divineencounters.shrine.Shrine.near(level, origin, 6);
        if (shrine == null) {
            source.sendFailure(Component.literal(
                    "No shrine with a signed book within 6 blocks (lodestone + lectern)."));
            return 0;
        }
        var text = com.cleannrooster.divineencounters.shrine.PrayerText.bodyOf(shrine.book());
        var reading = com.cleannrooster.divineencounters.shrine.DivinePrayers.evaluator().evaluate(text);
        var outcome = com.cleannrooster.divineencounters.shrine.PrayerOutcomeResolver.resolve(reading);
        var axis = com.cleannrooster.divineencounters.shrine.DispositionAxis.SPIRIT;

        source.sendSuccess(() -> Component.literal(String.format(
                "%d words, %d matches", text.split("\s+").length, reading.matches().size())), false);
        for (var match : reading.matches()) {
            source.sendSuccess(() -> Component.literal(String.format("  %-22s #%d  %+.2f (of %+.2f)",
                    match.term().text(), match.occurrence(), match.contributed(),
                    match.term().weight())), false);
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "disposition %+.3f (need %.2f)  conviction %.2f (need %.2f)",
                reading.dispositionOn(axis),
                com.cleannrooster.divineencounters.shrine.DivinePrayers.DISPOSITION_THRESHOLD,
                reading.convictionOn(axis),
                com.cleannrooster.divineencounters.shrine.DivinePrayers.CONVICTION_THRESHOLD)), false);
        source.sendSuccess(() -> Component.literal(outcome.isAnswered()
                ? "-> " + outcome.omen().id()
                : "-> unanswered ("
                + com.cleannrooster.divineencounters.shrine.PrayerOutcomeResolver.failureReason(reading)
                + ")"), false);
        return 1;
    }

    private static int resetPrayerCooldown(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        com.cleannrooster.divineencounters.shrine.PrayerSavedData.get(source.getLevel())
                .clear(player.getUUID());
        source.sendSuccess(() -> Component.literal("Prayer cooldown cleared"), true);
        return 1;
    }

    /// Accept both `war` and `divine_encounters:war`.
    private static ResourceLocation resolve(String name) {
        var parsed = ResourceLocation.tryParse(name);
        if (parsed == null) {
            return com.cleannrooster.divineencounters.DivineEncounters.id(name);
        }
        return parsed.getNamespace().equals("minecraft") && !name.contains(":")
                ? com.cleannrooster.divineencounters.DivineEncounters.id(name)
                : parsed;
    }
}
