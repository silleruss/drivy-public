package io.github.silleruss.drivy.vehicle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.silleruss.drivy.BuildConfig
import org.json.JSONException
import org.json.JSONObject

class DrivingProbe(private val context: Context, private val callback: VehicleConnection.Callback) :
    Application.ActivityLifecycleCallbacks {
    private val activity = context as? Activity
    private val application = context.applicationContext as Application
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var started = false
    private var closed = false

    fun start() {
        main.post {
            if (started || closed) return@post
            started = true
            application.registerActivityLifecycleCallbacks(this)
            emit("lifecycle", "probe_started", true)
            if (Build.VERSION.SDK_INT < 29) {
                emit("ux_error", "This diagnostic requires AAOS API 29 or newer.", true)
                return@post
            }
            try {
                car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                    if (closed) return@createCar
                    if (!ready) {
                        restrictions = null
                        emit("ux_error", "Car service disconnected.", true)
                        return@createCar
                    }
                    try {
                        val manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                        restrictions = manager
                        manager.registerListener(::onRestrictions)
                        onRestrictions(manager.currentCarUxRestrictions)
                    } catch (error: Exception) {
                        emit("ux_error", error.toString(), true)
                    }
                }
            } catch (error: Exception) {
                emit("ux_error", error.toString(), true)
            }
        }
    }

    private fun onRestrictions(state: CarUxRestrictions?) {
        if (state == null) emit("ux_error", "No UX restriction state available.", true)
        else emit("ux", state.activeRestrictions, state.isRequiresDistractionOptimization)
    }

    fun refresh() {
        main.post {
            if (closed) return@post
            try {
                val manager = restrictions ?: return@post // Initial async connection is still pending.
                onRestrictions(manager.currentCarUxRestrictions)
            } catch (error: Exception) {
                emit("ux_error", error.toString(), true)
            }
        }
    }

    fun stop() {
        main.post {
            if (closed) return@post
            closed = true
            application.unregisterActivityLifecycleCallbacks(this)
            try {
                restrictions?.unregisterListener()
            } catch (error: Exception) {
                Log.w("DrivyVehicle", "Could not unregister UX listener", error)
            } finally {
                car?.disconnect()
                restrictions = null
                car = null
            }
        }
    }

    private fun emit(kind: String, value: Any, requiresDo: Boolean) {
        if (closed) return
        try {
            val json = JSONObject().put("kind", kind).put("value", value)
                .put("requires_do", requiresDo).put("elapsed_ms", SystemClock.elapsedRealtime()).toString()
            if (BuildConfig.DEBUG) Log.i("DrivyVehicle", "DRIVY_NATIVE_EVENT $json")
            callback.onEvent(json)
        } catch (error: JSONException) {
            throw IllegalStateException("Could not encode driving diagnostic", error)
        }
    }

    private fun lifecycle(current: Activity, state: String) {
        if (current === activity) emit("lifecycle", state, true)
    }

    override fun onActivityCreated(current: Activity, state: Bundle?) = lifecycle(current, "created")
    override fun onActivityStarted(current: Activity) = lifecycle(current, "started")
    override fun onActivityResumed(current: Activity) = lifecycle(current, "resumed")
    override fun onActivityPaused(current: Activity) = lifecycle(current, "paused")
    override fun onActivityStopped(current: Activity) = lifecycle(current, "stopped")
    override fun onActivityDestroyed(current: Activity) = lifecycle(current, "destroyed")
    override fun onActivitySaveInstanceState(current: Activity, state: Bundle) = Unit
}
