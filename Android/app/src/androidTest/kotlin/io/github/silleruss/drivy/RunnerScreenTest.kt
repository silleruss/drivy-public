package io.github.silleruss.drivy

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class RunnerScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun selectionIsRequiredBeforeGameEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "io.github.silleruss.drivy.runnerprobe.uitest")
        context.getFileStreamPath("pet-progress.json").delete()
        context.getFileStreamPath("pet-progress.json.bak").delete()
        context.getSharedPreferences("companion", 0).edit().clear().commit()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            compose.waitUntil(30000) { compose.onAllNodesWithText("처음 함께할 드라이비를 골라주세요").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("드라이비 런").assertDoesNotExist()
            compose.onNodeWithText("모카와 동행 시작").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("드라이비 런").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun rankingSurvivesProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "io.github.silleruss.drivy.runnerprobe.uitest" &&
            InstrumentationRegistry.getArguments().getString("verify_ranking_restart") == "true")
        val records = RunnerScores(context.filesDir, "runner-scores-pattern-v2.json").load()
        assertEquals(1, records.size)
        assertEquals(Companion.RAPI, records.single().companion)
        assertTrue(records.single().score > 0)
    }

    @Test fun failedSaveKeepsPreviousRankingVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "io.github.silleruss.drivy.runnerprobe.uitest")
        val scoreFile = context.getFileStreamPath("runner-scores-pattern-v2.json")
        val blocked = context.getFileStreamPath("runner-scores-pattern-v2.json.new")
        scoreFile.delete()
        blocked.deleteRecursively()
        RunnerScores(context.filesDir, "runner-scores-pattern-v2.json").add(RunnerRecord(99, Companion.NURI))
        assertTrue(blocked.mkdir())
        java.io.File(blocked, "keep").writeText("block")
        assertTrue(PetSaveStore(context.filesDir).save(PetProgress(companion = Companion.MOKA)))
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                compose.waitUntil(30000) { compose.onAllNodesWithText("드라이비 런").fetchSemanticsNodes().isNotEmpty() }
                compose.mainClock.autoAdvance = false
                compose.onNodeWithText("드라이비 런").performScrollTo().performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForRunnerReady()
                compose.onNodeWithText("시작").performScrollTo().performClick()
                compose.mainClock.advanceTimeBy(4000)
                compose.waitUntil(10000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodesWithText("기록을 저장하지 못했어요", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("최고 기록 99점").assertExists()
            }
            assertEquals(listOf(RunnerRecord(99, Companion.NURI)), RunnerScores(context.filesDir, "runner-scores-pattern-v2.json").load())
        } finally { blocked.deleteRecursively() }
    }

    @Test fun gameOverShowsAndRestoresRanking() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "io.github.silleruss.drivy.runnerprobe.uitest")
        context.getFileStreamPath("runner-scores-pattern-v2.json").delete()
        assertTrue(PetSaveStore(context.filesDir).save(PetProgress(companion = Companion.RAPI)))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            compose.waitUntil(30000) { compose.onAllNodesWithText("자세히").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.autoAdvance = false
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("receive", String::class.java).apply { isAccessible = true }
                    .invoke(activity, """{"kind":"ux","requires_do":false}""")
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("드라이비 런").performScrollTo().performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForRunnerReady()
            compose.onNodeWithText("시작").performScrollTo().performClick()
            compose.mainClock.advanceTimeBy(2120)
            compose.onNodeWithText("점프").performClick()
            compose.mainClock.advanceTimeBy(750)
            compose.onNodeWithText("점수 17").assertExists()
            compose.mainClock.advanceTimeBy(3500)
            compose.onNodeWithText("게임 종료").assertExists()
            compose.onNodeWithText("개인 랭킹").assertExists()
            compose.waitUntil(10000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithText("최고 기록", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("라피", substring = true).assertExists()
            assertEquals(1, RunnerScores(context.filesDir, "runner-scores-pattern-v2.json").load().size)
            compose.onNodeWithText("다시 시작").performScrollTo().performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("점프").assertExists()
        }
        assertEquals(1, RunnerScores(context.filesDir, "runner-scores-pattern-v2.json").load().size)
    }

    @Test fun activityStopsGameOnRestrictionErrorAndPause() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "io.github.silleruss.drivy.runnerprobe.uitest")
        context.getFileStreamPath("runner-scores-pattern-v2.json").delete()
        val initial = PetProgress(2.0, 850.0, Companion.TORI)
        assertTrue(PetSaveStore(context.filesDir).save(initial))
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitUntil(30000) { compose.onAllNodesWithText("자세히").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.autoAdvance = false
            fun event(json: String) = scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("receive", String::class.java).apply { isAccessible = true }.invoke(activity, json)
            }
            fun enter() {
                event("""{"kind":"ux","requires_do":false}""")
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText("드라이비 런").performScrollTo().performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForRunnerReady()
                compose.onNodeWithText("시작").performScrollTo().performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText("점프").assertExists()
            }
            enter()
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("connectVehicle").apply { isAccessible = true }.invoke(activity)
                val getter = MainActivity::class.java.getDeclaredMethod("getInGame").apply { isAccessible = true }
                assertEquals("Vehicle reconnect must discard the current game", false, getter.invoke(activity))
            }
            compose.mainClock.advanceTimeByFrame()
            enter()
            event("""{"kind":"ux","requires_do":true}""")
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("점프").assertDoesNotExist()
            event("""{"kind":"ux","requires_do":false}""")
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("점프").assertDoesNotExist()
            enter()
            event("""{"kind":"ux_error"}""")
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("점프").assertDoesNotExist()
            enter()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.mainClock.advanceTimeByFrame()
            event("""{"kind":"ux","requires_do":false}""")
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("점프").assertDoesNotExist()
        }
        assertEquals(initial, PetSaveStore(context.filesDir).load())
        assertFalse(context.getFileStreamPath("runner-scores-pattern-v2.json").exists())
    }

    @Test fun backAndRecreationDiscardTheRun() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "io.github.silleruss.drivy.runnerprobe.uitest")
        context.getFileStreamPath("runner-scores-pattern-v2.json").delete()
        assertTrue(PetSaveStore(context.filesDir).save(PetProgress(companion = Companion.MOKA)))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            compose.waitUntil(30000) { compose.onAllNodesWithText("드라이비 런").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.autoAdvance = false
            fun parked() {
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("receive", String::class.java).apply { isAccessible = true }
                        .invoke(activity, """{"kind":"ux","requires_do":false}""")
                }
                compose.mainClock.advanceTimeByFrame()
            }
            fun enter() {
                parked()
                compose.onNodeWithText("드라이비 런").performScrollTo().performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForRunnerReady()
                compose.onNodeWithText("시작").performScrollTo().performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText("점프").assertExists()
            }
            enter()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("점프").assertDoesNotExist()
            enter()
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("onUserLeaveHint").apply { isAccessible = true }.invoke(activity)
                val getter = MainActivity::class.java.getDeclaredMethod("getInGame").apply { isAccessible = true }
                assertEquals("Minimizing must discard the game before a small window opens", false, getter.invoke(activity))
            }
            compose.mainClock.advanceTimeByFrame()
            enter()
            scenario.onActivity { activity ->
                val config = android.content.res.Configuration(activity.resources.configuration)
                activity.onPictureInPictureModeChanged(true, config)
                val getter = MainActivity::class.java.getDeclaredMethod("getInGame").apply { isAccessible = true }
                assertEquals("PiP must not retain the game", false, getter.invoke(activity))
                activity.onPictureInPictureModeChanged(false, config)
            }
            compose.mainClock.advanceTimeByFrame()
            enter()
            scenario.recreate()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("점프").assertDoesNotExist()
        }
        assertFalse(context.getFileStreamPath("runner-scores-pattern-v2.json").exists())
    }

    @Test fun homeOrLeavingForegroundDoesNotSaveAnInterruptedRun() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName == "io.github.silleruss.drivy.runnerprobe.uitest")
        context.getFileStreamPath("runner-scores-pattern-v2.json").delete()
        CompanionDisplay(context).enabled = false
        assertTrue(PetSaveStore(context.filesDir).save(PetProgress(companion = Companion.TORI)))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            compose.waitUntil(30000) { compose.onAllNodesWithText("드라이비 런").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("드라이비 런").performScrollTo().performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForRunnerReady()
            compose.onNodeWithText("시작").performScrollTo().performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("점프").assertExists()
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            val deadline = android.os.SystemClock.uptimeMillis() + 3000
            var stopped = false
            var stillResumed = false
            while (!stopped && android.os.SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val getter = MainActivity::class.java.getDeclaredMethod("getInGame").apply { isAccessible = true }
                    stopped = getter.invoke(activity) == false
                    stillResumed = activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                }
                if (!stopped) android.os.SystemClock.sleep(50)
            }
            if (!stopped && stillResumed) {
                // Pleos Home can leave the Activity visible inside its TaskView.
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    val getter = MainActivity::class.java.getDeclaredMethod("getInGame").apply { isAccessible = true }
                    stopped = getter.invoke(activity) == false
                }
            }
            assertTrue("Leaving the foreground must discard the game", stopped)
        }
        assertFalse(context.getFileStreamPath("runner-scores-pattern-v2.json").exists())
    }
}
