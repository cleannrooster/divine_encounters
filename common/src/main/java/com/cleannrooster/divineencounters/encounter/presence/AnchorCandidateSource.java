package com.cleannrooster.divineencounters.encounter.presence;

import com.cleannrooster.divineencounters.encounter.anchor.AnchorKind;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/// Contributes the arena's authored positions — perches, edges, echo points — to the candidate
/// pool.
///
/// Silently contributes nothing when no arena has been registered. That is the whole reason perch
/// actions degrade gracefully instead of erroring when the boss is summoned with a spawn egg: the
/// pool simply contains no elevated options, so {@link ManifestKind#PERCH} finds nothing and the
/// AI picks a different action.
public final class AnchorCandidateSource implements CandidateSource {
    @Override
    public void collect(CandidateContext context, List<Vec3> out) {
        var anchors = context.anchors();
        if (anchors.isEmpty()) {
            return;
        }
        // Only unclaimed anchors: two things must never occupy the same perch.
        for (var kind : new AnchorKind[]{AnchorKind.PERCH, AnchorKind.EDGE, AnchorKind.ECHO_POINT}) {
            for (var anchor : anchors.available(kind)) {
                out.add(anchor.position());
            }
        }
    }
}
