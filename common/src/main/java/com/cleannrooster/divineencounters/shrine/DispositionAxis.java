package com.cleannrooster.divineencounters.shrine;

import com.cleannrooster.divineencounters.DivineEncounters;
import net.minecraft.resources.ResourceLocation;

/// A dimension a prayer can be read along.
///
/// There is only one axis today — malice against triumph — but it is a named, registered thing
/// rather than a hardcoded sign, because the shrine is meant to grow. A later reading might weigh
/// mercy against wrath, or patience against hunger, without any of the scoring machinery changing:
/// terms declare which axis they speak to, the evaluator accumulates per axis, and an outcome
/// resolver inspects whichever axes it cares about.
///
/// @param id       registry-style identifier
/// @param negative translation-key suffix for the negative pole, e.g. `malice`
/// @param positive translation-key suffix for the positive pole, e.g. `triumph`
public record DispositionAxis(ResourceLocation id, String negative, String positive) {
    /// The founding axis: `MALICIOUS <- neutral -> TRIUMPHANT`.
    public static final DispositionAxis SPIRIT =
            new DispositionAxis(DivineEncounters.id("spirit"), "malice", "triumph");

    @Override
    public String toString() {
        return this.id.toString();
    }
}
