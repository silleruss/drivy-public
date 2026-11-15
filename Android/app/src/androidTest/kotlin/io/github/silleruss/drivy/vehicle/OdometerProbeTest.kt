package io.github.silleruss.drivy.vehicle

import ai.pleos.playground.vehicle.api.Odometer
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import android.os.Handler
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class OdometerProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Suppress("UNCHECKED_CAST")
    private fun exerciseReadFailure(error: Exception, shouldRetry: Boolean) {
        val calls = AtomicInteger()
        val events = CopyOnWriteArrayList<JSONObject>()
        val initial = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        var finishRead: ((Float?) -> Unit)? = null
        var listener: DistanceDrivenListener? = null
        val api = Proxy.newProxyInstance(Odometer::class.java.classLoader, arrayOf(Odometer::class.java)) { _, method, args ->
            when (method.name) {
                "checkCurrentDistanceDrivenCapabilities" -> (args[0] as (CapabilityConfig) -> Unit)(CapabilityConfig(AccessType.READ))
                "checkTripCurrentDrivingTimeCapabilities" -> (args[0] as (CapabilityConfig) -> Unit)(CapabilityConfig(AccessType.NONE))
                "registerDistanceDriven" -> { listener = args[0] as DistanceDrivenListener }
                "getCurrentDistanceDriven" -> when (calls.incrementAndGet()) {
                    1 -> (args[0] as (Float?) -> Unit)(1000f)
                    2 -> if (error is TimeoutException) finishRead = args[0] as (Float?) -> Unit
                         else (args[1] as (Exception) -> Unit)(error)
                    else -> { finishRead = args[0] as (Float?) -> Unit }
                }
            }
            null
        } as Odometer
        val probe = OdometerProbe(api) { json ->
            val event = JSONObject(json)
            events.add(event)
            if (event.optString("metric") == "distance_km") {
                if (event.optString("source") == "initial" && event.optString("status") == "available") initial.countDown()
                if (event.optString("kind") == "counter_error") failed.countDown()
                if (event.optString("source") == "refresh" && event.optString("status") == "available") recovered.countDown()
            }
        }
        val handler = OdometerProbe::class.java.getDeclaredField("main").apply { isAccessible = true }.get(probe) as Handler
        val refresh = OdometerProbe::class.java.getDeclaredField("refreshDistance").apply { isAccessible = true }.get(probe) as Runnable
        fun tick() = instrumentation.runOnMainSync { handler.removeCallbacks(refresh); refresh.run() }
        try {
            instrumentation.runOnMainSync { probe.start() }
            assertTrue("Initial read did not complete", initial.await(5, TimeUnit.SECONDS))
            tick()
            if (error is TimeoutException) {
                instrumentation.runOnMainSync { listener!!.onDistanceDrivenUpdated(1000.5f) }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val pending = OdometerProbe::class.java.getDeclaredField("pending").apply { isAccessible = true }
                        .get(probe) as Map<String, Runnable>
                    val deadline = pending.getValue("distance_km/read")
                    handler.removeCallbacks(deadline)
                    deadline.run()
                }
            }
            assertTrue("Read failure was not reported", failed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertEquals(shouldRetry, handler.hasCallbacks(refresh)) }
            if (shouldRetry) {
                if (error is TimeoutException) {
                    tick()
                    tick()
                    assertEquals("Timed-out SDK request must not be duplicated", 2, calls.get())
                    instrumentation.runOnMainSync { finishRead!!.invoke(999f) }
                    instrumentation.waitForIdleSync()
                    assertEquals("A late value must not be delivered", 3, events.count {
                        it.optString("metric") == "distance_km" && it.optString("kind") != "counter_capability"
                    })
                }
                tick()
                tick()
                assertEquals("Only one read may remain in flight", 3, calls.get())
                instrumentation.runOnMainSync { finishRead!!.invoke(1001f) }
                assertTrue("Polling did not recover", recovered.await(5, TimeUnit.SECONDS))
                val expected = if (error is TimeoutException) listOf("initial", "update", "read", "refresh")
                    else listOf("initial", "read", "refresh")
                assertEquals(expected, events.filter {
                    it.optString("metric") == "distance_km" && it.optString("kind") != "counter_capability"
                }.map { it.optString("source") })
            }
        } finally {
            instrumentation.runOnMainSync { probe.stop(); assertFalse(handler.hasCallbacks(refresh)) }
        }
    }

    @Test fun transientReadFailureKeepsOneRetryInFlightAndRecovers() {
        exerciseReadFailure(IllegalStateException("temporary outage"), true)
        exerciseReadFailure(TimeoutException("no response"), true)
    }

    @Test fun permissionAndUnsupportedFailuresStopPolling() {
        exerciseReadFailure(SecurityException("denied"), false)
        exerciseReadFailure(IllegalStateException("ERROR_NOT_SUPPORTED_PROPERTY"), false)
    }
}
