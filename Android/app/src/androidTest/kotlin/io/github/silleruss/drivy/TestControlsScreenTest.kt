package io.github.silleruss.drivy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TestControlsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun appliesVehicleValuesWithoutChangingGrowth() {
        var applied: PetProgress? = null
        compose.setContent {
            TestControls(PetProgress(3.0, 42.0, Companion.MOKA, 1.0), "saved", true,
                { applied = it }, {}, {}, {})
        }
        compose.onNodeWithText("차량 데이터 값 설정").performClick()
        compose.onNodeWithText("누적 거리 km").performTextReplacement("12.5")
        compose.onNodeWithText("운전 감점").performTextReplacement("25")
        compose.onNodeWithText("차량 데이터 값 적용").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PetProgress(12.5, 42.0, Companion.MOKA, 25.0), applied) }
    }

    @Test fun appliesEnteredNumbersAndRejectsInvalidExperience() {
        var applied: PetProgress? = null
        compose.setContent {
            TestControls(PetProgress(companion = Companion.MOKA), "saved", true,
                { applied = it }, {}, {}, {})
        }
        compose.onNodeWithText("경험치 XP").assertDoesNotExist()
        compose.onNodeWithText("성장과 캐릭터").performClick()
        compose.onNodeWithText("드라이비 성장 기록 저장과 복원").assertDoesNotExist()
        compose.onNodeWithText("‹ 테스트 목록").performClick()
        compose.onNodeWithText("드라이비 성장 기록 저장과 복원").performClick()
        compose.onNodeWithText("경험치 XP").assertDoesNotExist()
        compose.onNodeWithText("‹ 테스트 목록").performClick()
        compose.onNodeWithText("성장과 캐릭터").performClick()
        compose.onNodeWithText("경험치 XP").performTextReplacement("300000")
        compose.onNodeWithText("캐릭터: 모카").performScrollTo().performClick()
        compose.onNodeWithText("네루").performClick()
        compose.onNodeWithText("입력값과 캐릭터 적용").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(300000.0, applied!!.experience, 0.0)
            assertEquals(0.0, applied!!.totalKm, 0.0)
            assertEquals(Companion.NERU, applied!!.companion)
            applied = null
        }
        compose.onNodeWithText("경험치 XP").performScrollTo().performTextReplacement("NaN")
        compose.onNodeWithText("입력값과 캐릭터 적용").performScrollTo().performClick()
        compose.runOnIdle { assertNull(applied) }
    }
}
