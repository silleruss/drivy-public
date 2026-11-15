package io.github.silleruss.drivy

import org.junit.Assert.*
import org.junit.Test

class DistanceGrowthTest {
    @Test fun revisedGrowthKeepsBaseXpAndUsesNewThresholds() {
        for ((score, expected) in listOf(null to 100.0, 0 to 100.0, 50 to 100.0, 75 to 100.0, 100 to 200.0)) {
            val growth = DistanceGrowth()
            growth.observe(100.0, 0)
            growth.observe(101.0, 60_000, score)
            assertEquals(expected, growth.experience, 0.0)
        }
        for ((xp, stage) in listOf(49_999.0 to 0, 50_000.0 to 1, 299_999.0 to 1, 300_000.0 to 2))
            assertEquals(stage, DistanceGrowth.stageFor(xp))
    }

    @Test fun repeatedSmallJumpsCannotRenewTheDistanceTolerance() {
        val growth = DistanceGrowth()
        growth.observe(1000.0, 0)
        for (i in 1..100) growth.observe(1000.0 + i * .03125, i * 200L)
        assertTrue("20 seconds cannot credit 3.125 km", growth.totalKm <= 240 * 20 / 3600.0 + .1)
        assertEquals(growth.totalKm * 100, growth.experience, .000001)
    }

    @Test fun validQuantizedDistanceIsIndependentOfSampleFrequency() {
        for (intervalMs in listOf(200L, 1000L, 10_000L)) {
            val growth = DistanceGrowth(12.0, 850.0)
            growth.observe(1000.0, 0)
            for (ms in intervalMs..60_000L step intervalMs) {
                val km = 1000.0 + kotlin.math.floor(ms / 1000.0 / 3.125) * .03125
                growth.observe(km.toFloat().toDouble(), ms)
            }
            assertEquals(12.59375, growth.totalKm, .000001)
            assertEquals(909.375, growth.experience, .000001)
        }
    }

    @Test fun parkedTimeCannotBeBankedToAcceptAFutureJump() {
        val growth = DistanceGrowth()
        growth.observe(1000.0, 0)
        assertEquals("unchanged", growth.observe(1000.0, 600_000))
        assertEquals("implausible_jump", growth.observe(1001.0, 600_200))
        assertEquals(0.0, growth.totalKm, 0.0)
        assertEquals("grown", growth.observe(1001.03125, 605_200))
        assertEquals(.03125, growth.totalKm, .000001)
    }

    @Test fun drivingQualityChangesXpWithoutChangingActualDistance() {
        for ((score, change) in listOf(null to 100.0, 100 to 200.0, 75 to 100.0, 50 to 100.0, 25 to 100.0, 0 to 100.0)) {
            val growth = DistanceGrowth(10.0, 1100.0)
            growth.observe(100.0, 0)
            assertEquals("grown", growth.observe(101.0, 60_000, score))
            assertEquals(11.0, growth.totalKm, 0.0)
            assertEquals(1100 + change, growth.experience, .000001)
            assertEquals(0, growth.stage)
            val xp = growth.experience
            growth.observe(102.0, 60_000, score)
            growth.observe(null, 61_000, score)
            assertEquals(xp, growth.experience, 0.0)
        }
        val floor = DistanceGrowth(0.0, 10.0)
        floor.observe(10.0, 0)
        floor.observe(11.0, 60_000, 0)
        assertEquals(110.0, floor.experience, 0.0)
        val cap = DistanceGrowth(0.0, 2_000_000_000.0)
        cap.observe(10.0, 0)
        cap.observe(11.0, 60_000, 100)
        assertEquals(2_000_000_000.0, cap.experience, 0.0)
        assertThrows(IllegalArgumentException::class.java) { floor.observe(12.0, 120_000, 101) }
        assertThrows(IllegalArgumentException::class.java) { DistanceGrowth(0.0, Double.NaN) }
    }

    @Test fun evolutionUsesExactDistanceThresholdsIncludingRestoredProgress() {
        for ((km, stage) in listOf(0.0 to 0, 499.999 to 0, 500.0 to 1, 2999.999 to 1, 3000.0 to 2,
            DistanceGrowth.MAXIMUM_TOTAL_KM to 2)) {
            assertEquals("distance $km", stage, DistanceGrowth(km).stage)
        }
    }

    @Test fun plausibleDistanceAndStages() {
        val growth = DistanceGrowth(499.9)
        assertEquals("baseline", growth.observe(1000.0, 1000))
        assertEquals("grown", growth.observe(1000.2, 61000))
        assertEquals(500.1, growth.totalKm, 0.000001)
        assertEquals(1, growth.stage)
        assertEquals(2, DistanceGrowth(3000.0).stage)
        val restarted = DistanceGrowth(growth.totalKm)
        assertEquals("baseline", restarted.observe(1050.0, 10))
        assertEquals(growth.totalKm, restarted.totalKm, 0.0)
    }

    @Test fun duplicatesResetsJumpsAndGaps() {
        val growth = DistanceGrowth()
        assertEquals("baseline", growth.observe(1000.0, 1000))
        assertEquals("grown", growth.observe(1000.05, 6000))
        assertEquals("out_of_order", growth.observe(null, 5000))
        assertEquals("out_of_order", growth.observe(1001.0, 6000))
        assertEquals("unchanged", growth.observe(1000.05, 7000))
        assertEquals("counter_reset", growth.observe(2.0, 8000))
        assertEquals("implausible_jump", growth.observe(10000.0, 9000))
        assertEquals("gap_too_long", growth.observe(10001.0, 10000 + DistanceGrowth.MAXIMUM_GAP_MS))
        assertEquals(.05, growth.totalKm, .000001)
    }

    @Test fun invalidSamplesAndDisconnectNeverCreditTheMissingInterval() {
        val growth = DistanceGrowth()
        growth.observe(1.0, 1000)
        for ((index, value) in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0).withIndex()) {
            assertEquals("unavailable", growth.observe(value, 2000 + index * 1000L))
        }
        assertEquals("out_of_order", growth.observe(5.0, 4000))
        assertEquals("baseline", growth.observe(10.0, 6000))
        growth.invalidate()
        assertEquals("out_of_order", growth.observe(11.0, 5000))
        assertEquals("baseline", growth.observe(20.0, 7000))
        assertEquals(0.0, growth.totalKm, 0.0)
    }

    @Test fun sameTimestampFailureInvalidatesTheBaselineWithoutAcceptingDuplicates() {
        for (invalid in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val growth = DistanceGrowth(2.0, 200.0)
            assertEquals("baseline", growth.observe(1000.0, 1000))
            assertEquals("out_of_order", growth.observe(null, 999))
            assertEquals("out_of_order", growth.observe(1000.01, 1000))
            assertEquals("unavailable", growth.observe(invalid, 1000))
            assertEquals("out_of_order", growth.observe(1000.02, 1000))
            assertEquals("baseline", growth.observe(1000.05, 2000))
            assertEquals(2.0, growth.totalKm, 0.0)
            assertEquals(200.0, growth.experience, 0.0)
            assertEquals("grown", growth.observe(1000.06, 3000))
            assertEquals(2.01, growth.totalKm, .000001)
        }
    }

    @Test fun invalidInitialProgressIsRejected() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 10_000_001.0)) {
            assertThrows(IllegalArgumentException::class.java) { DistanceGrowth(value) }
        }
        val capped = DistanceGrowth(DistanceGrowth.MAXIMUM_TOTAL_KM)
        capped.observe(1.0, 1000)
        assertEquals("total_limit", capped.observe(1.01, 2000))
    }
}
