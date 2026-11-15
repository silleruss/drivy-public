package io.github.silleruss.drivy

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CompanionSetupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectionSaveFailureIsAnnounced() {
        val status = mutableStateOf("new")
        compose.setContent { CompanionSetup(status.value) {} }
        compose.runOnIdle { status.value = "save_failed" }
        compose.onNodeWithText("저장하지 못했어요. 친구를 다시 선택해 저장해 주세요.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun chooseBeforeFirstSaveAndRestoreTheConfirmedFriend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "selection-test").apply { deleteRecursively(); mkdirs() }
        val store = PetSaveStore(directory)
        store.load()
        assertEquals("new", store.status)
        compose.setContent { CompanionSetup("new") { assertTrue(store.save(PetProgress(companion = it))) } }
        compose.onNodeWithText("모카 · 선택됨").assertIsSelected()
        compose.onNodeWithText("라피", substring = false).performScrollTo().assertIsNotSelected().performClick()
        compose.onNodeWithText("라피 · 선택됨").assertIsSelected()
        assertFalse(File(directory, "pet-progress.json").exists())
        compose.onNodeWithText("라피와 동행 시작").performScrollTo().performClick()
        assertEquals(Companion.RAPI, PetSaveStore(directory).load().companion)
        directory.deleteRecursively()
    }

    @Test fun selectionRemainsUsableInANarrowWindowWithLargerText() {
        var chosen: Companion? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                Box(Modifier.size(360.dp, 400.dp)) { CompanionSetup("new") { chosen = it } }
            }
        }
        compose.onNodeWithText("라피", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("라피 · 선택됨").assertIsSelected()
        compose.onNodeWithText("라피와 동행 시작").performScrollTo().performClick()
        assertEquals(Companion.RAPI, chosen)
    }

    @Test fun speedPermissionIsOptionalAndRequestedOnlyByTheUser() {
        var requests = 0
        val granted = mutableStateOf(false)
        compose.setContent {
            androidx.compose.foundation.layout.Column { SpeedPermissionSettings(granted.value) { requests++ } }
        }
        compose.onNodeWithText("차량 속도 권한 요청").performClick()
        assertEquals(1, requests)
        compose.runOnIdle { granted.value = true }
        compose.onNodeWithText("차량 속도 권한이 켜져 있어요.").assertIsDisplayed()
        compose.onNodeWithText("차량 속도 권한 요청").assertDoesNotExist()
    }

    @Test fun settingsPersistModeCornerAndDragCoordinatesAndPipIsUnavailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("companion-display", 0)
        preferences.edit().clear().commit()
        val display = CompanionDisplay(context)
        val enabled = mutableStateOf(false)
        val corner = mutableStateOf(display.corner)
        compose.setContent {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                CompanionDisplaySettings(enabled.value, corner.value, true, null,
                    { enabled.value = it; display.enabled = it },
                    { corner.value = it; display.corner = it }, {})
            }
        }
        compose.onNodeWithText("시스템 PiP").assertDoesNotExist()
        compose.onNodeWithContentDescription("앱 최소화 시 드라이비 화면에 표시").performScrollTo().performClick()
        compose.onNodeWithText("왼쪽 위").performScrollTo().performClick()
        assertTrue(CompanionDisplay(context).enabled)
        assertEquals(0f to 0f, display.position())
        display.savePosition(.2f, .7f)
        assertEquals(.2f to .7f, CompanionDisplay(context).position())
        display.corner = PetCorner.BOTTOM_RIGHT
        assertEquals(1f to 1f, display.position())
        display.savePosition(Float.NaN, 0f)
        assertEquals(1f to 1f, display.position())
        preferences.edit().clear().commit()
    }
}
