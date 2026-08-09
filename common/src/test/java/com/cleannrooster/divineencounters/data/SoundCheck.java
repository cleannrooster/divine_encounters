package com.cleannrooster.divineencounters.data;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/// Standalone verification of `sounds.json`, run from the `soundCheck` Gradle task.
///
/// Sound wiring fails silently in three separate ways, none of which produce an error:
///
/// - a misspelled vanilla event id plays **nothing**, and a boss that is quietly mute during one
///   attack is very easy to miss in a fight full of other noise;
/// - a `sounds.json` entry with no matching `ModSounds.register(...)` is never reachable;
/// - a registered event with no `sounds.json` entry plays nothing either.
///
/// All three are string agreements between a data file, a registry class and the vanilla sound
/// registry, so all three are checkable here.
///
/// Every referenced vanilla id is resolved against `BuiltInRegistries.SOUND_EVENT` — the actual
/// registry, not a list written down somewhere.
public final class SoundCheck {
    private static final String SOUNDS = "/assets/divine_encounters/sounds.json";
    private static final String LANG = "/assets/divine_encounters/lang/en_us.json";

    private static int failures;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        var sounds = read(SOUNDS);
        var lang = read(LANG);
        if (sounds == null || lang == null) {
            System.out.println("1 CHECK(S) FAILED");
            System.exit(1);
            return;
        }

        var declared = new LinkedHashSet<String>();
        var missingVanilla = new LinkedHashSet<String>();
        var missingSubtitles = new LinkedHashSet<String>();
        var singleSource = new LinkedHashSet<String>();

        for (var entry : sounds.entrySet()) {
            declared.add(entry.getKey());
            var value = entry.getValue().getAsJsonObject();

            if (value.has("subtitle")) {
                var key = value.get("subtitle").getAsString();
                if (!lang.has(key)) {
                    missingSubtitles.add(key);
                }
            } else {
                missingSubtitles.add(entry.getKey() + " (no subtitle at all)");
            }

            var list = value.getAsJsonArray("sounds");
            if (list.size() < 2) {
                singleSource.add(entry.getKey());
            }
            for (var element : list) {
                var name = element.getAsJsonObject().get("name").getAsString();
                var id = ResourceLocation.tryParse(name);
                if (id == null || !BuiltInRegistries.SOUND_EVENT.containsKey(id)) {
                    missingVanilla.add(name);
                }
            }
        }

        System.out.printf("sound events: %d, sources: %d%n", declared.size(),
                sounds.entrySet().stream()
                        .mapToInt(e -> e.getValue().getAsJsonObject().getAsJsonArray("sounds").size())
                        .sum());

        expect("every referenced sound exists in the vanilla registry"
                        + (missingVanilla.isEmpty() ? "" : " — unknown " + missingVanilla),
                missingVanilla.isEmpty());
        expect("every sound event has a translated subtitle"
                        + (missingSubtitles.isEmpty() ? "" : " — " + missingSubtitles),
                missingSubtitles.isEmpty());

        // Not a correctness rule, but the whole point of this pass: one source per event means every
        // play is identical, and an identical vanilla sample is exactly what players recognise.
        expect("every event varies between at least two sources"
                        + (singleSource.isEmpty() ? "" : " — single-source " + singleSource),
                singleSource.isEmpty());

        checkRegistryAgreement(declared);

        System.out.println(failures == 0 ? "ALL SOUND CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /// `sounds.json` and `ModSounds` must name exactly the same set.
    ///
    /// Read from the registry source rather than by loading the class, which would need Architectury's
    /// deferred registers to be initialised. The agreement being checked is textual anyway.
    private static void checkRegistryAgreement(LinkedHashSet<String> declared) {
        String source;
        try {
            source = Files.readString(Path.of("src", "main", "java", "com", "cleannrooster",
                    "divineencounters", "registry", "ModSounds.java"), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            expect("ModSounds source is readable", false);
            return;
        }

        var registered = new LinkedHashSet<String>();
        var matcher = Pattern.compile("register\\(\"([a-z_.0-9]+)\"\\)").matcher(source);
        while (matcher.find()) {
            registered.add(matcher.group(1));
        }

        var unregistered = new LinkedHashSet<>(declared);
        unregistered.removeAll(registered);
        expect("every sounds.json event is registered in ModSounds"
                        + (unregistered.isEmpty() ? "" : " — " + unregistered),
                unregistered.isEmpty());

        var unmapped = new LinkedHashSet<>(registered);
        unmapped.removeAll(declared);
        expect("every registered sound has a sounds.json entry"
                        + (unmapped.isEmpty() ? "" : " — " + unmapped),
                unmapped.isEmpty());
    }

    private static com.google.gson.JsonObject read(String path) {
        var stream = SoundCheck.class.getResourceAsStream(path);
        if (stream == null) {
            expect("resource is present: " + path, false);
            return null;
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            expect("resource parses: " + path, false);
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

    private SoundCheck() {
    }
}
