package com.cleannrooster.divineencounters.config;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/// The numbers a server owner is allowed to change: every attack's damage, and each boss's basic
/// attributes.
///
/// Scope is deliberately the same as the player-facing guide. If a figure is quoted to players it can
/// be retuned; everything else — timings, geometry, the observation angles, the presence rules — stays
/// in code, because those are load-bearing for fairness in ways a stray edit would quietly break. An
/// attack whose damage is doubled is still an attack you can read and dodge. An attack whose windup is
/// halved may not be.
///
/// ### Read once, at startup
///
/// Values are read when the file loads and baked into the attack definitions and attribute suppliers
/// at registration. Editing the file needs a game restart; `/reload` will not do it. That is a real
/// limitation and it is deliberate rather than an oversight — entity attribute suppliers are built
/// once when the entity type is registered, so making damage live-reloadable while attributes were
/// not would produce a config where half the fields behave differently from the other half, with
/// nothing on screen to say which half you were editing.
///
/// ### Missing keys keep their defaults
///
/// The file is merged over the defaults rather than replacing them, and rewritten afterwards. So a
/// config written for an older version gains new keys automatically, a hand-deleted line falls back
/// to the shipped value instead of zero, and an unparseable file logs and is ignored rather than
/// leaving the mod half-configured.
public final class DivineConfig {
    private static final String FILE_NAME = DivineEncounters.MOD_ID + ".json";

    private static final String README =
            "Damage and boss attributes for Divine Encounters. Changes require a game restart. "
            + "Missing entries fall back to the shipped defaults. Delete this file to regenerate it.";

    /// Authored damage per attack, keyed by the attack's path (its id without the namespace).
    ///
    /// Populated by the attack definitions themselves as they are built, so this map cannot drift
    /// out of step with the moveset: an attack that exists has an entry, and one that does not,
    /// does not.
    private static final Map<String, Double> attackDamage = new LinkedHashMap<>();
    private static final Map<String, Map<String, Double>> bossAttributes = new LinkedHashMap<>();
    /// Free-form sections: section name -> key -> authored value. Booleans are stored alongside
    /// numbers rather than in a second map, because a config with two parallel type registries is a
    /// config where half the keys silently ignore the other half's merge rules.
    private static final Map<String, Map<String, Object>> settings = new LinkedHashMap<>();

    private static JsonObject loaded = new JsonObject();
    private static boolean initialised;

    private DivineConfig() {
    }

    // --- lookup ------------------------------------------------------------------------------------

    /// Damage for an attack, from config if present.
    ///
    /// Called from the attack definitions at construction, which is what keeps the default recorded
    /// here identical to the authored value by definition rather than by discipline.
    public static float damage(String attackPath, float authored) {
        attackDamage.put(attackPath, (double) authored);
        return (float) read("attacks", attackPath, authored);
    }

    /// A boss attribute, from config if present.
    public static double attribute(String bossPath, String attribute, double authored) {
        bossAttributes.computeIfAbsent(bossPath, key -> new LinkedHashMap<>())
                .put(attribute, authored);
        return read("bosses." + bossPath, attribute, authored);
    }

    /// A numeric setting in an arbitrary section.
    public static double setting(String section, String key, double authored) {
        settings.computeIfAbsent(section, ignored -> new LinkedHashMap<>()).put(key, authored);
        return read(section, key, authored);
    }

