package io.github.silleruss.drivy

import org.junit.Assert.*
import org.junit.Test

class CompanionDisplayTest {
    @Test fun smallCompanionRequiresOptInAChosenPetAndKnownParkedState() {
        for (enabled in listOf(false, true)) for (chosen in listOf(false, true))
            for (known in listOf(false, true)) for (restricted in listOf(false, true)) {
                assertEquals(enabled && chosen && known && !restricted,
                    canShowSmallCompanion(enabled, chosen, known, restricted))
            }
    }
}
