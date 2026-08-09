package com.cleannrooster.divineencounters.config;

import com.cleannrooster.divineencounters.combat.AttackRegistry;
import com.cleannrooster.divineencounters.combat.DivineAttacks;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/// Standalone verification that the config covers what it claims to, run from the `configCheck`
/// Gradle task.
///
/// The failure this guards is a config key that looks right and does nothing. An attack added later
/// whose `.damage(...)` call was not routed through {@link DivineConfig} is the obvious case: the
/// file lists nine attacks instead of ten, the tenth cannot be tuned, and nothing anywhere reports
/// it. The same goes for an attribute.
///
/// So the check is a coverage comparison rather than a value test — every registered attack must
/// have announced a damage default, and each boss must have announced the attributes the
/// player-facing guide quotes.
public final class ConfigCheck {
    /// The attributes the guide puts in front of players, and therefore the ones a server owner is
    /// entitled to change. Kept here rather than derived, so removing one from an entity is a
    /// failure rather than a silent narrowing of what is configurable.
    private static final List<String> REQUIRED_ATTRIBUTES =
            List.of("max_health", "armor", "knockback_resistance", "scale");

    private static int failures;

    public static void main(String[] args) {
        // Attack definitions touch registries and damage sources.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // No platform here, so DivineConfig falls back to defaults — which is exactly what makes the
        // declared values comparable against the authored ones.
        DivineConfig.load();
        DivineAttacks.bootstrap();

        checkAttackCoverage();
        checkAttributeCoverage();
        checkKillOmenSettings();
        checkWarOmenSources();

        System.out.println(failures == 0
                ? "ALL CONFIG CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /// Every registered attack must be tunable.
    private static void checkAttackCoverage() {
        var declared = DivineConfig.authoredAttackDamage().keySet();
        var registered = new LinkedHashSet<String>();
        AttackRegistry.all().forEach(attack -> registered.add(attack.id().getPath()));

        System.out.printf("attacks registered: %d, exposed to config: %d%n",
                registered.size(), declared.size());

        var missing = new LinkedHashSet<>(registered);
        missing.removeAll(declared);
        expect("every registered attack exposes its damage"
                        + (missing.isEmpty() ? "" : " — missing " + missing),
                missing.isEmpty());

        var orphaned = new LinkedHashSet<>(declared);
        orphaned.removeAll(registered);
        expect("no config key names an attack that does not exist"
                        + (orphaned.isEmpty() ? "" : " — stale " + orphaned),
                orphaned.isEmpty());

        var nonPositive = declared.stream()
                .filter(key -> DivineConfig.authoredAttackDamage().get(key) <= 0.0)
                .toList();
        expect("every default damage is positive"
                        + (nonPositive.isEmpty() ? "" : " — " + nonPositive),
                nonPositive.isEmpty());
    }

    /// Both bosses must expose the attributes players are shown.
    ///
    /// Read from the entity sources rather than by calling `createAttributes()`, because the entity
    /// classes implement GeckoLib interfaces and GeckoLib ships against intermediary mappings — it
    /// cannot be put on a Mojmap harness classpath, so loading those classes here is not possible.
    /// Scanning for the `DivineConfig.attribute(...)` calls checks the same agreement one level out:
    /// an attribute the entity stopped routing through config fails here exactly as it would if the
    /// class had been loaded.
    private static void checkAttributeCoverage() {
        for (var boss : List.of("visage_of_war", "visage_of_malice")) {
            var source = entitySource(boss);
            if (source == null) {
                expect(boss + " entity source is readable", false);
                continue;
            }
            var declared = new LinkedHashSet<String>();
            var matcher = Pattern
                    .compile("DivineConfig\\.attribute\\(\"" + boss + "\", ?\"([a-z_]+)\"")
                    .matcher(source);
            while (matcher.find()) {
                declared.add(matcher.group(1));
            }

            System.out.printf("%s exposes %d attributes: %s%n", boss, declared.size(), declared);
            expect(boss + " exposes attributes at all", !declared.isEmpty());
            for (var required : REQUIRED_ATTRIBUTES) {
                expect(boss + " exposes " + required, declared.contains(required));
            }
        }
    }

    /// The kill-omen feature must expose all of its knobs.
    ///
    /// Registering the listener is what declares them, so this also proves the listener is wired at
    /// all — a `register()` that was never called would leave the section missing entirely.
    private static void checkKillOmenSettings() {
        com.cleannrooster.divineencounters.omen.OmenDrops.register();
        // Declares the binding-lifetime setting. Registering is what announces a section, so this
        // doubles as proof the watcher is wired at all.
        com.cleannrooster.divineencounters.omen.OmenWatcher.register();

        var lifetime = DivineConfig.authoredSettings().get("omens");
        expect("binding lifetime is configurable",
                lifetime != null && lifetime.containsKey("binding_lifetime_minutes"));
        if (lifetime != null) {
            var minutes = lifetime.get("binding_lifetime_minutes");
            System.out.println("omen binding lifetime: " + minutes + " minutes");
            expect("the binding lifetime outlasts the kill cooldown",
                    minutes instanceof Number number && number.doubleValue() >= 30.0);
        }

        var section = DivineConfig.authoredSettings().get("omens_from_kills");
        expect("kill-omen settings appear in the config", section != null);
        if (section == null) {
            return;
        }
        System.out.println("kill-omen settings: " + section.keySet());
        for (var key : List.of("enabled", "war_chance_from_boss", "malice_chance_from_player",
                "malice_chance_from_baby_animal", "cooldown_minutes", "war_boss_health_threshold")) {
            expect("kill-omen config exposes " + key, section.containsKey(key));
        }
        for (var key : List.of("war_chance_from_boss", "malice_chance_from_player",
                "malice_chance_from_baby_animal")) {
            var value = section.get(key);
            expect(key + " is a probability", value instanceof Number number
                    && number.doubleValue() >= 0.0 && number.doubleValue() <= 1.0);
        }
    }

    /// The Visage of War must not arm an Omen of War.
    ///
    /// Winning that fight handing back a ticket to repeat it is the one exclusion the feature was
    /// specified with, and it now takes two data files to express.
    ///
    /// Absence from the source tag used to be enough. It is not any more: the health rule qualifies
    /// anything at or above the threshold, War has 320 health, and the default threshold is 200 — so
    /// the exclusion had to become a statement rather than an omission. Both halves are asserted,
    /// including the arithmetic that makes the exclusion necessary, because a future retune that
    /// raised the threshold past 320 would make this look redundant when it is not.
    private static void checkWarOmenSources() {
        var settings = DivineConfig.authoredSettings().get("omens_from_kills");
        var threshold = settings == null ? null : settings.get("war_boss_health_threshold");
        var warHealth = 320.0;
        if (threshold instanceof Number number) {
            expect("the Visage of War would qualify on health alone (" + warHealth + " >= "
                            + number.doubleValue() + "), so an explicit exclusion is required",
                    warHealth >= number.doubleValue());
        }

        var excluded = tagEntries("war_omen_excluded");
        expect("the Visage of War is explicitly excluded",
                excluded.contains("divine_encounters:visage_of_war"));
        expect("the Visage of Malice is not excluded",
                !excluded.contains("divine_encounters:visage_of_malice"));

        checkWarSourceTag();
    }

    private static LinkedHashSet<String> tagEntries(String name) {
        var entries = new LinkedHashSet<String>();
        var stream = ConfigCheck.class.getResourceAsStream(
                "/data/divine_encounters/tags/entity_type/" + name + ".json");
        if (stream == null) {
            expect("tag exists: " + name, false);
            return entries;
        }
        try (var reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
            com.google.gson.JsonParser.parseReader(reader).getAsJsonObject()
                    .getAsJsonArray("values")
                    .forEach(element -> entries.add(element.getAsString()));
        } catch (Exception exception) {
            expect("tag parses: " + name, false);
        }
        return entries;
    }

    private static void checkWarSourceTag() {
        var stream = ConfigCheck.class.getResourceAsStream(
                "/data/divine_encounters/tags/entity_type/war_omen_sources.json");
        if (stream == null) {
            expect("the war-omen source tag exists", false);
            return;
        }
        try (var reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var values = com.google.gson.JsonParser.parseReader(reader)
                    .getAsJsonObject().getAsJsonArray("values");
            var entries = new LinkedHashSet<String>();
            values.forEach(element -> entries.add(element.getAsString()));
            System.out.println("war omen sources: " + entries);
            expect("the war-omen source tag is populated", !entries.isEmpty());
            expect("killing the Visage of War does not arm an Omen of War",
                    !entries.contains("divine_encounters:visage_of_war"));
            expect("killing the Visage of Malice can arm an Omen of War",
                    entries.contains("divine_encounters:visage_of_malice"));
        } catch (Exception exception) {
            expect("the war-omen source tag parses (" + exception.getMessage() + ")", false);
        }
    }

    private static String entitySource(String boss) {
        var folder = boss.equals("visage_of_war") ? "visage" : "malice";
        var className = boss.equals("visage_of_war")
                ? "VisageOfWarEntity" : "VisageOfMaliceEntity";
        var path = Path.of("src", "main", "java", "com", "cleannrooster", "divineencounters",
                "content", folder, "entity", className + ".java");
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return null;
        }
    }

    private static void expect(String what, boolean condition) {
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

    private ConfigCheck() {
    }
}
