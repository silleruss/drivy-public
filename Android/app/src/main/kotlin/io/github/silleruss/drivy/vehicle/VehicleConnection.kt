package io.github.silleruss.drivy.vehicle

import android.content.Context
import androidx.annotation.MainThread
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.silleruss.drivy.BuildConfig
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import org.json.JSONException
import org.json.JSONObject

class VehicleConnection(context: Context, private val callback: Callback) {
    fun interface Callback {
        fun onEvent(json: String)
    }

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val area = DoorArea(DoorValue.ROW_1_LEFT)
    private var vehicle: Vehicle? = null
    private var door: Door? = null
    private var listener: DoorPositionListener? = null
    private var odometer: OdometerProbe? = null
    private var speed: SpeedProbe? = null
    @Volatile private var active = false
    private var closed = false

    fun start() {
        main.post {
            if (active || closed) return@post
            active = true
            emit("connection", "starting")
            speed = SpeedProbe(context, callback).also { it.start() }
            try {
                val connected = Vehicle(context)
                vehicle = connected
                connected.initialize()
                odometer = OdometerProbe(connected.getOdometer(), callback).also { it.start() }
                val api = connected.getDoor()
                door = api
                api.checkDoorPositionCapabilities(
                    onComplete = { config, areas ->
                        main.post capability@{
                            if (!active) return@capability
                            if (config.accessType !in setOf(AccessType.READ, AccessType.READ_WRITE) || area !in areas) {
                                emit("error", "Front-left door is not readable on this vehicle.")
                                return@capability
                            }
                            emit("capability", config.accessType.name)
                            val updates = object : DoorPositionListener {
                                override fun onPositionUpdated(value: Int?) = emit("door", value)
                                override fun onFailed(error: Exception) = emit("error", error.toString())
                            }
                            listener = updates
                            try {
                                api.registerDoorPosition(area, updates)
                                read()
                            } catch (error: Exception) {
                                emit("error", error.toString())
                            }
                        }
                    },
                    onFailed = { emit("error", it.toString()) }
                )
            } catch (error: Exception) {
                emit("connection_error", error.toString())
            }
        }
    }

    fun read() {
        main.post {
            val api = door
            if (!active || api == null) return@post
            try {
                api.getCurrentDoorPosition(area,
                    onComplete = { emit("door", it) },
                    onFailed = { emit("error", it.toString()) })
            } catch (error: Exception) {
                emit("error", error.toString())
            }
        }
    }

    fun retrySpeed() { speed?.start() }

    @MainThread
    fun stop() {
        // Native lifecycle calls can close inputs before onStop returns.
        closed = true
        if (active) emit("connection", "stopping")
        active = false
        speed?.stop()
        speed = null
        odometer?.stop()
        odometer = null
        try {
            listener?.let { door?.unregisterDoorPosition(area, it) }
        } catch (error: Exception) {
            Log.w("DrivyVehicle", "Could not unregister door listener", error)
        } finally {
            try {
                vehicle?.release()
            } catch (error: Exception) {
                Log.w("DrivyVehicle", "Could not release Vehicle SDK", error)
            } finally {
                listener = null
                door = null
                vehicle = null
            }
        }
    }

    private fun emit(kind: String, value: Any?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { emit(kind, value) }
            return
        }
        if (!active) return
        try {
            val json = JSONObject().put("kind", kind).put("value", value ?: JSONObject.NULL)
                .put("elapsed_ms", SystemClock.elapsedRealtime()).toString()
            if (BuildConfig.DEBUG) Log.i("DrivyVehicle", "DRIVY_NATIVE_EVENT $json")
            callback.onEvent(json)
        } catch (error: JSONException) {
            throw IllegalStateException("Could not encode Vehicle SDK result", error)
        }
    }
}
