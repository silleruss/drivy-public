package io.github.silleruss.drivy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class RunnerLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allFriendsAndStagesCanJumpAtNarrowAndWideSizes() {
        val companion = mutableStateOf(Companion.MOKA)
        val stage = mutableStateOf(0)
        val width = mutableStateOf(360.dp)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(width.value).height(640.dp).testTag("viewport")) {
                    RunnerScreen(companion.value, stage.value) {}
                }
            }
        }
        for (screenWidth in listOf(360.dp, 720.dp, 1400.dp)) {
            compose.runOnIdle { width.value = screenWidth }
            compose.mainClock.advanceTimeByFrame()
            for (friend in Companion.entries) for (form in 0..2) {
                compose.runOnIdle { companion.value = friend; stage.value = form }
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithContentDescription("${friend.displayName} 달리기").assertExists()
            }
            assertEquals("game fills the available viewport",
                compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot(),
                compose.onNodeWithTag("runner-world").getUnclippedBoundsInRoot())
        }
        compose.waitForRunnerReady()
        compose.onNodeWithText("시작").performScrollTo().assertHeightIsAtLeast(64.dp).performClick()
        compose.mainClock.advanceTimeByFrame()
        val world = compose.onNodeWithTag("runner-world")
        fun pixels() = world.captureToImage().toPixelMap().let { image ->
            (0 until image.height step 4).flatMap { y -> (0 until image.width step 4).map { x -> image[x, y] } }
        }
        val running = pixels()
        compose.onNodeWithText("점프").assertHeightIsAtLeast(72.dp).performClick()
        compose.mainClock.advanceTimeBy(120)
        assertTrue("jump and obstacle movement must redraw", running != pixels())
        for (friend in Companion.entries) for (form in 0..2) {
            compose.runOnIdle { companion.value = friend; stage.value = form }
            compose.mainClock.advanceTimeBy(16)
            compose.onNodeWithContentDescription("${friend.displayName} 달리기").assertIsDisplayed()
        }
        val frames = mutableSetOf<Int>()
        repeat(12) {
            compose.mainClock.advanceTimeBy(32)
            frames += pixels().hashCode()
        }
        assertEquals("world must visibly animate at least every 32ms", 12, frames.size)
        compose.onNodeWithText("나가기").assertIsDisplayed()
    }

    @Test fun stagesContinueBeyondThreeWithoutStopping() {
        assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "io.github.silleruss.drivy.runnerprobe.uitest")
        compose.mainClock.autoAdvance = false
        compose.setContent { RunnerScreen(Companion.MOKA, 0, randomSeed = 42) {} }
        compose.mainClock.advanceTimeByFrame()
        val bounds = compose.onNodeWithTag("runner-world").getUnclippedBoundsInRoot()
        val halfWidth = (minOf((bounds.right - bounds.left).value * .15f, (bounds.bottom - bounds.top).value * .24f) * .55f / (bounds.right - bounds.left).value / 4).toDouble()
        val model = RunnerGame(halfWidth, kotlin.random.Random(42))
        compose.waitForRunnerReady()
        compose.onNodeWithText("시작").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeByFrame()
        var steps = 0
        while (model.stage < 4 && !model.over && steps++ < 2500) {
            val target = if (model.obstacle + model.obstacleWidth < model.playerLeft) model.obstacle + (model.secondOffset ?: 0.0) else model.obstacle
            if (target < model.playerRight + model.speed * .22 && model.height == 0.0) {
                model.jump()
                compose.onNodeWithText("점프").performSemanticsAction(SemanticsActions.OnClick) { it() }
            }
            compose.mainClock.advanceTimeBy(32)
            model.advance(.032)
        }
        assertFalse("run should continue beyond stage three", model.over)
        assertTrue(model.stage >= 4)
        compose.onNodeWithText("스테이지 4 ·", substring = true).assertExists()
        compose.onNodeWithText("점프").assertExists()
    }

    @Test fun shortLandscapeKeepsStartAndExitReachable() {
        assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "io.github.silleruss.drivy.runnerprobe.uitest")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(720.dp).height(360.dp)) { RunnerScreen(Companion.TORI, 2) {} }
            }
        }
        compose.onNodeWithText("시작").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        compose.mainClock.autoAdvance = false
        compose.waitForRunnerReady()
        compose.onNodeWithText("시작").performClick()
        compose.mainClock.advanceTimeBy(4000)
        compose.waitUntil(10000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText("최고 기록", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("다시 시작").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        compose.onNodeWithText("나가기").assertIsDisplayed().assertHeightIsAtLeast(64.dp)
    }
}
