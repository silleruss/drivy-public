package io.github.silleruss.drivy

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.silleruss.drivy.vehicle.DrivingProbe
import io.github.silleruss.drivy.vehicle.VehicleConnection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Reproducible screen capture on the isolated uitest app, using labelled synthetic inputs. */
class SubmissionDemoTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val output get() = File(context.filesDir, "submission-demo").apply { mkdirs() }
    private val saves get() = MainActivity::class.java.getDeclaredField("saves").apply { isAccessible = true }.get(null) as ExecutorService
    private fun drain() { saves.submit {}.get(15, TimeUnit.SECONDS) }
    private fun call(a: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(a)
    private fun receive(a: MainActivity, json: String) { MainActivity::class.java.getDeclaredMethod("receive", String::class.java).apply { isAccessible = true }.invoke(a, json) }
    private fun ready(s: ActivityScenario<MainActivity>) {
        var done = false
        compose.waitUntil(30_000) { s.onActivity { done = call(it, "getInitialized") == true }; done }
        s.onActivity { a ->
            val session = MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
            session.setInt(a, session.getInt(a) + 1)
            (MainActivity::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(a) as? VehicleConnection)?.stop()
            (MainActivity::class.java.getDeclaredField("driving").apply { isAccessible = true }.get(a) as? DrivingProbe)?.stop()
            receive(a, """{"kind":"ux","requires_do":false}""")
        }
    }
    private fun click(text: String) {
        val node = compose.onNodeWithText(text, substring = false)
        if (text !in listOf("테스트", "드라이비", "닫기")) node.performScrollTo()
        node.performClick()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(1800)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        File(output, "scenes.tsv").appendText("${SystemClock.elapsedRealtime()}\t$name\n")
        SystemClock.sleep(3200)
    }

    @Test fun captureCoreFeatures() {
        check(context.packageName.endsWith(".uitest"))
        org.junit.Assume.assumeTrue("Run explicitly with submission_demo=true", InstrumentationRegistry.getArguments().getString("submission_demo") == "true")
        drain()
        context.getFileStreamPath("pet-progress.json").delete()
        context.getFileStreamPath("pet-progress.json.bak").delete()
        assertTrue(DriveJournalStore(context.filesDir).delete())
        context.getSharedPreferences("companion-display", 0).edit().clear().commit()
        output.deleteRecursively(); output.mkdirs()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            ready(s)
            click("네루"); capture("01-selection")
            click("네루와 동행 시작")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("자세히").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            capture("02-home")
            click("테스트"); capture("03-test-menu")
            click("차량 데이터 값 설정")
            click("입력값으로 성장 확인")
            compose.onNodeWithText("실제 저장 기록은 바뀌지 않았어요.", substring = true).assertExists()
            capture("04-synthetic-distance")
            assertEquals(0.0, PetSaveStore(context.filesDir).load().experience, 0.0)
            click("‹ 테스트 목록"); click("성장과 캐릭터")
            click("50000.0 XP 적용"); drain()
            assertEquals(50000.0, PetSaveStore(context.filesDir).load().experience, 0.0)
            click("드라이비"); capture("05-growth-stage2")
            click("테스트"); click("성장과 캐릭터"); click("300000.0 XP 적용"); drain()
            click("드라이비"); capture("06-growth-stage3")
            s.recreate(); ready(s)
            assertEquals(300000.0, PetSaveStore(context.filesDir).load().experience, 0.0)
            capture("07-recreated")
            click("기록·목표·설정")
            click("기록·분석에 동의하고 시작")
            click("부드러운 출발"); click("시내 위주")
            capture("08-report-settings")
            click("닫기")
            s.onActivity { a ->
                val now = SystemClock.elapsedRealtime()
                for (i in 0..2) {
                    receive(a, """{"kind":"counter","metric":"distance_km","status":"available","value":${1000.0 + i * .025},"elapsed_ms":${now + i * 1000}}""")
                    receive(a, """{"kind":"speed","status":"available","value":18,"elapsed_ms":${now + i * 1000}}""")
                }
                call(a, "persistJournal")
            }
            drain()
            click("최근 기록 상세 보기"); capture("09-report-detail")
            click("내 기록"); click("정체가 있었음"); click("도움 됨")
            capture("10-report-reflection")
            click("닫기")
            click("자세히"); capture("11-details"); click("닫기")
            s.onActivity { receive(it, """{"kind":"ux","requires_do":true}""") }
            capture("12-restricted")
            compose.onNodeWithText("드라이비 런").assertDoesNotExist()
            s.onActivity { receive(it, """{"kind":"ux","requires_do":false}""") }
            click("드라이비 런")
            compose.waitUntil(30_000) { compose.onAllNodesWithText("시작").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            capture("13-runner-ready")
            compose.mainClock.autoAdvance = false
            click("시작")
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithText("점프").performClick()
            compose.mainClock.advanceTimeBy(300)
            capture("14-runner-play")
            compose.mainClock.advanceTimeBy(10000)
            capture("15-runner-result")
            compose.onNodeWithText("게임 종료").assertExists()
            compose.mainClock.autoAdvance = true
        }
        drain()
        assertEquals(Companion.NERU, PetSaveStore(context.filesDir).load().companion)
        File(output, "passed.txt").writeText("Core demo assertions passed. UX and vehicle samples are synthetic. Recreation is not a process restart.\n")
    }
    @Test fun captureNewProcessAndLiveStatus() {
        check(context.packageName.endsWith(".uitest"))
        org.junit.Assume.assumeTrue("Run explicitly with submission_demo=true", InstrumentationRegistry.getArguments().getString("submission_demo") == "true")
        val stored = PetSaveStore(context.filesDir).load()
        assertEquals(Companion.NERU, stored.companion)
        assertTrue(stored.experience >= 300000.0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(30_000) { compose.onAllNodesWithText("자세히").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            capture("16-process-restart")
            click("테스트"); click("실시간 차량 데이터")
            capture("17-live-sdk-status")
            click("드라이비")
            compose.waitForIdle()
            scenario.onActivity { activity ->
                CompanionDisplay(context).enabled = true
                call(activity, "readDisplaySettings")
            }
            val user = android.os.Process.myUid() / 100000
            fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
                java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()
            }
            val permission = "appops set --user $user ${context.packageName} SYSTEM_ALERT_WINDOW "
            shell(permission + "allow")
            try {
                scenario.onActivity { assertEquals(true, call(it, "showSmallCompanion")) }
                val title = "DRIVY Companion ${context.packageName}"
                val deadline = SystemClock.uptimeMillis() + 20_000
                var dump = shell("dumpsys window windows")
                while (!dump.contains(title) && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(100); dump = shell("dumpsys window windows")
                }
                assertTrue("Actual overlay must be visible", dump.contains(title))
                SystemClock.sleep(2000)
                instrumentation.uiAutomation.takeScreenshot().let { b ->
                    File(output, "18-small-window.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
                }
                File(output, "scenes.tsv").appendText("${SystemClock.elapsedRealtime()}\t18-small-window\n")
                SystemClock.sleep(5000)
                val window = shell("dumpsys window windows").substringAfter(title).substringBefore("Window #")
                val frame = Regex(" frame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(window)!!
                val (left, top, right, bottom) = frame.groupValues.drop(1).map(String::toInt)
                shell("input tap ${(left + right) / 2} ${(top + bottom) / 2}")
                compose.waitUntil(15_000) { compose.onAllNodesWithText("자세히").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
                capture("19-window-return")
            } finally {
                context.stopService(android.content.Intent(context, CompanionOverlayService::class.java))
                shell(permission + "default")
                CompanionDisplay(context).enabled = false
            }
        }
        File(output, "restart-pid.txt").writeText(android.os.Process.myPid().toString())
    }

}
