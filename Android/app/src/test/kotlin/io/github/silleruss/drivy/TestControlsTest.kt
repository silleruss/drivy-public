package io.github.silleruss.drivy

import org.junit.Assert.*
import org.junit.Test

class TestControlsTest {
    @Test fun drivingScenarioRuns() { assertTrue(testDrivingScenario().startsWith("통과")) }

    @Test fun acceptsBoundariesAndKeepsIndependentFields() {
        val progress = testProgress("300000", "12.5", "100", Companion.NERU)
        assertEquals(2, DistanceGrowth.stageFor(progress.experience))
        assertEquals(12.5, progress.totalKm, 0.0)
        assertEquals(100.0, progress.drivingPenalty, 0.0)
        assertEquals(Companion.NERU, progress.companion)
    }
    @Test fun rejectsInvalidAndNonFiniteValues() {
        for (value in listOf("", "abc", "NaN", "Infinity", "-1", "2000000001")) {
            assertThrows(IllegalArgumentException::class.java) { testProgress(value, "0", "0", Companion.MOKA) }
        }
        assertThrows(IllegalArgumentException::class.java) { testProgress("0", "10000001", "0", Companion.MOKA) }
        assertThrows(IllegalArgumentException::class.java) { testProgress("0", "0", "101", Companion.MOKA) }
    }
}
