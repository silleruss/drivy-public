package io.github.silleruss.drivy

import org.junit.Assert.*
import org.junit.Test

class DrivingScoreTest {
    @Test fun failureAtTheSameMillisecondInvalidatesTheScore() {
        val score = DrivingScore()
        for (second in 0..10) score.observe(72.0, second * 1000L)
        assertEquals(100, score.current(10_000))
        score.observe(null, 9_999)
        assertEquals(100, score.current(10_000))
        score.observe(null, 10_000)
        assertNull(score.current(10_000))
        assertNull(score.forInterval(0, 10_000))
        score.observe(72.0, 10_000)
        assertNull(score.current(10_000))
    }

    @Test fun acceptedSampleControlsTheExpiryDeadline() {
        val score = DrivingScore()
        assertNull(score.sampleExpiresAtMs)
        for (second in 0..10) score.observe(72.0, second * 1000L)
        assertEquals(12_001L, score.sampleExpiresAtMs)
        score.observe(72.0, 10_100)
        score.observe(72.0, 9000)
        assertEquals(12_001L, score.sampleExpiresAtMs)
        assertEquals(100, score.current(12_000))
        assertNull(score.current(12_001))
        score.observe(72.0, 11_000)
        assertEquals(13_001L, score.sampleExpiresAtMs)
        score.invalidate()
        assertNull(score.sampleExpiresAtMs)
    }

    @Test fun scoreRequiresContinuousFreshMovingSamples() {
        val score = DrivingScore()
        assertNull(score.current(0))
        for (second in 0..9) score.observe(72.0, second * 1000L)
        assertNull(score.current(9000))
        score.observe(72.0, 10_000)
        assertEquals(100, score.current(10_000))
        assertEquals(100, score.forInterval(0, 10_000))
        assertNull(score.current(9999))
        assertNull(score.current(12_001))
        score.observe(72.0, 13_000)
        assertNull(score.forInterval(0, 13_000))
        for (second in 14..23) score.observe(72.0, second * 1000L)
        assertEquals(100, score.forInterval(13_000, 23_000))
        assertNull(score.forInterval(12_000, 23_000))
        assertNull(score.forInterval(23_000, 23_000))
    }

    @Test fun thresholdJitterCountsOneManeuverUntilSmoothAgain() {
        val score = DrivingScore()
        var speed = 36.0
        score.observe(speed, 0)
        for ((index, acceleration) in listOf(3.1, 2.9, 3.1).withIndex()) {
            speed += acceleration * 3.6
            score.observe(speed, (index + 1) * 1000L)
        }
        assertEquals(20.0, score.penalty, 0.0)
        score.observe(speed, 4000)
        score.observe(speed + 14.4, 5000)
        assertTrue(score.penalty in 39.0..40.0)
    }

    @Test fun repeatedHarshBrakingLowersScoreAndSmoothDistanceRecovers() {
        val score = DrivingScore()
        for (second in 0..10) score.observe(144.0, second * 1000L)
        var speed = 144.0
        for (second in 11..16) {
            if (second % 2 == 1) speed -= 14.4
            score.observe(speed, second * 1000L)
        }
        assertTrue(score.current(16_000)!! < 50)
        val penalty = score.penalty
        val restored = DrivingScore(penalty)
        assertNull(restored.current(0))
        for (second in 0..200) restored.observe(speed, second * 1000L)
        assertTrue(restored.penalty < penalty)
        assertTrue(restored.current(200_000)!! > score.current(16_000)!!)
    }

    @Test fun missingInvalidDuplicateAndOutOfOrderSamplesNeverCreateSafeScore() {
        for (invalid in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 241.0)) {
            val score = DrivingScore(60.0)
            for (second in 0..10) score.observe(72.0, second * 1000L)
            val before = score.penalty
            score.observe(240.0, 9000)
            assertEquals(before, score.penalty, 0.0)
            score.observe(invalid, 11_000)
            assertNull(score.current(11_000))
            score.observe(72.0, 12_000)
            assertNull(score.forInterval(10_000, 12_000))
        }
        val impossible = DrivingScore()
        impossible.observe(0.0, 0)
        impossible.observe(240.0, 1000)
        assertEquals(0.0, impossible.penalty, 0.0)
        assertNull(impossible.current(1000))
        val parked = DrivingScore()
        for (second in 0..20) parked.observe(0.0, second * 1000L)
        assertNull(parked.current(20_000))
    }
}
