package io.github.silleruss.drivy

import kotlin.math.abs
import kotlin.math.roundToInt

/** A game score for observed acceleration/braking; it cannot determine traffic-law compliance. */
class DrivingScore(penalty: Double = 0.0) {
    private var lastSpeed: Double? = null
    private var lastMs = -1L
    private var latestMs = -1L
    private var coverageStartMs = -1L
    private var coveredKm = 0.0
    var penalty = penalty
        private set
    val sampleExpiresAtMs: Long? get() = if (lastSpeed == null) null else lastMs + MAXIMUM_SAMPLE_GAP_MS + 1
    private var maneuver = 0

    init { require(penalty.isFinite() && penalty in 0.0..100.0) }

    fun observe(speedKmh: Double?, elapsedMs: Long) {
        if (elapsedMs < latestMs) return
        if (speedKmh == null || !speedKmh.isFinite() || speedKmh !in 0.0..240.0 || elapsedMs < 0) {
            latestMs = elapsedMs
            invalidate()
            return
        }
        if (elapsedMs == latestMs) return
        latestMs = elapsedMs
        val previous = lastSpeed
        val duration = elapsedMs - lastMs
        if (previous != null && duration < 200) return
        if (previous == null || duration > MAXIMUM_SAMPLE_GAP_MS) {
            invalidate()
            coverageStartMs = elapsedMs
        } else {
            val acceleration = (speedKmh - previous) / 3.6 / (duration / 1000.0)
            if (abs(acceleration) > 20) {
                invalidate()
                return
            }
            val distance = (speedKmh + previous) / 2 * duration / 3_600_000.0
            coveredKm += distance
            val current = when {
                acceleration >= 3.0 -> 1
                acceleration <= -3.5 -> -1
                abs(acceleration) < 1.0 -> 0
                else -> maneuver
            }
            // shortcut: prototype thresholds require vehicle calibration before production scoring.
            if (current != 0 && current != maneuver) {
                penalty = (penalty + if (current == 1) 20 else 25).coerceAtMost(100.0)
            } else if (current == 0) {
                penalty = (penalty - distance * 2).coerceAtLeast(0.0)
            }
            maneuver = current
        }
        lastSpeed = speedKmh
        lastMs = elapsedMs
    }

    fun current(elapsedMs: Long): Int? {
        if (lastSpeed == null || elapsedMs - lastMs !in 0..MAXIMUM_SAMPLE_GAP_MS ||
            lastMs - coverageStartMs < 10_000 || coveredKm < 0.1) return null
        return (100 - penalty).roundToInt().coerceIn(0, 100)
    }

    fun forInterval(startMs: Long, endMs: Long): Int? =
        if (startMs < coverageStartMs || startMs >= endMs) null else current(endMs)

    fun invalidate() {
        lastSpeed = null
        coverageStartMs = -1
        coveredKm = 0.0
        maneuver = 0
    }

    companion object {
        const val MAXIMUM_SAMPLE_GAP_MS = 2000L
    }
}
