package io.github.silleruss.drivy

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import android.car.Car
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

class CompanionActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun speedPermissionIsNotRequestedUntilTheUserAsks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("permission_fixture") == "true" &&
            context.packageName == "io.github.silleruss.drivy.demo.uitest")
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Car.PERMISSION_SPEED))
        val primary = context.getFileStreamPath("pet-progress.json")
        val backup = context.getFileStreamPath("pet-progress.json.bak")
        primary.delete(); backup.delete()
        assertTrue(PetSaveStore(context.filesDir).save(PetProgress(companion = Companion.RAPI)))
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                compose.waitUntil(30_000) { compose.onAllNodesWithText("자세히에서 속도 권한 요청").fetchSemanticsNodes().isNotEmpty() }
                scenario.recreate()
                compose.waitUntil(30_000) { compose.onAllNodesWithText("자세히에서 속도 권한 요청").fetchSemanticsNodes().isNotEmpty() }
                val user = android.os.Process.myUid() / 100000
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant --user $user ${context.packageName} android.car.permission.CAR_SPEED").use {
                    FileInputStream(it.fileDescriptor).readBytes()
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitUntil(30_000) { compose.onAllNodesWithText("가감속 기준").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            primary.delete(); backup.delete()
        }
    }

    @Test fun actualActivityRequiresSelectionAndRestoresItAfterRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".uitest"))
        val user = android.os.Process.myUid() / 100000
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant --user $user ${context.packageName} android.car.permission.CAR_SPEED").use {
            FileInputStream(it.fileDescriptor).readBytes()
        }
        val primary = context.getFileStreamPath("pet-progress.json")
        val backup = context.getFileStreamPath("pet-progress.json.bak")
        primary.delete(); backup.delete()
        context.getSharedPreferences("companion", 0).edit().clear().commit()
        CompanionDisplay(context).enabled = false
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithText("처음 함께할 드라이비를 골라주세요").fetchSemanticsNodes().isNotEmpty()
                }
                scenario.onActivity { activity ->
                    val receive = MainActivity::class.java.getDeclaredMethod("receive", String::class.java).apply { isAccessible = true }
                    val time = SystemClock.elapsedRealtime()
                    for (i in 0..1) receive.invoke(activity,
                        """{"kind":"counter","metric":"distance_km","status":"available","value":${100 + i},"elapsed_ms":${time + i * 60_000}}""")
                }
                assertFalse("Unconfirmed choice must not be saved", primary.exists())
                compose.onNodeWithText("라피", substring = false).performScrollTo().performClick()
                compose.onNodeWithText("라피와 동행 시작").performScrollTo().performClick()
                compose.waitUntil(10_000) { primary.isFile }
                val confirmed = PetSaveStore(context.filesDir).load()
                assertEquals(PetProgress(companion = Companion.RAPI), confirmed)
                scenario.recreate()
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithText("자세히").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("처음 함께할 드라이비를 골라주세요").assertDoesNotExist()
                assertEquals(confirmed, PetSaveStore(context.filesDir).load())
            }
            if (InstrumentationRegistry.getArguments().getString("keep_selection") == "true") {
                java.io.File(context.cacheDir, "selection-pid").writeText(android.os.Process.myPid().toString())
            }
        } finally {
            if (InstrumentationRegistry.getArguments().getString("keep_selection") != "true") {
                primary.delete(); backup.delete()
            }
        }
    }
}
