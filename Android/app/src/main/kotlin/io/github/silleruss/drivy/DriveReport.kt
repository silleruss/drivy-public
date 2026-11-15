package io.github.silleruss.drivy

import kotlin.math.abs

/** Aggregates only contiguous, valid speed samples; this is not a safety assessment. */
internal class DriveReportAccumulator {
    var accelerations = 0
        private set
    var decelerations = 0
        private set
    var coveredMs = 0L
        private set
    var hasGap = false
        private set
    private var lastSpeed: Double? = null
    private var lastMs = -1L
    private var latestMs = -1L
    private var maneuver = 0

    fun observeSpeed(speedKmh: Double?, elapsedMs: Long): Boolean {
        if (elapsedMs < 0) { invalidate(); return false }
        if (elapsedMs < latestMs) return false
        if (speedKmh == null || !speedKmh.isFinite() || speedKmh !in 0.0..240.0) {
            latestMs = elapsedMs
            invalidate()
            return false
        }
        if (elapsedMs == latestMs) return false
        latestMs = elapsedMs
        val previous = lastSpeed
        val duration = elapsedMs - lastMs
        if (previous != null && duration < 200) return false
        if (previous != null && duration <= DrivingScore.MAXIMUM_SAMPLE_GAP_MS) {
            val acceleration = (speedKmh - previous) / 3.6 / (duration / 1000.0)
            if (abs(acceleration) > 20) {
                invalidate()
                return false
            }
            coveredMs += duration
            // shortcut: prototype thresholds require vehicle calibration before user-facing classification.
            val current = when {
                acceleration >= 3.0 -> 1
                acceleration <= -3.5 -> -1
                abs(acceleration) < 1.0 -> 0
                else -> maneuver
            }
            if (current != 0 && current != maneuver) {
                if (current == 1) accelerations++ else decelerations++
            }
            maneuver = current
        } else if (previous != null) {
            invalidate()
        }
        lastSpeed = speedKmh
        lastMs = elapsedMs
        return true
    }

    fun invalidate() {
        hasGap = true
        lastSpeed = null
        maneuver = 0
    }
}
