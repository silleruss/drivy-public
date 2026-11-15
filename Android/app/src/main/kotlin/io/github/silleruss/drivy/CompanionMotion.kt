package io.github.silleruss.drivy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

internal data class CompanionPose(val frame: Int, val lift: Float, val tilt: Float, val stretch: Float)

/** phase is one 4.8-second loop; distances are dp and rotation is in degrees. */
internal fun companionPose(companion: Companion, phase: Float, greeting: Boolean): CompanionPose {
    val wave = sin(phase * 2 * PI).toFloat()
    val quick = sin(phase * 4 * PI).toFloat()
    if (greeting) {
        val hop = abs(sin(phase * 12 * PI)).toFloat()
        return CompanionPose(2, -8f * hop, quick * 7f, .025f * hop)
    }
    val blink = if (companion == Companion.NERU) phase in .64f.. .92f else phase in .84f.. .875f
    val (lift, tilt, stretch) = when (companion) {
        Companion.MOKA -> Triple(-2f * quick, 3f * wave, .012f * quick)
        Companion.NURI -> Triple(-6f - 5f * wave, 4f * quick, .015f * wave)
        Companion.PIKO -> Triple(-7f * abs(quick), 2f * quick, .035f * quick)
        Companion.NERU -> Triple(-1f * wave, 3f + 3f * wave, .016f * wave)
        Companion.TORI -> Triple(0f, 1.5f * wave, .018f * quick)
        Companion.RAPI -> Triple(-3f * quick, 4f * wave, .014f * quick)
    }
    return CompanionPose(if (blink) 1 else 0, lift, tilt, stretch)
}
