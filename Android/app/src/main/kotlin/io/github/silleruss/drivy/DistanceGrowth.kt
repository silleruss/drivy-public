package io.github.silleruss.drivy

/** Validates distance independently of the observed driving-habit score. */
class DistanceGrowth(totalKm: Double = 0.0, experience: Double = totalKm * 100) {
    var totalKm = totalKm
        private set
    var experience = experience
        private set
    val stage: Int get() = stageFor(experience)
    private var hasBaseline = false
    private var lastKm = 0.0
    private var lastMs = 0L
    private var latestMs = -1L
    private var distanceAllowance = 0.1

    init {
        require(totalKm.isFinite() && totalKm in 0.0..MAXIMUM_TOTAL_KM)
        require(experience.isFinite() && experience in 0.0..2_000_000_000.0)
    }

    fun observe(odometerKm: Double?, elapsedMs: Long, drivingScore: Int? = null): String {
        require(drivingScore == null || drivingScore in 0..100)
        if (elapsedMs < latestMs) return "out_of_order"
        if (odometerKm == null || !odometerKm.isFinite() || odometerKm < 0 || elapsedMs < 0) {
            latestMs = elapsedMs
            invalidate()
            return "unavailable"
        }
        if (elapsedMs == latestMs) return "out_of_order"
        latestMs = elapsedMs
        val hadBaseline = hasBaseline
        val delta = odometerKm - lastKm
        val duration = elapsedMs - lastMs
        lastKm = odometerKm
        lastMs = elapsedMs
        hasBaseline = true
        if (!hadBaseline || delta < 0 || duration > MAXIMUM_GAP_MS) distanceAllowance = 0.1
        if (!hadBaseline) return "baseline"
        if (delta < 0) return "counter_reset"
        if (duration > MAXIMUM_GAP_MS) return "gap_too_long"
        val available = distanceAllowance + 240 * duration / 3_600_000.0
        // Share the rounding allowance across samples without banking parked time.
        distanceAllowance = available.coerceAtMost(0.1)
        if (delta == 0.0) return "unchanged"
        if (delta > available) return "implausible_jump"
        distanceAllowance = (available - delta).coerceAtMost(0.1)
        if (totalKm + delta > MAXIMUM_TOTAL_KM) return "total_limit"
        totalKm += delta
        val multiplier = drivingScore?.let { ((it - 50) / 25.0).coerceAtLeast(1.0) } ?: 1.0
        experience = (experience + delta * 100 * multiplier).coerceIn(0.0, 2_000_000_000.0)
        return "grown"
    }

    fun invalidate() { hasBaseline = false }

    companion object {
        const val SECOND_STAGE_XP = 50_000.0
        const val THIRD_STAGE_XP = 300_000.0
        fun stageFor(experience: Double) = if (experience >= THIRD_STAGE_XP) 2 else if (experience >= SECOND_STAGE_XP) 1 else 0
        const val MAXIMUM_GAP_MS = 30 * 60 * 1000L
        const val MAXIMUM_TOTAL_KM = 10_000_000.0
    }
}
