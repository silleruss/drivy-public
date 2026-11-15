package io.github.silleruss.drivy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.silleruss.drivy.vehicle.VehicleConnection
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CompanionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyFormRendersMovingAndGreetingFrames() {
        val selected = mutableStateOf(Companion.MOKA)
        val stage = mutableStateOf(0)
        val greeting = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompanionSprite(greeting.value, selected.value, stage.value, "동행 모션", Modifier.size(256.dp))
        }
        for (companion in Companion.entries) for (form in 0..2) {
            compose.runOnIdle { selected.value = companion; stage.value = form; greeting.value = false }
            compose.mainClock.advanceTimeBy(1000)
            val idle = compose.onNodeWithContentDescription("동행 모션").captureToImage().toPixelMap()
            compose.mainClock.advanceTimeBy(400)
            val moving = compose.onNodeWithContentDescription("동행 모션").captureToImage().toPixelMap()
            compose.runOnIdle { greeting.value = true }
            compose.mainClock.advanceTimeBy(16)
            val happy = compose.onNodeWithContentDescription("동행 모션").captureToImage().toPixelMap()
            var motionChanges = 0
            var greetingChanges = 0
            for (y in 0 until idle.height step 3) for (x in 0 until idle.width step 3) {
                if (idle[x, y] != moving[x, y]) motionChanges++
                if (moving[x, y] != happy[x, y]) greetingChanges++
            }
            assertTrue("$companion stage $form must move on screen", motionChanges > 100)
            assertTrue("$companion stage $form must show its greeting frame", greetingChanges > 100)
        }
    }

    @Test fun summaryKeepsEssentialsAndDetailsCloseWhenDrivingIsRestricted() {
        val restricted = mutableStateOf(false)
        compose.setContent {
            CompanionScreen(12.25, 51225.5, 90, restricted.value, "차량 연결 완료", "saved", Companion.TORI)
        }
        compose.onNodeWithContentDescription("단단한 토리 · 2단계").assertIsDisplayed()
        compose.onNodeWithText("51225.5 XP").assertIsDisplayed()
        compose.onNodeWithText("90 / 100").assertIsDisplayed()
        compose.onNodeWithText("12.25 km").assertIsDisplayed()
        compose.onNodeWithContentDescription("진화 진행도").assertExists()
        compose.onNodeWithText(Companion.TORI.concept).assertDoesNotExist()
        compose.onNodeWithText("차량 연결 완료").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        compose.onNodeWithText("자세히").assertHeightIsAtLeast(76.dp).assertWidthIsAtLeast(76.dp)
        compose.onNodeWithText("인사하기").assertHeightIsAtLeast(76.dp).performClick()
        compose.onNodeWithText("반가워요!").assertExists()
        compose.onNodeWithText("자세히").performClick()
        compose.onNodeWithText(Companion.TORI.concept).assertIsDisplayed()
        compose.onNodeWithText("75점 초과 보너스 · 기본 경험치는 차감하지 않아요").assertExists()
        compose.runOnIdle { restricted.value = true }
        compose.onNodeWithText("주차 후에 다시 만나요").assertIsDisplayed()
        compose.onNodeWithText("동행 정보").assertDoesNotExist()
        compose.onNodeWithContentDescription("단단한 토리 · 2단계").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { restricted.value = false }
        compose.onNodeWithText("반가워요!").assertDoesNotExist()
        compose.onNodeWithText("인사하기").assertIsDisplayed()
        val events = mutableListOf<String>()
        compose.runOnIdle {
            val connection = VehicleConnection(InstrumentationRegistry.getInstrumentation().targetContext) { events.add(it) }
            connection.start()
            connection.stop()
        }
        compose.waitForIdle()
        assertTrue("A cancelled start must not open the SDK after stop", events.isEmpty())
    }

    @Test fun allCompanionsEvolveAndRegressWithExperienceWhileDistanceIsPreserved() {
        val experience = mutableStateOf(999.0)
        val score = mutableStateOf<Int?>(null)
        val selected = mutableStateOf(Companion.TORI)
        compose.setContent {
            CompanionScreen(50.01, experience.value, score.value, false, "차량 연결 완료", "new", selected.value)
        }
        compose.onNodeWithText("측정 대기").assertIsDisplayed()
        for (companion in Companion.entries) {
            compose.runOnIdle { selected.value = companion }
            for ((xp, stage) in listOf(49999.0 to 0, 50000.0 to 1, 299999.0 to 1, 300000.0 to 2, 49999.0 to 0, 300001.0 to 2)) {
                compose.runOnIdle { experience.value = xp }
                compose.onNodeWithContentDescription("${companion.forms[stage].name} · ${stage + 1}단계").assertExists()
            }
            compose.onNodeWithText("50.01 km").assertExists()
            compose.onNodeWithText("최종 진화 · 우리의 여정은 계속돼요").assertExists()
        }
        compose.onNodeWithText("인사하기").performClick()
        compose.runOnIdle { selected.value = Companion.TORI; score.value = 40 }
        compose.onNodeWithText("반가워요!").assertDoesNotExist()
        compose.onNodeWithText("40 / 100").assertIsDisplayed()
        compose.onNodeWithText("75점 초과 보너스 · 기본 경험치는 차감하지 않아요").assertDoesNotExist()
    }

    @Test fun missingSpeedPermissionExplainsHowToEnableScoring() {
        val granted = mutableStateOf(false)
        compose.setContent {
            CompanionScreen(2.0, 200.0, null, false, "차량 연결 완료", "saved", Companion.TORI, granted.value)
        }
        compose.onNodeWithText("자세히에서 속도 권한 요청").assertIsDisplayed()
        compose.runOnIdle { granted.value = true }
        compose.onNodeWithText("가감속 기준").assertIsDisplayed()
    }

    @Test fun narrowLargeTextKeepsControlsAndWarningsReachable() {
        val dataStatus = mutableStateOf("차량 연결 완료")
        val expandedWindow = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(if (expandedWindow.value) 1400.dp else 360.dp).height(640.dp)) {
                    CompanionScreen(2.0, 200.0, null, false, dataStatus.value, "save_failed", Companion.TORI)
                }
            }
        }
        compose.onNodeWithText("저장 실패 · 진행을 유지하며 다시 시도해요").assertIsDisplayed()
        val warningBounds = compose.onNodeWithText("저장 실패 · 진행을 유지하며 다시 시도해요").getUnclippedBoundsInRoot()
        val friendBounds = compose.onNodeWithContentDescription("꼬마 토리 · 1단계").getUnclippedBoundsInRoot()
        assertTrue("Save errors must appear before the companion without scrolling", warningBounds.bottom < friendBounds.top)
        assertTrue("The companion must fit inside the narrow window", friendBounds.right - friendBounds.left <= 360.dp)
        compose.onNodeWithText("인사하기").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(76.dp)
        compose.onNodeWithText("측정 대기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2.00 km").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("드라이비 런").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(76.dp)
        compose.onNodeWithText("저장 실패 · 진행을 유지하며 다시 시도해요").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { dataStatus.value = "차량 데이터를 확인할 수 없어요 · 성장은 간직하고 있어요" }
        compose.onNodeWithText("차량 연결 확인 필요 · 거리 반영 대기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("자세히").performScrollTo().performClick()
        compose.onNodeWithText(Companion.TORI.concept).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("차량 데이터를 확인할 수 없어요 · 성장은 간직하고 있어요").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed().performClick()
        compose.onNodeWithText("동행 정보").assertDoesNotExist()
        compose.runOnIdle { expandedWindow.value = true }
        val scoreBounds = compose.onNodeWithText("측정 대기").getUnclippedBoundsInRoot()
        val distanceBounds = compose.onNodeWithText("2.00 km").getUnclippedBoundsInRoot()
        assertTrue("Large text must stack metrics even on a wide window with bounded content", scoreBounds.bottom < distanceBounds.top)
    }

    @Test fun shortWideWindowAndLargeFontCanScrollWithoutClipping() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(960.dp).height(400.dp)) {
                    CompanionScreen(10.0, 1000.0, 50, false, "차량 연결 완료", "corrupt", Companion.PIKO)
                }
            }
        }
        compose.onNodeWithText("인사하기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1000 XP").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("50 / 100").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("기록을 복원하지 못했어요 · 성장 중지 · 원본 기록은 보관하고 있어요").performScrollTo().assertIsDisplayed()
    }
}
