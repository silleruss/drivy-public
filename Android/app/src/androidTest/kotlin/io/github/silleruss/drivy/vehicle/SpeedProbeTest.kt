package io.github.silleruss.drivy.vehicle

import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class SpeedProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val deniedContext = object : ContextWrapper(instrumentation.targetContext) {
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED
    }

    @Test fun missingPermissionProducesNoSpeedValue() {
        val event = AtomicReference<String>()
        val probe = SpeedProbe(deniedContext) { event.set(it) }
        try {
            instrumentation.runOnMainSync { probe.start() }
            instrumentation.waitForIdleSync()
            val result = JSONObject(event.get())
            assertEquals("speed", result.getString("kind"))
            assertEquals("permission_denied", result.getString("status"))
            assertTrue(result.isNull("value"))
        } finally {
            instrumentation.runOnMainSync { probe.stop() }
        }
    }

    @Test fun stopPreventsQueuedStartAndCallbacks() {
        val event = AtomicReference<String>()
        val probe = SpeedProbe(deniedContext) { event.set(it) }
        instrumentation.runOnMainSync {
            probe.start()
            probe.stop()
        }
        instrumentation.waitForIdleSync()
        assertNull(event.get())
    }
}
