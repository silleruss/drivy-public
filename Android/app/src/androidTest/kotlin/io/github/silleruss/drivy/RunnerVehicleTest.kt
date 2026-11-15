package io.github.silleruss.drivy

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

class RunnerVehicleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().readText()
    }
    private fun console(path: String) {
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("toybox nc -w 5 127.0.0.1 8088")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
            it.write("PUT $path HTTP/1.0\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        val response = android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
        assertTrue(response, response.contains("200 OK"))
    }

    @Test fun actualRestrictionStopsGame() {
        val context = instrumentation.targetContext
        assumeTrue(context.packageName == "io.github.silleruss.drivy.runnerprobe.uitest" &&
            InstrumentationRegistry.getArguments().getString("runner_vehicle") == "true")
        assertEquals("1", shell("getprop ro.boot.qemu").trim())
        val initial = PetProgress(2.0, 850.0, Companion.TORI)
        context.getFileStreamPath("runner-scores-pattern-v2.json").delete()
        assertTrue(PetSaveStore(context.filesDir).save(initial))
        console("/vehicle/controls?speed=0&accelerator=0&brake=0")
        console("/vehicle/gear?gear=Park")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                compose.waitUntil(30000) { compose.onAllNodesWithText("드라이비 런").fetchSemanticsNodes().isNotEmpty() }
                compose.mainClock.autoAdvance = false
                compose.onNodeWithText("드라이비 런").performScrollTo().performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForRunnerReady()
                compose.onNodeWithText("시작").performScrollTo().performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText("점프").assertExists()
                val before = SystemClock.elapsedRealtime()
                console("/vehicle/gear?gear=Drive")
                console("/vehicle/controls?speed=30&accelerator=0&brake=0")
                val deadline = SystemClock.uptimeMillis() + 20000
                var received = false
                while (!received && SystemClock.uptimeMillis() < deadline) {
                    received = shell("logcat --pid=${Process.myPid()} -d -s DrivyVehicle:I").lineSequence()
                        .filter { it.contains("DRIVY_NATIVE_EVENT ") }
                        .map { JSONObject(it.substringAfter("DRIVY_NATIVE_EVENT ")) }
                        .any { it.optString("kind") == "ux" && it.optBoolean("requires_do") && it.optLong("elapsed_ms") >= before }
                    if (!received) SystemClock.sleep(100)
                }
                assertTrue("Actual AAOS restriction callback was not observed", received)
                scenario.onActivity { activity ->
                    val getter = MainActivity::class.java.getDeclaredMethod("getInGame").apply { isAccessible = true }
                    assertEquals(false, getter.invoke(activity))
                }
            }
            assertFalse(context.getFileStreamPath("runner-scores-pattern-v2.json").exists())
            assertEquals(initial, PetSaveStore(context.filesDir).load())
        } finally {
            console("/vehicle/controls?speed=0&accelerator=0&brake=100")
            console("/vehicle/gear?gear=Park")
            console("/vehicle/controls?speed=0&accelerator=0&brake=0")
        }
    }
}
