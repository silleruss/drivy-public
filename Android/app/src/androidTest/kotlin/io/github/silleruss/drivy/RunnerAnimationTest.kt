package io.github.silleruss.drivy

import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections

/** Wall-clock renderer check, separate from Compose's virtual animation clock. */
class RunnerAnimationTest {
    @Test fun gameplayDrawsAtLeastThirtyFramesPerSecond() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName in setOf("io.github.silleruss.drivy.runnerprobe.uitest", "io.github.silleruss.drivy.uitest"))
        val frames = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val thread = HandlerThread("runner-frame-test").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames.add(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) to metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        try {
            ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java)).use { scenario ->
                scenario.onActivity {
                    it.setContent { RunnerScreen(Companion.TORI, 0) {} }
                    it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                }
                fun clickStart(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.text?.toString() == "시작") {
                        var target: AccessibilityNodeInfo? = node
                        while (target != null && !target.isClickable) target = target.parent
                        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    }
                    for (i in 0 until node.childCount) if (clickStart(node.getChild(i))) return true
                    return false
                }
                var started = false
                repeat(50) {
                    if (!started) { started = clickStart(instrumentation.uiAutomation.rootInActiveWindow); if (!started) Thread.sleep(100) }
                }
                assertTrue("start button must be accessible", started)
                Thread.sleep(250)
                val from = System.nanoTime()
                Thread.sleep(1500)
                val until = System.nanoTime()
                val sample = synchronized(frames) { frames.filter { it.first in from..until } }
                val fps = sample.size * 1_000_000_000.0 / (until - from)
                val sorted = sample.map { it.second / 1_000_000.0 }.sorted()
                val report = "frames=${sample.size}, seconds=${(until - from) / 1e9}, fps=$fps, p95Ms=${sorted.getOrNull((sorted.size * .95).toInt())}"
                context.getFileStreamPath("runner-frame-metrics.txt").writeText(report)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    context.getFileStreamPath("runner-fullscreen.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                assertTrue(report, fps >= 30.0)
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            }
        } finally { thread.quitSafely() }
    }
}
