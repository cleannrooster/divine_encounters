package com.cleannrooster.divineencounters.content;

import com.cleannrooster.divineencounters.content.malice.ai.MaliceState;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Standalone verification of the Visage of Malice's vegetation-phasing rules, run from the
/// `phasingCheck` Gradle task. Lives in the test source set, so it never ships in the jar.
///
/// Two things are asserted, and both of them are promises to the player rather than internal
/// consistency checks.
///
/// **Player-built structures stay solid.** The phasing rule is only acceptable because the line
/// between "a tree" and "something somebody made" is drawn deliberately. That line lives entirely in
/// three tag files, which are plain data and therefore very easy to widen by accident — the natural
/// thing to reach for when adding modded tree support is `#minecraft:logs`, which silently contains
/// every stripped log and every six-sided wood block. This asserts the deny tag still covers those,
/// so that widening stays safe.
///
/// **Attacks cannot come out of solid wood.** Trunk phasing is gated on movement state, and the
/// attack states must never be in that set. A regression there would not crash or look wrong in
/// review; it would just produce a boss that occasionally kills you from inside a tree.
public final class PhasingCheck {
    private static final String TAGS = "/data/divine_encounters/tags/block/";

    private static int failures;

    public static void main(String[] args) {
        var always = readTag("malice_phase_always");
        var supernatural = readTag("malice_phase_supernatural");
        var denied = readTag("malice_phase_denied");

        checkTagsAreSane(always, supernatural, denied);
        checkConstructedWoodIsDenied(denied);
        checkStrippedVariantsAreDenied(supernatural, denied);
        checkStateGate();

        System.out.println(failures == 0 ? "ALL PHASING CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void checkTagsAreSane(Set<String> always, Set<String> supernatural,
                                         Set<String> denied) {
        expect("the always-phase tag is populated", !always.isEmpty());
        expect("the supernatural-phase tag is populated", !supernatural.isEmpty());
        expect("the deny tag is populated", !denied.isEmpty());
        expect("leaves are always passable", always.contains("#minecraft:leaves"));

        // A block in both phase tags is not wrong, but it means the state gate does nothing for it —
        // which is almost certainly not what the author intended.
        var overlap = new LinkedHashSet<>(always);
        overlap.retainAll(supernatural);
        expect("no block is in both phase tags" + (overlap.isEmpty() ? "" : " — " + overlap),
                overlap.isEmpty());
    }

    /// The categories the brief names explicitly as things that must stay solid.
    private static void checkConstructedWoodIsDenied(Set<String> denied) {
        for (var required : List.of("#minecraft:planks", "#minecraft:wooden_doors",
                "#minecraft:wooden_trapdoors", "#minecraft:wooden_fences", "#minecraft:fence_gates",
                "#minecraft:wooden_stairs", "#minecraft:wooden_slabs",
                "minecraft:chest", "minecraft:barrel", "minecraft:crafting_table",
                "minecraft:lectern")) {
            expect("denied: " + required, denied.contains(required));
        }
    }

    /// Every natural log in the supernatural tag must have its stripped and its six-sided wood
    /// counterpart denied.
    ///
    /// This is the check that makes `#minecraft:logs` safe to add for modded-tree support. Without
    /// it, a one-line datapack edit that looks entirely reasonable would make every log cabin in the
    /// world transparent to the boss.
    private static void checkStrippedVariantsAreDenied(Set<String> supernatural, Set<String> denied) {
        var missing = new LinkedHashSet<String>();
        for (var entry : supernatural) {
            if (!entry.startsWith("minecraft:") || !entry.endsWith("_log")) {
                continue;
            }
            var kind = entry.substring("minecraft:".length(), entry.length() - "_log".length());
            for (var variant : List.of(
                    "minecraft:stripped_" + kind + "_log",
                    "minecraft:" + kind + "_wood",
                    "minecraft:stripped_" + kind + "_wood")) {
                if (!denied.contains(variant)) {
                    missing.add(variant);
                }
            }
        }
        expect("every natural log's processed variants are denied"
                        + (missing.isEmpty() ? "" : " — missing " + missing),
                missing.isEmpty());
    }

    /// Trunk phasing is a traversal privilege, never a firing position.
    private static void checkStateGate() {
        for (var state : MaliceState.values()) {
            if (state.isAttacking()) {
                expect(state + " does not phase through trunks", !state.allowsTrunkPhasing());
            }
        }
        expect("STALK does not phase through trunks", !MaliceState.STALK.allowsTrunkPhasing());

        expect("HIDDEN phases through trunks", MaliceState.HIDDEN.allowsTrunkPhasing());
        expect("POUNCE phases through trunks", MaliceState.POUNCE.allowsTrunkPhasing());
        expect("AMBUSH phases through trunks", MaliceState.AMBUSH.allowsTrunkPhasing());
        expect("BREAK_CONTACT phases through trunks", MaliceState.BREAK_CONTACT.allowsTrunkPhasing());
        expect("NO_WITNESS phases through trunks", MaliceState.NO_WITNESS.allowsTrunkPhasing());
    }

    private static Set<String> readTag(String name) {
        var path = TAGS + name + ".json";
        var stream = PhasingCheck.class.getResourceAsStream(path);
        if (stream == null) {
            expect("tag file is present: " + path, false);
            return Set.of();
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            var values = root.getAsJsonArray("values");
            return collect(values);
        } catch (Exception exception) {
            expect("tag file parses: " + path + " (" + exception.getMessage() + ")", false);
            return Set.of();
        }
    }

    private static Set<String> collect(JsonArray values) {
        var out = new LinkedHashSet<String>();
        for (var element : values) {
            if (element.isJsonPrimitive()) {
                out.add(element.getAsString());
            } else if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
                out.add(element.getAsJsonObject().get("id").getAsString());
            }
        }
        return out;
    }

    private static void expect(String what, boolean condition) {
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

    private PhasingCheck() {
    }
}
