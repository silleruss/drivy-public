package io.github.silleruss.drivy

import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.FileInputStream

class CompanionOverlayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun activityMinimizesToOverlayAndTapReturnsToApp() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".uitest"))
        val user = Process.myUid() / 100000
        val permission = "appops set --user $user ${context.packageName} SYSTEM_ALERT_WINDOW "
        val display = CompanionDisplay(context)
        val title = "DRIVY Companion ${context.packageName}"
        try {
            shell(permission + "allow")
            display.enabled = true
            display.mode = SmallWindowMode.PIP
            assertTrue(PetSaveStore(context.filesDir).save(PetProgress(companion = Companion.MOKA)))
            androidx.test.core.app.ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                var owner: MainActivity? = null
                scenario.onActivity { owner = it }
                val activity = owner!!
                val deadline = android.os.SystemClock.uptimeMillis() + 20_000
                fun field(name: String) = MainActivity::class.java.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() })
                    .apply { isAccessible = true }.invoke(activity) as Boolean
                while (android.os.SystemClock.uptimeMillis() < deadline && !(field("initialized") && field("uxKnown") && !field("restricted")))
                    android.os.SystemClock.sleep(100)
                assertTrue("Expected parked UX and initialized app", field("initialized") && field("uxKnown") && !field("restricted"))
                assertEquals("Previous PiP settings must migrate to the floating companion", SmallWindowMode.OVERLAY, display.mode)
                instrumentation.runOnMainSync {
                    val method = MainActivity::class.java.getDeclaredMethod("showSmallCompanion").apply { isAccessible = true }
                    assertEquals(true, method.invoke(activity))
                }
                waitForWindow(title, true, display)
                android.os.SystemClock.sleep(3000)
                assertTrue("Overlay must remain visible after minimizing", shell("dumpsys window windows").contains(title))
                assertFalse("The full app must leave the foreground", shell("dumpsys activity activities").lineSequence().any {
                    it.contains("topResumedActivity=") && it.contains(context.packageName + "/io.github.silleruss.drivy.MainActivity")
                })
                val window = shell("dumpsys window windows").substringAfter(title).substringBefore("Window #")
                val frame = Regex(" frame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(window)!!
                val (left, top, right, bottom) = frame.groupValues.drop(1).map(String::toInt)
                shell("input tap ${(left + right) / 2} ${(top + bottom) / 2}")
                waitForWindow(title, false, display)
                val returnDeadline = android.os.SystemClock.uptimeMillis() + 10_000
                while (android.os.SystemClock.uptimeMillis() < returnDeadline && !shell("dumpsys activity activities").contains(context.packageName + "/io.github.silleruss.drivy.MainActivity"))
                    android.os.SystemClock.sleep(100)
                assertTrue("Tap should return to the app", shell("dumpsys activity activities").contains(context.packageName + "/io.github.silleruss.drivy.MainActivity"))
            }
        } finally {
            context.stopService(Intent(context, CompanionOverlayService::class.java))
            shell(permission + "default")
            context.getSharedPreferences("companion-display", 0).edit().clear().commit()
            context.getFileStreamPath("pet-progress.json").delete()
            context.getFileStreamPath("pet-progress.json.bak").delete()
        }
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().readText()
    }

    private fun waitForWindow(title: String, visible: Boolean, display: CompanionDisplay) {
        val deadline = android.os.SystemClock.uptimeMillis() + 20_000
        var lastDump = ""
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            lastDump = shell("dumpsys window windows")
            if (lastDump.contains(title) == visible) return
            if (visible && display.notice != null) fail(display.notice)
            android.os.SystemClock.sleep(100)
        }
        fail("Window visible=$visible was not observed; notice=${display.notice}; dump=${lastDump.take(300)}; main=" +
            android.os.Looper.getMainLooper().thread.stackTrace.joinToString("\n"))
    }

    private fun console(path: String) {
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("toybox nc -w 5 127.0.0.1 8088")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
            it.write("PUT $path HTTP/1.0\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        val result = android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
        assertTrue(result, result.contains("200 OK"))
    }

    private fun waitForStopped() {
        val name = instrumentation.targetContext.packageName
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (!shell("dumpsys activity services $name").contains(".CompanionOverlayService}")) {
                val active = shell("dumpsys notification --noredact").substringBefore("  mArchive=")
                assertFalse("Foreground notification remains after service stop", active.contains("pkg=$name "))
                return
            }
            android.os.SystemClock.sleep(100)
        }
        fail("Companion service remains after its window is hidden")
    }

    @Test fun animatedOverlayKeepsPaceWithA60HzDisplay() {
        assumeTrue("Requires isolated parked-vehicle fixture", InstrumentationRegistry.getArguments().getString("frame_fixture") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "io.github.silleruss.drivy.pipprobe.uitest")
        check(shell("getprop ro.boot.qemu").trim() == "1")
        val hz = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY).refreshRate
        check(hz in 59f..61f) { "This measurement requires a 60 Hz display, got $hz" }
        check(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
        val permission = "appops set --user ${Process.myUid() / 100000} ${context.packageName} SYSTEM_ALERT_WINDOW "
        val display = CompanionDisplay(context)
        val intent = Intent(context, CompanionOverlayService::class.java)
        val title = "DRIVY Companion ${context.packageName}"
        try {
            shell(permission + "allow")
            display.enabled = true
            display.mode = SmallWindowMode.OVERLAY
            assertTrue(PetSaveStore(context.filesDir).save(PetProgress(companion = Companion.MOKA)))
            context.startForegroundService(intent)
            waitForWindow(title, true, display)
            android.os.SystemClock.sleep(1500)
            shell("dumpsys gfxinfo ${context.packageName} reset")
            android.os.SystemClock.sleep(4000)
            val output = shell("dumpsys gfxinfo ${context.packageName} framestats")
            val section = output.substringAfter("Window: $title", "").substringAfter("---PROFILEDATA---", "")
            val rows = section.lineSequence().drop(1).takeWhile { !it.startsWith("---PROFILEDATA---") }
                .map { it.split(',') }.filter { it.size > 2 && it[0] == "0" }
                .mapNotNull { it[2].toLongOrNull() }.distinct().toList()
            assertTrue("Insufficient overlay frames: ${rows.size}", rows.size >= 60)
            val intervals = rows.zipWithNext { a, b -> (b - a) / 1_000_000.0 }.filter { it > 0 }.sorted()
            val fps = (rows.size - 1) * 1_000_000_000.0 / (rows.last() - rows.first())
            val observation = org.json.JSONObject().put("display_hz", hz.toDouble()).put("frames", rows.size)
                .put("fps", fps).put("median_interval_ms", intervals[intervals.size / 2])
                .put("p90_interval_ms", intervals[(intervals.size * .9).toInt().coerceAtMost(intervals.lastIndex)])
            context.cacheDir.resolve("frame-observation.json").writeText(observation.toString())
            assertTrue("Overlay frame rate too low: $observation", fps >= 50.0)
        } finally {
            context.stopService(intent)
            shell(permission + "default")
            context.getSharedPreferences("companion-display", 0).edit().clear().commit()
            context.getFileStreamPath("pet-progress.json").delete()
            context.getFileStreamPath("pet-progress.json.bak").delete()
        }
    }

    @Test fun drivingScreenOffReturnAndRepeatedStartsCleanUpTheRealWindow() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".uitest"))
        check(shell("getprop ro.boot.qemu").trim() == "1")
        val user = Process.myUid() / 100000
        val permission = "appops set --user $user ${context.packageName} SYSTEM_ALERT_WINDOW "
        val display = CompanionDisplay(context)
        val title = "DRIVY Companion ${context.packageName}"
        val intent = Intent(context, CompanionOverlayService::class.java)
        fun show() {
            display.notice = null
            context.startForegroundService(intent)
            waitForWindow(title, true, display)
        }
        try {
            shell("pm grant --user $user ${context.packageName} android.car.permission.CAR_SPEED")
            console("/vehicle/controls?speed=0&accelerator=0&brake=0")
            console("/vehicle/gear?gear=Park")
            shell(permission + "allow")
            display.enabled = true
            display.mode = SmallWindowMode.OVERLAY
            assertTrue(PetSaveStore(context.filesDir).save(PetProgress(companion = Companion.MOKA)))
            show()
            context.startForegroundService(intent)
            instrumentation.waitForIdleSync()
            val driveAt = android.os.SystemClock.elapsedRealtime()
            console("/vehicle/gear?gear=Drive")
            console("/vehicle/controls?speed=30&accelerator=0&brake=0")
            val restrictedBy = android.os.SystemClock.uptimeMillis() + 20_000
            var restrictionReceived = false
            while (!restrictionReceived && android.os.SystemClock.uptimeMillis() < restrictedBy) {
                restrictionReceived = shell("logcat --pid=${Process.myPid()} -d -s DrivyVehicle:I").lineSequence()
                    .filter { it.contains("DRIVY_NATIVE_EVENT ") }.map { org.json.JSONObject(it.substringAfter("DRIVY_NATIVE_EVENT ")) }
                    .any { it.optString("kind") == "ux" && it.optBoolean("requires_do") && it.optLong("elapsed_ms") >= driveAt }
                if (!restrictionReceived) android.os.SystemClock.sleep(100)
            }
            assertTrue("No actual AAOS restriction callback; check the AVD vehicle input path", restrictionReceived)
            waitForWindow(title, false, display)
            waitForStopped()
            assertTrue(display.notice, display.notice?.contains("주행 제한") == true)
            display.notice = null
            context.startForegroundService(intent)
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            while (display.notice == null && android.os.SystemClock.uptimeMillis() < deadline) {
                assertFalse("Restricted startup must never add a window", shell("dumpsys window windows").contains(title))
                android.os.SystemClock.sleep(100)
            }
            assertTrue(display.notice?.contains("주행 제한") == true)
            waitForStopped()
            console("/vehicle/controls?speed=0&accelerator=0&brake=0")
            console("/vehicle/gear?gear=Park")
            android.os.SystemClock.sleep(1000)
            waitForWindow(title, false, display)
            waitForStopped()
            show()
            shell("input keyevent 223")
            waitForWindow(title, false, display)
            waitForStopped()
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            shell("input keyevent 82")
            val unlockedBy = android.os.SystemClock.uptimeMillis() + 15_000
            while (context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
                android.os.SystemClock.uptimeMillis() < unlockedBy) android.os.SystemClock.sleep(100)
            assertFalse("Wait for the device to unlock before showing again",
                context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            show()
            androidx.test.core.app.ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                waitForWindow(title, false, display)
                waitForStopped()
            }
            repeat(2) {
                show()
                context.stopService(intent)
                waitForWindow(title, false, display)
                waitForStopped()
            }
        } finally {
            context.stopService(intent)
            shell("input keyevent 224")
            console("/vehicle/controls?speed=0&accelerator=0&brake=0")
            console("/vehicle/gear?gear=Park")
            shell(permission + "default")
            context.getSharedPreferences("companion-display", 0).edit().clear().commit()
            context.getFileStreamPath("pet-progress.json").delete()
            context.getFileStreamPath("pet-progress.json.bak").delete()
        }
    }

    @Test fun injectedUxErrorOrTimeoutCleansUpTheWindow() {
        val failure = InstrumentationRegistry.getArguments().getString("ux_failure")
        assumeTrue("Requires an isolated source-injected fixture", failure in listOf("uxerror", "uxtimeout"))
        val context = instrumentation.targetContext
        check(context.packageName.contains("probe.uitest"))
        val permission = "appops set --user ${Process.myUid() / 100000} ${context.packageName} SYSTEM_ALERT_WINDOW "
        val display = CompanionDisplay(context)
        val title = "DRIVY Companion ${context.packageName}"
        try {
            shell(permission + "allow")
            display.enabled = true
            display.mode = SmallWindowMode.OVERLAY
            display.notice = null
            assertTrue(PetSaveStore(context.filesDir).save(PetProgress(companion = Companion.MOKA)))
            context.startForegroundService(Intent(context, CompanionOverlayService::class.java))
            if (failure == "uxerror") waitForWindow(title, true, display)
            val deadline = android.os.SystemClock.uptimeMillis() + 20_000
            while (display.notice == null && android.os.SystemClock.uptimeMillis() < deadline) {
                if (failure == "uxtimeout") assertFalse(shell("dumpsys window windows").contains(title))
                android.os.SystemClock.sleep(100)
            }
            assertTrue(display.notice, display.notice?.contains("차량 상태") == true)
            waitForWindow(title, false, display)
            waitForStopped()
        } finally {
            context.stopService(Intent(context, CompanionOverlayService::class.java))
            shell(permission + "default")
        }
    }

    @Test fun parkedOverlayCreatesARealTransparentWindowAndRevocationClosesIt() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".uitest"))
        val user = Process.myUid() / 100000
        val permission = "appops set --user $user ${context.packageName} SYSTEM_ALERT_WINDOW "
        val display = CompanionDisplay(context)
        val title = "DRIVY Companion ${context.packageName}"
        try {
            shell(permission + "allow")
            assertTrue(Settings.canDrawOverlays(context))
            display.enabled = true
            display.mode = SmallWindowMode.OVERLAY
            display.corner = PetCorner.BOTTOM_RIGHT
            assertTrue(PetSaveStore(context.filesDir).save(PetProgress(companion = Companion.MOKA)))
            context.startForegroundService(Intent(context, CompanionOverlayService::class.java))
            waitForWindow(title, true, display)
            val windows = shell("dumpsys window windows")
            assertTrue(windows.contains("APPLICATION_OVERLAY"))
            val block = windows.substringAfter(title).substringBefore("Window #")
            val frame = Regex(" frame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(block)!!
            val (left, top, right, bottom) = frame.groupValues.drop(1).map(String::toInt)
            val x = (left + right) / 2
            val y = (top + bottom) / 2
            val down = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int, px: Int, py: Int) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                    px.toFloat(), py.toFloat(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            shell("screencap -p /data/local/tmp/drivy-overlay-test.png")
            touch(android.view.MotionEvent.ACTION_DOWN, x, y)
            for (step in 1..10) touch(android.view.MotionEvent.ACTION_MOVE, x - step * 30, y - step * 20)
            touch(android.view.MotionEvent.ACTION_UP, x - 300, y - 200)
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (display.position() == (1f to 1f) && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(100)
            }
            assertNotEquals("Dragging must persist a new position", 1f to 1f, display.position())
            shell("screencap -p /data/local/tmp/drivy-overlay-test.png")
            shell(permission + "deny")
            waitForWindow(title, false, display)
            assertTrue(display.notice?.contains("권한") == true)
        } finally {
            context.stopService(Intent(context, CompanionOverlayService::class.java))
            shell(permission + "default")
            context.getSharedPreferences("companion-display", 0).edit().clear().commit()
            context.getFileStreamPath("pet-progress.json").delete()
            context.getFileStreamPath("pet-progress.json.bak").delete()
        }
    }
}
