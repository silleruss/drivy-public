package io.github.silleruss.drivy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DriveReportsPanelTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()
    private fun record() = DriveRecord("test", now - 2000, now - 1000, 0.05, true,
        1000, true, 1, 1, "stopped", DriveGoal.START)

    @Test fun consentAndDeletionAreExplicitAndNarrowLargeTextControlsRemainReachable() {
        val journal = mutableStateOf(DriveJournal())
        var deleted = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    CompanionScreen(2.0, 200.0, null, false, "차량 연결 완료", "saved", Companion.TORI,
                        reportContent = {
                            DriveReportsPanel(journal.value, "saved", Companion.TORI,
                                { journal.value = journal.value.copy(enabled = it) },
                                { journal.value = journal.value.copy(goal = it) }, {}, { deleted = true; journal.value = DriveJournal() }, {},
                                onEnvironment = { journal.value = journal.value.copy(environment = it) })
                        })
                }
            }
        }
        assertFalse(journal.value.enabled)
        compose.onNodeWithText("기록·목표·설정").performScrollTo().performClick()
        compose.onNodeWithText("기록·분석에 동의하고 시작").performScrollTo().assertHeightIsAtLeast(76.dp).performClick()
        compose.runOnIdle { assertTrue(journal.value.enabled) }
        compose.onNodeWithText("부드러운 출발").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(DriveGoal.START, journal.value.goal) }
        compose.onNodeWithText("시내 위주", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(DriveEnvironment.CITY, journal.value.environment) }
        compose.onNodeWithText("기록·입력·목표 전체 삭제").performScrollTo().performClick()
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertFalse(deleted) }
        compose.onNodeWithText("기록·입력·목표 전체 삭제").performScrollTo().performClick()
        compose.onNodeWithText("전체 삭제 확인").performClick()
        compose.runOnIdle { assertTrue(deleted); assertEquals(DriveJournal(), journal.value) }
    }

    @Test fun userContextAndReflectionDoNotModifyObservedFacts() {
        val journal = mutableStateOf(DriveJournal(true, records = listOf(record())))
        compose.setContent {
            DriveReportsPanel(journal.value, "saved", Companion.NERU, {}, {}, { edited ->
                journal.value = journal.value.copy(records = listOf(edited))
            }, {}, {})
        }
        compose.onNodeWithText("기록·목표·설정").performClick()
        compose.onNode(hasText("0.05 km", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("내 기록", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("고속도로 위주", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("피해야 하는 상황", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("도움 됨", substring = false).performScrollTo().performClick()
        compose.runOnIdle {
            val actual = journal.value.records.single()
            assertEquals(DriveDriver.ME, actual.driver)
            assertEquals(DriveContext.AVOIDANCE, actual.context)
            assertEquals(DriveReflection.HELPFUL, actual.reflection)
            assertEquals(DriveEnvironment.HIGHWAY, actual.environment)
            assertFalse(actual.includedInPersonalAnalysis)
            assertEquals(record().distanceKm, actual.distanceKm, 0.0)
        }
    }

    @Test fun latestRecordOpensDetailsAndSavingDoesNotMoveControls() {
        val status = mutableStateOf("saved")
        compose.setContent {
            DriveReportsPanel(DriveJournal(true, records = listOf(record())), status.value, Companion.TORI,
                {}, {}, {}, {}, {})
        }
        val before = compose.onNodeWithText("기록·목표·설정").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { status.value = "saving" }
        val after = compose.onNodeWithText("기록·목표·설정").fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 0.01f)
        compose.onNodeWithText("최근 기록 상세 보기").performClick()
        compose.onNodeWithText("측정 기록 상세").assertIsDisplayed()
        compose.onNode(hasText("아래로 스크롤하면 더 볼 수 있어요 ↓") or hasText("모든 내용이 표시돼요")).assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed().performClick()
    }

    @Test fun wideDetailKeepsRecordListAndSelectionVisible() {
        val older = record().copy(id = "older", distanceKm = 1.25)
        compose.setContent {
            DriveReportsPanel(DriveJournal(true, records = listOf(older, record())), "saved", Companion.TORI,
                {}, {}, {}, {}, {})
        }
        compose.onNodeWithText("최근 기록 상세 보기").performClick()
        compose.onNodeWithText("기록 목록", substring = false).assertIsDisplayed()
        compose.onNode(hasText("1.25 km", substring = true) and hasClickAction()).performClick()
        compose.onNodeWithText("관측 거리 1.25 km", substring = true).assertIsDisplayed()
        compose.onNodeWithText("운전자 모름").performScrollTo().assertIsSelected()
    }

    @Test fun restrictionClosesReportDialogAndRemovesEveryControl() {
        val restricted = mutableStateOf(false)
        compose.setContent {
            CompanionScreen(2.0, 200.0, null, restricted.value, "차량 연결 완료", "saved", Companion.TORI,
                reportContent = { DriveReportsPanel(DriveJournal(true, records = listOf(record())), "saved", Companion.TORI, {}, {}, {}, {}, {}) })
        }
        compose.onNodeWithText("기록·목표·설정").performScrollTo().performClick()
        compose.onNodeWithText("기록·분석 동의 중단").assertExists()
        compose.runOnIdle { restricted.value = true }
        compose.onNodeWithText("주차 후에 다시 만나요").assertIsDisplayed()
        compose.onNodeWithText("기록·분석 동의 중단").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun corruptJournalAllowsExplicitDeletionButCannotEnableRecording() {
        var enabled = false
        var deleted = false
        compose.setContent { DriveReportsPanel(DriveJournal(), "corrupt", Companion.TORI, { enabled = it }, {}, {}, { deleted = true }, {}) }
        compose.onNodeWithText("기록·목표·설정").performClick()
        compose.onNodeWithText("기록·분석에 동의하고 시작").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("기록·입력·목표 전체 삭제").performScrollTo().performClick()
        compose.onNodeWithText("전체 삭제 확인").performClick()
        compose.runOnIdle { assertFalse(enabled); assertTrue(deleted) }
    }
}
