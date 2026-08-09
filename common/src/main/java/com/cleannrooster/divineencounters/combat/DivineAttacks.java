package com.cleannrooster.divineencounters.combat;

import com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks;
import com.cleannrooster.divineencounters.content.visage.ai.VisageAttacks;

/// Single place that forces every boss's attack definitions to register.
///
/// Both sides call this during common init, which is what lets a swing be networked as nothing more than
/// an id: the client looks the definition up in {@link AttackRegistry} rather than being told about it.
/// A new boss adds one line here.
public final class DivineAttacks {
    private DivineAttacks() {
    }

    public static void bootstrap() {
        VisageAttacks.bootstrap();
        MaliceAttacks.bootstrap();
    }
}
