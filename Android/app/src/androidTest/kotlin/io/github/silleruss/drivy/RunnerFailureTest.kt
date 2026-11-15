package io.github.silleruss.drivy

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class RunnerFailureTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun lostOrMissingUxNeverContinuesGame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = InstrumentationRegistry.getArguments().getString("runner_failure")
        assumeTrue(context.packageName == "io.github.silleruss.drivy.runnerprobe.uitest" && fixture in listOf("uxerror", "uxtimeout"))
        context.getFileStreamPath("runner-scores-pattern-v2.json").delete()
        val initial = PetProgress(2.0, 850.0, Companion.NURI)
        assertTrue(PetSaveStore(context.filesDir).save(initial))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            if (fixture == "uxtimeout") {
                compose.waitUntil(30000) { compose.onAllNodesWithText("주차 후에 다시 만나요").fetchSemanticsNodes().isNotEmpty() }
                SystemClock.sleep(3000)
                compose.onNodeWithText("드라이비 런").assertDoesNotExist()
            } else {
                compose.waitUntil(30000) { compose.onAllNodesWithText("드라이비 런").fetchSemanticsNodes().isNotEmpty() }
                compose.mainClock.autoAdvance = false
                compose.onNodeWithText("드라이비 런").performScrollTo().performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForRunnerReady()
                compose.onNodeWithText("시작").performScrollTo().performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText("점프").assertExists()
                val deadline = SystemClock.uptimeMillis() + 10000
                var stopped = false
                while (!stopped && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val getInGame = MainActivity::class.java.getDeclaredMethod("getInGame").apply { isAccessible = true }
                        stopped = getInGame.invoke(activity) == false
                    }
                    if (!stopped) SystemClock.sleep(50)
                }
                assertTrue("Injected UX error must discard the game", stopped)
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText("점프").assertDoesNotExist()
                compose.onNodeWithText("주차 후에 다시 만나요").assertExists()
            }
        }
        assertFalse(context.getFileStreamPath("runner-scores-pattern-v2.json").exists())
        assertEquals(initial, PetSaveStore(context.filesDir).load())
    }
}
