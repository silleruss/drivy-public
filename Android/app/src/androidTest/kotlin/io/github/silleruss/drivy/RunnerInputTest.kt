package io.github.silleruss.drivy

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class RunnerInputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun heldAndTextTranslatedSpaceMatchHeldPointerWhileTimedTapsStayShort() {
        val attempt = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { key(attempt.intValue) { RunnerScreen(Companion.TORI, 0, randomSeed = 42) {} } }
        fun start() {
            compose.runOnIdle { attempt.intValue++ }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForRunnerReady()
            compose.onNodeWithText("시작").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeByFrame()
        }
        var downAt = 0L
        fun space(down: Boolean, repeat: Int = 0, pulseMs: Long? = null) {
            val now = SystemClock.uptimeMillis()
            if (down && repeat == 0) downAt = now
            val eventTime = if (pulseMs != null) downAt + pulseMs else if (!down) maxOf(now, downAt + 16) else now
            val event = KeyEvent(downAt, eventTime, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_SPACE, repeat, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            compose.waitForIdle()
        }
        fun snapshot() = compose.onNodeWithTag("runner-world").captureToImage()
        start()
        space(true)
        compose.mainClock.advanceTimeBy(192)
        space(true, 1)
        compose.mainClock.advanceTimeBy(208)
        val heldKey = snapshot()
        space(false)
        val textTranslatedKeys = (0L..1L).map { pulseMs ->
            start()
            space(true); space(false, pulseMs = pulseMs)
            compose.mainClock.advanceTimeBy(400)
            snapshot()
        }
        start()
        space(true); space(false)
        compose.mainClock.advanceTimeBy(400)
        val tappedKey = snapshot()
        start()
        compose.onNodeWithText("점프").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        val heldPointer = snapshot()
        compose.onNodeWithText("점프").performTouchInput { up() }
        fun difference(first: ImageBitmap, second: ImageBitmap): Double {
            val a = first.toPixelMap(); val b = second.toPixelMap()
            var total = 0.0
            for (y in 0 until a.height step 3) for (x in (a.width * .12).toInt()..(a.width * .33).toInt() step 3) {
                total += abs(a[x, y].red - b[x, y].red) + abs(a[x, y].green - b[x, y].green) + abs(a[x, y].blue - b[x, y].blue)
            }
            return total
        }
        val heldDifference = difference(heldKey, heldPointer)
        val tappedDifference = difference(tappedKey, heldPointer)
        assertTrue("Held space must match held pointer, rather than a tap: held=$heldDifference tap=$tappedDifference",
            heldDifference < tappedDifference * .25)
        for ((pulseMs, translated) in textTranslatedKeys.withIndex()) {
            val translatedDifference = difference(translated, heldPointer)
            assertTrue("A ${pulseMs}ms emulator keypress must not truncate the jump: translated=$translatedDifference tap=$tappedDifference",
                translatedDifference < tappedDifference * .25)
        }
    }
}
