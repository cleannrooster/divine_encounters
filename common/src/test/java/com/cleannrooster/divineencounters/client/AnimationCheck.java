package com.cleannrooster.divineencounters.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/// Standalone verification that the authored animation clips are safe for the bones that code
/// rotates afterwards. Run from the `animationCheck` Gradle task; lives in the test source set, so
/// it never ships in the jar.
///
/// ### The bug this exists to prevent
///
/// `VisageOfMaliceModel.setCustomAnimations` rotates the head and neck to hold the gaze on the
/// player, *adding* to whatever the clip posed. That addition is only safe if the clip actually
/// posed the bone.
///
/// GeckoLib decides per frame whether each bone was animated. If it was, the bone is rewritten from
/// the clip and the offset lands on a clean value. If it was **not**, GeckoLib instead eases the
/// bone from its *current* value back toward the rest pose over several ticks — and that current
/// value still contains the offset added on the previous frame. The offset compounds every frame
/// and the bone spins.
///
/// This is a genuinely nasty failure mode: the model code is correct, the clip is valid, and
/// nothing throws. The only symptom is a head that rotates rapidly instead of tracking, and the
/// cause is a bone that is *missing* from a clip rather than wrong in one. The generator injects
/// constant keyframes to guarantee coverage; this asserts the guarantee held, so a hand-edited or
/// re-exported clip cannot quietly reintroduce it.
public final class AnimationCheck {
    /// Animation file → bones that code rotates on top of the clip.
    ///
    /// Keep in step with each model's `setCustomAnimations`. A bone listed here must appear in
    /// every clip in that file, even if only holding a constant value.
    private static final Map<String, List<String>> DRIVEN_BONES = Map.of(
            "/assets/divine_encounters/animations/visage_of_malice.animation.json",
            List.of("head", "neck"));

    private static int failures;

    public static void main(String[] args) {
        DRIVEN_BONES.forEach(AnimationCheck::checkFile);

        System.out.println(failures == 0 ? "ALL ANIMATION CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void checkFile(String path, List<String> driven) {
        var stream = AnimationCheck.class.getResourceAsStream(path);
        if (stream == null) {
            expect("animation file is present: " + path, false);
            return;
        }

        JsonObject animations;
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            animations = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
        } catch (Exception exception) {
            expect("animation file parses: " + path + " (" + exception.getMessage() + ")", false);
            return;
        }

        expect("clips found in " + shortName(path), animations != null && !animations.isEmpty());
        if (animations == null) {
            return;
        }

        for (var bone : driven) {
            var missing = animations.entrySet().stream()
                    .filter(entry -> !animatesBone(entry.getValue().getAsJsonObject(), bone))
                    .map(Map.Entry::getKey)
                    .toList();
            expect("every clip in " + shortName(path) + " animates '" + bone + "'"
                            + (missing.isEmpty() ? "" : " — missing from " + missing),
                    missing.isEmpty());
        }
    }

    /// A bone counts as animated if it has a `rotation` channel. Position and scale are irrelevant
    /// here: only rotation compounds, because only rotation is what the model adds to.
    private static boolean animatesBone(JsonObject clip, String bone) {
        var bones = clip.getAsJsonObject("bones");
        if (bones == null || !bones.has(bone)) {
            return false;
        }
        var entry = bones.get(bone);
        return entry.isJsonObject() && entry.getAsJsonObject().has("rotation");
    }

    private static String shortName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void expect(String what, boolean condition) {
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

    private AnimationCheck() {
    }
}
