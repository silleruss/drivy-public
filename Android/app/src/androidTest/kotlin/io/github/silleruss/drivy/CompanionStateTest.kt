package io.github.silleruss.drivy

import android.content.Intent
import android.os.StrictMode
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

class CompanionStateTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val initial = PetProgress(2.0, 850.0, Companion.RAPI)
    private val primary get() = context.getFileStreamPath("pet-progress.json")
    private val backup get() = context.getFileStreamPath("pet-progress.json.bak")
    private lateinit var activity: MainActivity

    @Before fun prepare() {
        assumeTrue("Requires isolated vehicle fixture", InstrumentationRegistry.getArguments().getString("pip_fixture") == "true")
        check(context.packageName == "io.github.silleruss.drivy.pipprobe.uitest")
        primary.delete(); backup.delete()
        context.getFileStreamPath("hold-init-save").delete()
        context.getFileStreamPath("init-save-waiting").delete()
        context.getFileStreamPath("pet-progress.json.tmp").deleteRecursively()
        context.getSharedPreferences("companion", 0).edit().clear().commit()
        CompanionDisplay(context).enabled = false
        assertTrue(PetSaveStore(context.filesDir).save(initial))
    }

    @After fun cleanup() {
        if (context.packageName == "io.github.silleruss.drivy.pipprobe.uitest") {
            val executor = MainActivity::class.java.getDeclaredField("saves").apply { isAccessible = true }
                .get(null) as java.util.concurrent.ExecutorService
            executor.submit {
                MainActivity::class.java.getDeclaredField("unsavedProgress").apply { isAccessible = true }.set(null, null)
            }.get(15, java.util.concurrent.TimeUnit.SECONDS)
            primary.delete(); backup.delete()
            context.getFileStreamPath("hold-init-save").delete()
            context.getFileStreamPath("init-save-waiting").delete()
            context.getFileStreamPath("pet-progress.json.tmp").deleteRecursively()
        }
    }

    private fun launch(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        scenario.onActivity { activity = it }
        awaitState { call(it, "getInitialized") == true }
        return scenario
    }

    private fun onActivity(action: (MainActivity) -> Unit) = instrumentation.runOnMainSync { action(activity) }

    private fun awaitState(predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            onActivity { done = predicate(it) }
            if (done) return
            SystemClock.sleep(20)
        }
        fail("Activity state did not reach its expected result")
    }

    private fun awaitSaved() = awaitState {
        !MainActivity::class.java.getDeclaredField("dirty").apply { isAccessible = true }.getBoolean(it)
    }

    private fun call(activity: MainActivity, method: String, json: String? = null): Any? =
        if (json == null) MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)
        else MainActivity::class.java.getDeclaredMethod(method, String::class.java).apply { isAccessible = true }.invoke(activity, json)

    private fun speed(activity: MainActivity, value: Double, ms: Long) {
        call(activity, "receive", """{"kind":"speed","status":"available","value":$value,"elapsed_ms":$ms}""")
    }

    private fun moving(activity: MainActivity) {
        val now = SystemClock.elapsedRealtime()
        for (i in 0..10) speed(activity, 72.0, now - (10 - i) * 1000)
        assertEquals(100, call(activity, "getDrivingScore"))
    }

    private fun distance(activity: MainActivity) {
        val now = SystemClock.elapsedRealtime()
        for (i in 0..1) call(activity, "receive",
            """{"kind":"counter","metric":"distance_km","status":"available","value":${1000 + i * .03125},"elapsed_ms":${now + i * 1000}}""")
    }

    private fun waitForUi(text: String) {
        fun contains(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean = node != null &&
            (node.text?.contains(text) == true || (0 until node.childCount).any { contains(node.getChild(it)) })
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (contains(instrumentation.uiAutomation.rootInActiveWindow)) return
            SystemClock.sleep(100)
        }
        fail("Actual Activity UI did not show: $text")
    }

    @Test fun pendingSaveDoesNotBlockRecreationOrOverwriteProgress() {
        assumeTrue("Requires delayed save fixture", InstrumentationRegistry.getArguments().getString("io_delay_ms") == "1000")
        launch().use { scenario ->
            val gate = context.getFileStreamPath("hold-init-save")
            val waiting = context.getFileStreamPath("init-save-waiting")
            try {
                gate.writeText("hold")
                onActivity { distance(it); call(it, "save") }
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (!waiting.exists() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
                assertTrue("Save must be held by the fixture", waiting.exists())
                scenario.recreate()
                scenario.onActivity { activity = it }
                onActivity { assertEquals(false, call(it, "getInitialized")) }
                waitForUi("동행 기록을 불러오고 있어요")
                instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/drivy-startup.png").use {
                    java.io.FileInputStream(it.fileDescriptor).readBytes()
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                gate.delete()
                awaitState { call(it, "getInitialized") == true }
                onActivity {
                    assertNull("Stopped Activity must not connect to the vehicle", MainActivity::class.java
                        .getDeclaredField("connection").apply { isAccessible = true }.get(it))
                    assertEquals(2.03125, call(it, "getTotal"))
                    assertEquals(853.125, call(it, "getExperience"))
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertEquals(initial.copy(totalKm = 2.03125, experience = 853.125), PetSaveStore(context.filesDir).load())
            } finally { gate.delete(); waiting.delete() }
        }
    }

    @Test fun destroyedActivityDoesNotApplyLateLoad() {
        assumeTrue("Requires delayed save fixture", InstrumentationRegistry.getArguments().getString("io_delay_ms") == "1000")
        val gate = context.getFileStreamPath("hold-init-save")
        val waiting = context.getFileStreamPath("init-save-waiting")
        try {
            gate.writeText("hold")
            val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            lateinit var destroyed: MainActivity
            scenario.onActivity { destroyed = it }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!waiting.exists() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertTrue("Initial save must be held by the fixture", waiting.exists())
            val executor = MainActivity::class.java.getDeclaredField("saves").apply { isAccessible = true }
                .get(null) as java.util.concurrent.ExecutorService
            scenario.onActivity {
                it.finish()
                assertTrue(it.isFinishing)
                gate.delete()
                executor.submit {}.get(15, java.util.concurrent.TimeUnit.SECONDS)
            }
            scenario.close()
            instrumentation.waitForIdleSync()
            assertTrue(destroyed.isDestroyed)
            assertEquals(false, call(destroyed, "getInitialized"))
            assertEquals(initial, PetSaveStore(context.filesDir).load())
        } finally { gate.delete(); waiting.delete() }
    }

    private fun clickText(text: String) {
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.text?.toString()?.lineSequence()?.any { it == text } == true) {
                var button: android.view.accessibility.AccessibilityNodeInfo = node
                while (!button.isClickable) button = button.parent ?: break
                node.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                if (button.isClickable) return button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            return (0 until node.childCount).any { find(node.getChild(it)) }
        }
        fun scroll(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean = node != null &&
            ((node.isScrollable && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) ||
                (0 until node.childCount).any { scroll(node.getChild(it)) })
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(instrumentation.uiAutomation.rootInActiveWindow)) return
            scroll(instrumentation.uiAutomation.rootInActiveWindow)
            SystemClock.sleep(100)
        }
        fail("Actual UI button was not clickable: $text")
    }

    @Test fun firstSelectionCommitsBeforeRestoringTheConfirmedFriend() {
        primary.delete(); backup.delete()
        launch().use {
            waitForUi("처음 함께할 드라이비를 골라주세요")
            clickText("라피")
            clickText("라피와 동행 시작")
            awaitState { call(it, "getNeedsSelection") == false }
            assertEquals(PetProgress(companion = Companion.RAPI), PetSaveStore(context.filesDir).load())
        }
        launch().use {
            onActivity { assertEquals(false, call(it, "getNeedsSelection")); assertEquals(Companion.RAPI, call(it, "getCompanion")) }
        }
    }

    @Test fun scoreExpiresWithoutSdkEventsAndOldDeadlineCannotClearANewScore() {
        launch().use { scenario ->
            onActivity(::moving)
            SystemClock.sleep(1200)
            onActivity { speed(it, 72.0, SystemClock.elapsedRealtime()) }
            SystemClock.sleep(1100)
            onActivity { assertEquals("Old deadline cleared a newer score", 100, call(it, "getDrivingScore")) }
            SystemClock.sleep(1100)
            onActivity {
                assertNull("No SDK event should be needed to expire the score", call(it, "getDrivingScore"))
                assertEquals(2.0, call(it, "getTotal"))
                assertEquals(850.0, call(it, "getExperience"))
            }
            waitForUi("측정 대기")
            assertEquals(initial, PetSaveStore(context.filesDir).load())
        }
    }

    @Test fun resumingAfterExpiryReevaluatesTheOwningActivity() {
        launch().use { scenario ->
            onActivity(::moving)
            context.startActivity(Intent().setClassName(context.packageName + ".test",
                "androidx.test.core.app.InstrumentationActivityInvoker\$EmptyFloatingActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var deadline = SystemClock.uptimeMillis() + 10_000
            var resumed = true
            while (resumed && SystemClock.uptimeMillis() < deadline) {
                onActivity { resumed = call(it, "getResumed") == true }
                if (resumed) SystemClock.sleep(50)
            }
            assertFalse("Floating test Activity must pause the owner", resumed)
            onActivity {
                assertEquals(100, call(it, "getDrivingScore"))
                val handler = MainActivity::class.java.getDeclaredField("main").apply { isAccessible = true }.get(it) as android.os.Handler
                val expiry = MainActivity::class.java.getDeclaredField("scoreExpires").apply { isAccessible = true }.get(it) as Runnable
                assertFalse("Paused Activity must cancel its expiry callback", handler.hasCallbacks(expiry))
            }
            SystemClock.sleep(2200)
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").use {
                java.io.FileInputStream(it.fileDescriptor).readBytes()
            }
            deadline = SystemClock.uptimeMillis() + 10_000
            while (!resumed && SystemClock.uptimeMillis() < deadline) {
                onActivity { resumed = call(it, "getResumed") == true }
                if (!resumed) SystemClock.sleep(50)
            }
            assertTrue("Owner must resume after closing the floating Activity", resumed)
            onActivity { assertNull(call(it, "getDrivingScore")) }
            waitForUi("측정 대기")
            assertEquals(initial, PetSaveStore(context.filesDir).load())
        }
    }

    @Test fun corruptFilesBlockTemporaryGrowthAndPenaltyWithoutChangingOriginals() {
        primary.writeText("{broken-primary"); backup.writeText("{broken-backup")
        launch().use { scenario ->
            onActivity {
                val now = SystemClock.elapsedRealtime()
                speed(it, 36.0, now - 1000); speed(it, 72.0, now)
                distance(it)
                assertEquals("corrupt", call(it, "getStorageStatus"))
                assertEquals("Corrupt saves must not accumulate temporary distance", 0.0, call(it, "getTotal"))
                assertEquals(0.0, call(it, "getExperience"))
                val habits = MainActivity::class.java.getDeclaredField("habits").apply { isAccessible = true }.get(it) as DrivingScore
                assertEquals(0.0, habits.penalty, 0.0)
            }
            assertEquals("{broken-primary", primary.readText())
            assertEquals("{broken-backup", backup.readText())
            waitForUi("성장 중지")
        }
    }

    @Test fun sameTimestampDistanceFailureDoesNotGrantMissingGrowth() {
        launch().use {
            onActivity { activity ->
                val now = SystemClock.elapsedRealtime()
                fun sample(value: String, status: String, timestamp: Long) = call(activity, "receive",
                    """{"kind":"counter","metric":"distance_km","status":"$status","value":$value,"elapsed_ms":$timestamp}""")
                sample("1000.0", "available", now)
                sample("null", "unavailable", now)
                sample("1000.03125", "available", now + 1000)
                assertEquals(2.0, call(activity, "getTotal"))
                assertEquals(850.0, call(activity, "getExperience"))
                sample("1000.0625", "available", now + 2000)
                assertEquals(2.03125, call(activity, "getTotal"))
                assertEquals(853.125, call(activity, "getExperience"))
                call(activity, "save")
            }
            awaitSaved()
            assertEquals(initial.copy(totalKm = 2.03125, experience = 853.125), PetSaveStore(context.filesDir).load())
        }
    }

    @Test fun distanceTimeoutReconnectsWithoutLosingProgress() {
        launch().use { scenario ->
            var previousSession = 0
            var previousCallback: io.github.silleruss.drivy.vehicle.VehicleConnection.Callback? = null
            val session = MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
            onActivity {
                previousSession = session.getInt(it)
                val connection = MainActivity::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(it)
                previousCallback = connection.javaClass.getDeclaredField("callback").apply { isAccessible = true }
                    .get(connection) as io.github.silleruss.drivy.vehicle.VehicleConnection.Callback
                call(it, "receive", """{"kind":"counter_error","metric":"distance_km","source":"read","status":"timeout"}""")
                call(it, "receive", """{"kind":"counter","metric":"distance_km","status":"available","value":1000.0,"elapsed_ms":${SystemClock.elapsedRealtime()}}""")
            }
            awaitState { session.getInt(it) > previousSession }
            onActivity {
                call(it, "receive", """{"kind":"counter","metric":"distance_km","status":"available","value":1000.03125,"elapsed_ms":${SystemClock.elapsedRealtime()}}""")
                previousCallback!!.onEvent("""{"kind":"counter","metric":"distance_km","status":"available","value":1000.0625,"elapsed_ms":${SystemClock.elapsedRealtime() + 1000}}""")
                assertEquals(2.0, call(it, "getTotal"))
                assertEquals(850.0, call(it, "getExperience"))
                assertNull(call(it, "getDrivingScore"))
                val handler = MainActivity::class.java.getDeclaredField("main").apply { isAccessible = true }.get(it) as android.os.Handler
                val retry = MainActivity::class.java.getDeclaredField("retryVehicleConnection").apply { isAccessible = true }.get(it) as Runnable
                call(it, "receive", """{"kind":"counter_error","metric":"distance_km","source":"read","status":"timeout"}""")
                assertTrue(handler.hasCallbacks(retry))
                call(it, "receive", """{"kind":"counter","metric":"distance_km","status":"available","value":1000.03125,"elapsed_ms":${SystemClock.elapsedRealtime() + 1}}""")
                assertTrue("An SDK request may still be stuck despite a newer subscription value", handler.hasCallbacks(retry))
                for (status in listOf("permission_denied", "unsupported")) {
                    call(it, "receive", """{"kind":"counter_error","metric":"distance_km","source":"read","status":"$status"}""")
                    assertFalse("Permanent errors must cancel a pending reconnect", handler.hasCallbacks(retry))
                    call(it, "receive", """{"kind":"counter_error","metric":"distance_km","source":"read","status":"timeout"}""")
                    assertTrue(handler.hasCallbacks(retry))
                }
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            onActivity {
                val handler = MainActivity::class.java.getDeclaredField("main").apply { isAccessible = true }.get(it) as android.os.Handler
                val retry = MainActivity::class.java.getDeclaredField("retryVehicleConnection").apply { isAccessible = true }.get(it) as Runnable
                assertFalse("Stopped Activity must not reconnect", handler.hasCallbacks(retry))
            }
            assertEquals(initial, PetSaveStore(context.filesDir).load())
        }
    }

    @Test fun backupRecoveryAndTemporaryWriteFailureKeepProgressAndRetry() {
        assertTrue(PetSaveStore(context.filesDir).save(initial))
        primary.writeText("{broken-primary")
        launch().use { scenario ->
            onActivity { assertEquals("recovered_backup", call(it, "getStorageStatus")) }
            val blocked = context.getFileStreamPath("pet-progress.json.tmp").apply { assertTrue(mkdir()) }
            onActivity {
                distance(it); call(it, "save")
                assertEquals(2.03125, call(it, "getTotal"))
                assertEquals(853.125, call(it, "getExperience"))
            }
            awaitState { call(it, "getStorageStatus") == "save_failed" }
            onActivity {
                val now = SystemClock.elapsedRealtime()
                speed(it, 36.0, now - 1000); speed(it, 72.0, now)
            }
            assertTrue(blocked.delete())
            onActivity { call(it, "save") }
            awaitSaved()
            onActivity { assertEquals("saved", call(it, "getStorageStatus")) }
            assertEquals(initial.copy(totalKm = 2.03125, experience = 853.125, drivingPenalty = 20.0), PetSaveStore(context.filesDir).load())
        }
    }

    @Test fun failedSaveSurvivesActivityRecreationUntilRetrySucceeds() {
        val blocked = context.getFileStreamPath("pet-progress.json.tmp").apply { assertTrue(mkdir()) }
        try {
            launch().use {
                onActivity { distance(it); call(it, "save") }
                awaitState { call(it, "getStorageStatus") == "save_failed" }
            }
            assertEquals(initial, PetSaveStore(context.filesDir).load())
            launch().use {
                onActivity {
                    assertEquals(2.03125, call(it, "getTotal"))
                    assertEquals(853.125, call(it, "getExperience"))
                    assertEquals("save_failed", call(it, "getStorageStatus"))
                    distance(it); call(it, "save")
                }
                awaitState { MainActivity::class.java.getDeclaredField("queuedProgress").apply { isAccessible = true }.get(it) == null }
            }
            assertEquals(initial, PetSaveStore(context.filesDir).load())
            launch().use {
                onActivity {
                    assertEquals(2.0625, call(it, "getTotal"))
                    assertEquals(856.25, call(it, "getExperience"))
                }
                assertTrue(blocked.delete())
                onActivity { call(it, "save") }
                awaitSaved()
                assertEquals(initial.copy(totalKm = 2.0625, experience = 856.25), PetSaveStore(context.filesDir).load())
            }
        } finally { blocked.deleteRecursively() }
    }

    @Test fun failedFirstSelectionStillRequiresConfirmationAfterRecreation() {
        primary.delete(); backup.delete()
        val blocked = context.getFileStreamPath("pet-progress.json.tmp").apply { assertTrue(mkdir()) }
        try {
            launch().use {
                waitForUi("처음 함께할 드라이비를 골라주세요")
                clickText("라피")
                clickText("라피와 동행 시작")
                awaitState { call(it, "getStorageStatus") == "save_failed" }
                onActivity { assertEquals(true, call(it, "getNeedsSelection")) }
            }
            launch().use {
                onActivity { assertEquals(true, call(it, "getNeedsSelection")) }
                waitForUi("처음 함께할 드라이비를 골라주세요")
                assertFalse(primary.exists())
            }
        } finally { blocked.deleteRecursively() }
    }

    @Test fun pendingProgressDoesNotOverwriteCorruptOriginals() {
        val blocked = context.getFileStreamPath("pet-progress.json.tmp")
        lateinit var original: String
        try {
            launch().use {
                original = primary.readText()
                assertTrue(blocked.mkdir())
                onActivity { distance(it); call(it, "save") }
                awaitState { call(it, "getStorageStatus") == "save_failed" }
            }
            primary.writeText("{broken-primary")
            backup.writeText("{broken-backup")
            assertTrue(blocked.delete())
            launch().use {
                onActivity {
                    assertEquals("corrupt", call(it, "getStorageStatus"))
                    assertEquals(0.0, call(it, "getTotal"))
                }
                assertEquals("{broken-primary", primary.readText())
                assertEquals("{broken-backup", backup.readText())
            }
            primary.writeText(original)
            launch().use {
                onActivity { assertEquals(2.0, call(it, "getTotal")) }
                assertEquals(initial, PetSaveStore(context.filesDir).load())
            }
        } finally { blocked.deleteRecursively() }
    }

    @Test fun queuedSavesAndSmallWindowEntryPreserveTheLatestSnapshotAcrossRecreation() {
        launch().use {
            onActivity { owner ->
                val start = SystemClock.elapsedRealtime() - 5000
                for (i in 0..3) {
                    call(owner, "receive", """{"kind":"counter","metric":"distance_km","status":"available","value":${1000 + i * .03125},"elapsed_ms":${start + i * 1000}}""")
                    call(owner, "save")
                }
            }
        }
        launch().use {
            onActivity { owner ->
                assertEquals(2.09375, call(owner, "getTotal"))
                assertEquals(859.375, call(owner, "getExperience"))
                CompanionDisplay(context).apply { enabled = true; mode = SmallWindowMode.PIP }
                call(owner, "readDisplaySettings")
                distance(owner)
                val started = SystemClock.elapsedRealtimeNanos()
                assertEquals(true, call(owner, "showSmallCompanion"))
                assertTrue("Small-window entry must not block a later UX restriction",
                    (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0 < 200)
                assertFalse(owner.isInPictureInPictureMode)
                call(owner, "receive", """{"kind":"ux","requires_do":true}""")
            }
            awaitSaved()
            onActivity { owner ->
                assertFalse("A cancelled entry must not open after its save completes", owner.isInPictureInPictureMode)
                assertEquals(initial.copy(totalKm = 2.125, experience = 862.5), PetSaveStore(context.filesDir).load())
                call(owner, "receive", """{"kind":"ux","requires_do":false}""")
                call(owner, "receive", """{"kind":"counter","metric":"distance_km","status":"available","value":1000.0625,"elapsed_ms":${SystemClock.elapsedRealtime() + 2000}}""")
                assertEquals(true, call(owner, "showSmallCompanion"))
            }
            awaitState { it.isInPictureInPictureMode && call(it, "getInPip") == true }
            assertEquals(initial.copy(totalKm = 2.15625, experience = 865.625), PetSaveStore(context.filesDir).load())
        }
    }

    @Test fun measureMainThreadSaveAndUxLatency() {
        val delayed = InstrumentationRegistry.getArguments().getString("io_delay_ms", "0").toLong()
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val durations = mutableListOf<Double>()
        val dispatch = mutableListOf<Double>()
        var uxMs = 0.0
        var uiMs = 0.0
        launch().use { scenario ->
            var before: StrictMode.ThreadPolicy? = null
            onActivity { activity ->
                before = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(before).detectDiskReads().detectDiskWrites()
                    .penaltyListener(Executor { it.run() }) { violation ->
                        if (violation is android.os.strictmode.DiskWriteViolation) writes.incrementAndGet()
                        if (violation is android.os.strictmode.DiskReadViolation) reads.incrementAndGet()
                    }.build())
            }
            try {
                repeat(if (delayed > 0) 1 else 25) {
                    val requested = SystemClock.elapsedRealtimeNanos()
                    onActivity { activity ->
                        MainActivity::class.java.getDeclaredField("dirty").apply { isAccessible = true }.setBoolean(activity, true)
                        val started = SystemClock.elapsedRealtimeNanos()
                        call(activity, "save")
                        dispatch.add((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                    }
                    awaitSaved()
                    durations.add((SystemClock.elapsedRealtimeNanos() - requested) / 1_000_000.0)
                }
                var restrictedAt = 0L
                onActivity { activity ->
                    MainActivity::class.java.getDeclaredField("dirty").apply { isAccessible = true }.setBoolean(activity, true)
                    call(activity, "save")
                    val started = SystemClock.elapsedRealtimeNanos()
                    restrictedAt = started
                    call(activity, "receive", """{"kind":"ux","requires_do":true}""")
                    uxMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                }
                waitForUi("주차 후에 다시 만나요")
                uiMs = (SystemClock.elapsedRealtimeNanos() - restrictedAt) / 1_000_000.0
                awaitSaved()
            } finally { onActivity { StrictMode.setThreadPolicy(before!!) } }
            assertEquals(initial, PetSaveStore(context.filesDir).load())
        }
        val report = JSONObject().put("synthetic_io_delay_ms", delayed).put("save_ms", org.json.JSONArray(durations))
            .put("dispatch_ms", org.json.JSONArray(dispatch)).put("restriction_ui_ms", uiMs).put("pid", android.os.Process.myPid())
            .put("ux_callback_ms", uxMs).put("main_thread_disk_reads", reads.get()).put("main_thread_disk_writes", writes.get())
        java.io.File(context.cacheDir, "io-observation.json").writeText(report.toString())
        assertTrue("UX restriction must not wait for slow storage: ${uxMs}ms", uxMs < 200)
        assertTrue("Restriction UI must render while storage is pending: ${uiMs}ms", uiMs < 500)
    }
}
