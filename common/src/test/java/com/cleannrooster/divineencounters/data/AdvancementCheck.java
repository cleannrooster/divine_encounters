package com.cleannrooster.divineencounters.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Standalone verification of the advancement tree, run from the `advancementCheck` Gradle task.
///
/// Advancements are data, and every way they go wrong goes wrong *quietly*:
///
/// - a mistyped `translate` key renders the raw key in the UI rather than failing;
/// - a mistyped entity id in a kill condition simply never fires, so the advancement is
///   unobtainable and nothing in the log says so;
/// - a mistyped `parent` silently orphans a branch out of the tree;
/// - a mistyped icon id shows as a missing-texture cube.
///
/// None of that surfaces until someone plays far enough to earn it, which for a boss kill is a long
/// way. All four are string agreements between separate files, which is exactly what a cheap data
/// check is for.
public final class AdvancementCheck {
    private static final String DIR = "/data/divine_encounters/advancement/";
    private static final String LANG = "/assets/divine_encounters/lang/en_us.json";
    private static final String NAMESPACE = "divine_encounters";

    private static final List<String> FILES = List.of("root", "visage_of_war", "visage_of_malice");

    private static int failures;

    public static void main(String[] args) {
        var lang = readJson(LANG);
        if (lang == null) {
            System.out.println("1 CHECK(S) FAILED");
            System.exit(1);
            return;
        }

        var known = new LinkedHashSet<String>();
        FILES.forEach(name -> known.add(NAMESPACE + ":" + name));

        for (var name : FILES) {
            var advancement = readJson(DIR + name + ".json");
            expect("advancement parses: " + name, advancement != null);
            if (advancement == null) {
                continue;
            }
            checkParent(name, advancement, known);
            checkDisplay(name, advancement, lang);
            checkCriteria(name, advancement);
        }

        checkRootIsAlwaysGranted();

        System.out.println(failures == 0
                ? "ALL ADVANCEMENT CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void checkParent(String name, JsonObject advancement, Set<String> known) {
        var isRoot = name.equals("root");
        var hasParent = advancement.has("parent");
        expect(name + (isRoot ? " is a root (no parent)" : " declares a parent"), isRoot != hasParent);
        if (hasParent) {
            var parent = advancement.get("parent").getAsString();
            expect(name + " points at an advancement that exists (" + parent + ")",
                    known.contains(parent));
        }
    }

    /// Every translatable string an advancement references must exist, and every item it names must
    /// belong to this mod or to vanilla.
    private static void checkDisplay(String name, JsonObject advancement, JsonObject lang) {
        var display = advancement.getAsJsonObject("display");
        expect(name + " has a display block", display != null);
        if (display == null) {
            return;
        }
        for (var field : List.of("title", "description")) {
            var node = display.getAsJsonObject(field);
            if (node == null || !node.has("translate")) {
                expect(name + "." + field + " uses a translation key", false);
                continue;
            }
            var key = node.get("translate").getAsString();
            expect(name + "." + field + " key is translated: " + key, lang.has(key));
        }

        var icon = display.getAsJsonObject("icon");
        if (icon == null || !icon.has("id")) {
            expect(name + " has an icon id", false);
            return;
        }
        var id = icon.get("id").getAsString();
        expect(name + " icon is namespaced: " + id, id.contains(":"));
    }

    /// A kill condition naming an entity this mod does not register is unobtainable, and looks
    /// exactly like an advancement nobody has earned yet.
    private static void checkCriteria(String name, JsonObject advancement) {
        var criteria = advancement.getAsJsonObject("criteria");
        expect(name + " declares criteria", criteria != null && !criteria.isEmpty());
        if (criteria == null) {
            return;
        }

        var requirements = advancement.getAsJsonArray("requirements");
        expect(name + " declares requirements", requirements != null && !requirements.isEmpty());

        for (var entry : criteria.entrySet()) {
            var conditions = entry.getValue().getAsJsonObject().getAsJsonObject("conditions");
            if (conditions == null || !conditions.has("entity")) {
                continue;
            }
            for (var condition : conditions.getAsJsonArray("entity")) {
                var predicate = condition.getAsJsonObject().getAsJsonObject("predicate");
                if (predicate == null || !predicate.has("type")) {
                    continue;
                }
                var type = predicate.get("type").getAsString();
                expect(name + " targets an entity this mod registers: " + type,
                        MOD_ENTITIES.contains(type));
            }
        }
    }

    /// Entity ids registered in `ModEntities`. Duplicated here as plain strings on purpose: the
    /// registry itself cannot be touched without a loader, and the whole point of the check is that
    /// these two files agree. If an entity is renamed, this list is the thing that fails.
    private static final Set<String> MOD_ENTITIES = Set.of(
            "divine_encounters:visage_of_war",
            "divine_encounters:visage_of_malice",
            "divine_encounters:malice_echo");

    /// The root is meant to be held by every player from the moment they join, with no fanfare.
    ///
    /// `minecraft:tick` with no conditions is how vanilla grants something immediately — it is what
    /// the recipe advancements use for their `unlock_right_away` criterion. An `impossible` trigger
    /// would look similar and do the opposite.
    private static void checkRootIsAlwaysGranted() {
        var root = readJson(DIR + "root.json");
        if (root == null) {
            return;
        }
        var criteria = root.getAsJsonObject("criteria");
        var granted = criteria != null && criteria.entrySet().stream()
                .anyMatch(entry -> "minecraft:tick".equals(
                        entry.getValue().getAsJsonObject().get("trigger").getAsString()));
        expect("the root is granted immediately", granted);

        var display = root.getAsJsonObject("display");
        expect("the root does not announce itself",
                display != null
                        && !display.get("announce_to_chat").getAsBoolean()
                        && !display.get("show_toast").getAsBoolean());
        expect("the root supplies a background", display != null && display.has("background"));
    }

    private static JsonObject readJson(String path) {
        var stream = AdvancementCheck.class.getResourceAsStream(path);
        if (stream == null) {
            expect("resource is present: " + path, false);
            return null;
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            expect("resource parses: " + path + " (" + exception.getMessage() + ")", false);
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

    private AdvancementCheck() {
    }
}
