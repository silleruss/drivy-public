package io.github.silleruss.drivy

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.FileInputStream

class CompanionPipTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val expected = PetProgress(2.0, 850.0, Companion.RAPI, 45.5)

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().readText()
    }

    private fun waitFor(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        fail("Timed out: $label")
    }

    private fun launch(): ActivityScenario<MainActivity> {
        assumeTrue("Requires the isolated phone UX fixture", InstrumentationRegistry.getArguments().getString("pip_fixture") == "true")
        check(context.packageName == "io.github.silleruss.drivy.pipprobe.uitest")
        check(shell("getprop ro.boot.qemu").trim() == "1")
        assertTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        assertTrue(PetSaveStore(context.filesDir).save(expected))
        CompanionDisplay(context).apply { enabled = true; mode = SmallWindowMode.PIP }
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("resumed Activity") {
            var focused = false
            scenario.onActivity {
                focused = it.hasWindowFocus() && MainActivity::class.java.getDeclaredMethod("getInitialized")
                    .apply { isAccessible = true }.invoke(it) == true
            }
            focused
        }
        // The product now migrates the old PiP setting; this fixture explicitly exercises the legacy path.
        scenario.onActivity {
            CompanionDisplay(context).mode = SmallWindowMode.PIP
            MainActivity::class.java.getDeclaredMethod("readDisplaySettings").apply { isAccessible = true }.invoke(it)
        }
        return scenario
    }

    private fun event(activity: MainActivity, json: String) {
        MainActivity::class.java.getDeclaredMethod("receive", String::class.java).apply { isAccessible = true }
            .invoke(activity, json)
    }

    private fun enter(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val entered = MainActivity::class.java.getDeclaredMethod("showSmallCompanion").apply { isAccessible = true }
                .invoke(activity)
            assertEquals(true, entered)
        }
        waitFor("actual OS PiP") {
            var pip = false
            scenario.onActivity { pip = pipReady(it) }
            pip && shell("dumpsys activity activities").contains("mode=pinned")
        }
    }

    private fun pipState(activity: MainActivity) =
        MainActivity::class.java.getDeclaredMethod("getInPip").apply { isAccessible = true }.invoke(activity) == true

    private fun pipReady(activity: MainActivity) = activity.isInPictureInPictureMode && pipState(activity)

    private fun bounds(scenario: ActivityScenario<MainActivity>): Rect {
        var bounds = Rect()
        scenario.onActivity {
            val view = it.window.decorView
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            bounds = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        return bounds
    }

    private fun assertSaved() = assertEquals(expected, PetSaveStore(context.filesDir).load())

    @Test fun actualPipMovesReturnsAndSurvivesRepeatedHomeTransitions() {
        launch().use { scenario ->
            enter(scenario)
            waitFor("Home entered PiP") {
                var pip = false
                scenario.onActivity { pip = pipReady(it) }
                pip && shell("dumpsys activity activities").contains("mode=pinned")
            }
            SystemClock.sleep(1000)
            val before = bounds(scenario)
            shell("input swipe ${before.centerX()} ${before.centerY()} 150 450 600")
            waitFor("system PiP window moved") { bounds(scenario) != before }
            shell("screencap -p /data/local/tmp/drivy-pip.png")
            val moved = bounds(scenario)
            val dwell = InstrumentationRegistry.getArguments().getString("pip_dwell_ms", "3000").toLong()
            val started = SystemClock.elapsedRealtime()
            val until = SystemClock.elapsedRealtime() + dwell
            var checks = 0
            while (SystemClock.elapsedRealtime() < until) {
                scenario.onActivity { assertTrue("PiP disappeared during dwell", it.isInPictureInPictureMode) }
                assertSaved()
                if (++checks % 60 == 0) instrumentation.sendStatus(2, android.os.Bundle().apply {
                    putString("stream", "PiP dwell: ${SystemClock.elapsedRealtime() - started}ms, $checks checks passed\n")
                })
                SystemClock.sleep(minOf(1000, (until - SystemClock.elapsedRealtime()).coerceAtLeast(1)))
            }
            val observedMs = SystemClock.elapsedRealtime() - started
            repeat(3) {
                SystemClock.sleep(1000) // Let the SystemUI PiP input consumer attach after the transition.
                val pip = bounds(scenario)
                val automation = instrumentation.uiAutomation
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                shell("input tap ${pip.centerX()} ${pip.centerY()}")
                waitFor("system PiP expand control") {
                    automation.windows.asSequence().mapNotNull { it.root }
                        .flatMap { it.findAccessibilityNodeInfosByViewId("com.android.systemui:id/expand_button").asSequence() }
                        .firstOrNull()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                }
                waitFor("PiP returned to resumed Activity") {
                    var resumed = false
                    scenario.onActivity { resumed = !it.isInPictureInPictureMode && !pipState(it) && it.hasWindowFocus() }
                    resumed
                }
                assertSaved()
                shell("input keyevent KEYCODE_HOME")
                waitFor("Home entered PiP") {
                    var pip = false
                    scenario.onActivity { pip = pipReady(it) }
                    pip
                }
            }
            assertSaved()
            java.io.File(context.cacheDir, "pip-observation.json").writeText(org.json.JSONObject()
                .put("dwell_ms", observedMs).put("checks", checks)
                .put("home_return_cycles", 3).put("bounds_before_move", before.toShortString())
                .put("bounds_after_move", moved.toShortString())
                .put("pid", android.os.Process.myPid()).toString())
        }
        waitFor("no pinned task after Activity close") { !shell("dumpsys activity activities").contains("mode=pinned") }
    }

    private fun closesOn(json: String) {
        launch().use { scenario ->
            scenario.onActivity {
                event(it, json)
                assertEquals(false, MainActivity::class.java.getDeclaredMethod("showSmallCompanion")
                    .apply { isAccessible = true }.invoke(it))
                assertFalse(it.isInPictureInPictureMode)
                event(it, """{"kind":"ux","requires_do":false}""")
            }
            enter(scenario)
            scenario.onActivity { event(it, json); assertTrue(it.isFinishing) }
            waitFor("restricted/unknown PiP closed") { !shell("dumpsys activity activities").contains("mode=pinned") }
            assertSaved()
        }
    }

    @Test fun restrictionPreventsEntryAndClosesExistingPip() = closesOn("""{"kind":"ux","requires_do":true}""")
    @Test fun unknownUxPreventsEntryAndClosesExistingPip() = closesOn("""{"kind":"ux_error","value":"Injected unavailable UX"}""")
}