    /// A boolean setting in an arbitrary section.
    public static boolean flag(String section, String key, boolean authored) {
        settings.computeIfAbsent(section, ignored -> new LinkedHashMap<>()).put(key, authored);
        var node = resolve(section);
        if (node == null || !node.has(key)) {
            return authored;
        }
        var value = node.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            DivineEncounters.LOGGER.warn(
                    "Config value {}.{} is not true/false; using the default {}.",
                    section, key, authored);
            return authored;
        }
        return value.getAsBoolean();
    }

    private static double read(String section, String key, double fallback) {
        var node = resolve(section);
        if (node == null || !node.has(key)) {
            return fallback;
        }
        var value = node.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            DivineEncounters.LOGGER.warn(
                    "Config value {}.{} is not a number; using the default {}.", section, key, fallback);
            return fallback;
        }
        return value.getAsDouble();
    }

    private static JsonObject resolve(String path) {
        JsonObject node = loaded;
        for (var part : path.split("\\.")) {
            if (node == null || !node.has(part) || !node.get(part).isJsonObject()) {
                return null;
            }
            node = node.getAsJsonObject(part);
        }
        return node;
    }

    // --- lifecycle ---------------------------------------------------------------------------------

    /// Read the config file. Must run before anything that calls {@link #damage} or
    /// {@link #attribute} — in practice, first thing in mod init.
    public static void load() {
        if (initialised) {
            return;
        }
        initialised = true;

        var path = configPath();
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var parsed = JsonParser.parseReader(reader);
            if (parsed.isJsonObject()) {
                loaded = parsed.getAsJsonObject();
            } else {
                DivineEncounters.LOGGER.warn("{} is not a JSON object; using defaults.", FILE_NAME);
            }
        } catch (Exception exception) {
            // Deliberately not fatal, and deliberately not partially applied. A broken config should
            // cost the owner their edits, not their world.
            DivineEncounters.LOGGER.error(
                    "Could not read {}; using defaults. ({})", FILE_NAME, exception.toString());
            loaded = new JsonObject();
        }
    }

    /// Write the file back, merging whatever was loaded over the full set of defaults.
    ///
    /// Called after registration, once every attack and attribute has announced itself, so the file
    /// on disk always lists everything that can be changed — including keys added by a mod update.
    public static void save() {
        var path = configPath();
        if (path == null) {
            return;
        }

        var root = new JsonObject();
        root.addProperty("_readme", README);

        var bosses = new JsonObject();
        bossAttributes.forEach((boss, attributes) -> {
            var entry = new JsonObject();
            attributes.forEach((name, authored) ->
                    entry.addProperty(name, read("bosses." + boss, name, authored)));
            bosses.add(boss, entry);
        });
        root.add("bosses", bosses);

        var attacks = new JsonObject();
        attackDamage.forEach((attack, authored) ->
                attacks.addProperty(attack, read("attacks", attack, authored)));
        root.add("attacks", attacks);

        settings.forEach((section, values) -> {
            var entry = new JsonObject();
            values.forEach((key, authored) -> {
                if (authored instanceof Boolean bool) {
                    entry.addProperty(key, flag(section, key, bool));
                } else {
                    entry.addProperty(key, read(section, key, ((Number) authored).doubleValue()));
                }
            });
            root.add(section, entry);
        });

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson((JsonElement) root, writer);
                writer.write(System.lineSeparator());
            }
        } catch (IOException exception) {
            DivineEncounters.LOGGER.error("Could not write {}: {}", FILE_NAME, exception.toString());
        }
    }

    private static Path configPath() {
        try {
            return Platform.getConfigFolder().resolve(FILE_NAME);
        } catch (Throwable ignored) {
            // No platform (harnesses, datagen). Defaults are the right answer there anyway.
            return null;
        }
    }

    // --- introspection, for the verification harness ----------------------------------------------

    public static Map<String, Double> authoredAttackDamage() {
        return Map.copyOf(attackDamage);
    }

    public static Map<String, Map<String, Object>> authoredSettings() {
        var copy = new LinkedHashMap<String, Map<String, Object>>();
        settings.forEach((section, values) -> copy.put(section, Map.copyOf(values)));
        return Map.copyOf(copy);
    }

    public static Map<String, Map<String, Double>> authoredBossAttributes() {
        var copy = new LinkedHashMap<String, Map<String, Double>>();
        bossAttributes.forEach((boss, values) -> copy.put(boss, Map.copyOf(values)));
        return Map.copyOf(copy);
    }
}
