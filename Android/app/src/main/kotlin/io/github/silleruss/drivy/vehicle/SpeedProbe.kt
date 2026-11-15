package io.github.silleruss.drivy.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import io.github.silleruss.drivy.BuildConfig
import org.json.JSONObject
import kotlin.math.abs

/** Observed vehicle speed, separate from the navigation speed limit. */
internal class SpeedProbe(private val context: Context, private val callback: VehicleConnection.Callback) {
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var closed = false
    private val timeout = Runnable { if (!closed) emit("timeout") }
    private val updates = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (closed || value.propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED) return
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) {
                emit("unavailable")
                return
            }
            val speed = (value.value as? Float)?.toDouble()
            val measuredMs = value.timestamp / 1_000_000
            val now = SystemClock.elapsedRealtime()
            if (speed == null || !speed.isFinite() || measuredMs <= 0 || measuredMs > now || now - measuredMs > 3_000) {
                emit("invalid")
                return
            }
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, 5_000)
            // AAOS reports reverse motion as negative velocity; scoring uses speed magnitude.
            emit("available", abs(speed) * 3.6, measuredMs)
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) emit("error")
        }
    }

    fun start() {
        main.post {
            if (closed || car != null) return@post
            if (context.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
                emit("permission_denied")
                return@post
            }
            main.postDelayed(timeout, 20_000)
            try {
                car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                    if (closed) return@createCar
                    if (!ready) {
                        clearSubscription()
                        emit("unavailable")
                    } else subscribe(connected)
                }
            } catch (error: Exception) {
                emit(if (error is SecurityException) "permission_denied" else "error")
            }
        }
    }

    @Suppress("DEPRECATION") // registerCallback is available on the Pleos API 34 runtime.
    private fun subscribe(connected: Car) {
        clearSubscription()
        try {
            val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            properties = manager
            val config = manager.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED)
            if (config == null || config.maxSampleRate < 1f) {
                emit("unsupported")
                return
            }
            if (!manager.registerCallback(updates, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI))
                emit("unsupported")
        } catch (error: Exception) {
            emit(if (error is SecurityException) "permission_denied" else "error")
        }
    }

    @Suppress("DEPRECATION")
    private fun clearSubscription() {
        try {
            properties?.unregisterCallback(updates)
        } catch (error: Exception) {
            Log.w("DrivyVehicle", "Could not unregister speed listener", error)
        } finally {
            properties = null
        }
    }

    private fun emit(status: String, value: Double? = null, elapsedMs: Long = SystemClock.elapsedRealtime()) {
        if (closed) return
        if (status != "available") main.removeCallbacks(timeout)
        val json = JSONObject().put("kind", "speed").put("status", status)
            .put("value", value ?: JSONObject.NULL).put("unit", "km/h").put("elapsed_ms", elapsedMs).toString()
        if (BuildConfig.DEBUG) Log.i("DrivyVehicle", "DRIVY_NATIVE_EVENT $json")
        callback.onEvent(json)
    }

    @MainThread
    fun stop() {
        closed = true
        main.removeCallbacksAndMessages(null)
        clearSubscription()
        try {
            car?.disconnect()
        } catch (error: Exception) {
            Log.w("DrivyVehicle", "Could not disconnect speed service", error)
        } finally {
            car = null
        }
    }
}
