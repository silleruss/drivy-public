package io.github.silleruss.drivy.vehicle

import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PleosSdkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun vehicleReturnsDoorAndDistance() {
        val received = CountDownLatch(2)
        val failure = AtomicReference<Exception>()
        val vehicle = Vehicle(instrumentation.targetContext)
        val failed: (Exception) -> Unit = { failure.set(it); received.countDown() }
        try {
            instrumentation.runOnMainSync {
                vehicle.initialize()
                vehicle.getDoor().getCurrentDoorPosition(DoorArea(DoorValue.ROW_1_LEFT),
                    onComplete = {
                        if (it == null) failure.set(IllegalStateException("Null door value"))
                        received.countDown()
                    }, onFailed = failed)
                vehicle.getOdometer().getCurrentDistanceDriven(onComplete = {
                    if (it == null || !it.isFinite() || it < 0) failure.set(IllegalStateException("Invalid distance"))
                    received.countDown()
                }, onFailed = failed)
            }
            assertTrue("No Vehicle door/distance response", received.await(30, TimeUnit.SECONDS))
            assertNull("Vehicle read failed", failure.get())
        } finally {
            instrumentation.runOnMainSync { vehicle.release() }
        }
    }

    @Test fun naviConnectsAndReturnsRouteState() {
        val connected = CountDownLatch(1)
        val received = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val navi = NaviHelper(instrumentation.targetContext)
        val listener = object : NaviHelperEventListener {
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) { received.countDown() }
        }
        try {
            instrumentation.runOnMainSync {
                navi.initialize(onConnected = { connected.countDown() }, onFailed = {
                    failure.set(it)
                    connected.countDown()
                })
            }
            assertTrue("Navi connection timed out", connected.await(30, TimeUnit.SECONDS))
            assertNull("Navi initialization failed", failure.get())
            instrumentation.runOnMainSync {
                navi.addListener(listener)
                navi.getRouteStateInfo()
            }
            assertTrue("No Navigation route-state callback", received.await(30, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                navi.removeListener(listener)
                navi.release()
            }
        }
    }
}
