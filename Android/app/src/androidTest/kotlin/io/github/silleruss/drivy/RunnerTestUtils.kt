package io.github.silleruss.drivy

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText

internal fun ComposeTestRule.waitForRunnerReady() {
    waitUntil(30000) {
        if (!mainClock.autoAdvance) mainClock.advanceTimeByFrame()
        onAllNodesWithText("시작").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) }
    }
}
