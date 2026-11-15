package io.github.silleruss.drivy

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.github.silleruss.drivy.vehicle.DrivingProbe
import io.github.silleruss.drivy.vehicle.VehicleConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class DriveReportActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val saves get() = MainActivity::class.java.getDeclaredField("saves").apply { isAccessible = true }.get(null) as ExecutorService
    private fun drain() { saves.submit {}.get(15, TimeUnit.SECONDS) }
    private fun call(activity: MainActivity, method: String): Any? =
        MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)
    private fun receive(activity: MainActivity, json: String) {
        MainActivity::class.java.getDeclaredMethod("receive", String::class.java).apply { isAccessible = true }.invoke(activity, json)
    }
    private fun journal(activity: MainActivity) = call(activity, "getJournal") as DriveJournal

    private fun ready(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var initialized = false
        while (!initialized && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { initialized = call(it, "getInitialized") == true }
            if (!initialized) SystemClock.sleep(20)
        }
        assertTrue(initialized)
        scenario.onActivity { activity ->
            val session = MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
            session.setInt(activity, session.getInt(activity) + 1)
            (MainActivity::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(activity) as? VehicleConnection)?.stop()
            (MainActivity::class.java.getDeclaredField("driving").apply { isAccessible = true }.get(activity) as? DrivingProbe)?.stop()
            receive(activity, """{"kind":"ux","requires_do":false}""")
        }
    }

    @Test fun stoppingAndRestartingCreatesDistinctPartialRecordsAndDeletionPreservesPet() {
        check(context.packageName.endsWith(".uitest"))
        drain()
        val store = DriveJournalStore(context.filesDir)
        assertTrue(store.delete())
        assertTrue(store.save(DriveJournal()))
        assertTrue(PetSaveStore(context.filesDir).save(PetProgress(2.0, 200.0, Companion.TORI)))
        CompanionDisplay(context).enabled = false
        saves.submit {
            MainActivity::class.java.getDeclaredField("unsavedJournal").apply { isAccessible = true }.set(null, null)
        }.get(15, TimeUnit.SECONDS)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario)
                compose.onNodeWithText("기록·목표·설정").performScrollTo().performClick()
                compose.onNodeWithText("기록·분석에 동의하고 시작").performScrollTo().performClick()
                scenario.onActivity { receive(it, """{"kind":"speed","status":"permission_denied","elapsed_ms":${SystemClock.elapsedRealtime()}}""") }
                compose.onNodeWithText("부드러운 출발", substring = false).performScrollTo().performClick()
                compose.onNodeWithText("시내 위주", substring = false).performScrollTo().performClick()
                compose.onNodeWithText("닫기").performClick()
                scenario.onActivity { activity ->
                    val time = SystemClock.elapsedRealtime() + 1
                    for (i in 0..1) {
                        receive(activity, """{"kind":"counter","metric":"distance_km","status":"available","value":${1000.0 + i * .03125},"elapsed_ms":${time + i * 1000}}""")
                        receive(activity, """{"kind":"speed","status":"available","value":${18.0 * i},"elapsed_ms":${time + i * 1000}}""")
                    }
                    assertEquals(1, journal(activity).records.size)
                    assertEquals(1000L, journal(activity).records.single().coveredMs)
                    assertEquals(DriveGoal.START, journal(activity).records.single().goal)
                    assertEquals(DriveEnvironment.CITY, journal(activity).records.single().environment)
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                drain()
                val stopped = DriveJournalStore(context.filesDir).load(System.currentTimeMillis()).records
                assertEquals(1, stopped.size)
                assertEquals("stopped", stopped.single().endReason)
                scenario.moveToState(Lifecycle.State.RESUMED)
                ready(scenario)
                scenario.onActivity { activity ->
                    receive(activity, """{"kind":"speed","status":"available","value":90,"elapsed_ms":${SystemClock.elapsedRealtime() + 1}}""")
                    val records = journal(activity).records
                    assertEquals(2, records.size)
                    assertNotEquals(records[0].id, records[1].id)
                    assertEquals(0L, records[1].coveredMs)
                    call(activity, "save")
                }
                drain()
                val petBefore = context.getFileStreamPath("pet-progress.json").readText()
                scenario.onActivity { call(it, "deleteJournal") }
                drain()
                assertFalse(context.getFileStreamPath("drive-journal.json").exists())
                assertEquals(petBefore, context.getFileStreamPath("pet-progress.json").readText())
                scenario.onActivity { activity ->
                    receive(activity, """{"kind":"speed","status":"available","value":90,"elapsed_ms":${SystemClock.elapsedRealtime() + 1000}}""")
                    assertEquals(DriveJournal(), journal(activity))
                }
            }
        } finally {
            drain()
            assertTrue(store.delete())
        }
    }

    @Test fun failedDeletionStaysDisabledAcrossRecreationUntilRetryCompletes() {
        check(context.packageName.endsWith(".uitest"))
        drain()
        val store = DriveJournalStore(context.filesDir)
        val blocked = context.getFileStreamPath("drive-journal.json.tmp")
        assertTrue(store.delete())
        assertTrue(store.save(DriveJournal(true)))
        assertTrue(PetSaveStore(context.filesDir).save(PetProgress(2.0, 200.0, Companion.TORI)))
        CompanionDisplay(context).enabled = false
        saves.submit {
            MainActivity::class.java.getDeclaredField("unsavedJournal").apply { isAccessible = true }.set(null, null)
        }.get(15, TimeUnit.SECONDS)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario)
                assertTrue(blocked.mkdir())
                java.io.File(blocked, "blocked").writeText("prevent deletion")
                scenario.onActivity { call(it, "deleteJournal") }
                drain()
                scenario.recreate()
                ready(scenario)
                scenario.onActivity { activity ->
                    assertEquals("delete_failed", call(activity, "getJournalStatus"))
                    receive(activity, """{"kind":"speed","status":"available","value":90,"elapsed_ms":${SystemClock.elapsedRealtime() + 1}}""")
                    assertEquals(DriveJournal(), journal(activity))
                }
                assertTrue(blocked.deleteRecursively())
                scenario.onActivity { call(it, "deleteJournal") }
                drain()
                assertFalse(context.getFileStreamPath("drive-journal.delete-pending").exists())
                assertEquals(DriveJournal(), DriveJournalStore(context.filesDir).load(System.currentTimeMillis()))
            }
        } finally {
            drain()
            blocked.deleteRecursively()
            assertTrue(store.delete())
        }
    }
}
