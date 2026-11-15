package io.github.silleruss.drivy.vehicle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.silleruss.drivy.BuildConfig
import ai.pleos.playground.vehicle.api.Odometer
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import org.json.JSONObject
import java.util.concurrent.TimeoutException

/** Read-only diagnostic. Each SDK connection owns a fresh probe. */
internal class OdometerProbe(private val api: Odometer, private val callback: VehicleConnection.Callback) {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var distanceRegistered = false
    private var timeRegistered = false
    private val pending = mutableMapOf<String, Runnable>()
    private var distancePolling = false
    private var distanceRevision = 0L
    private val refreshDistance = object : Runnable {
        override fun run() {
            if (closed || !distancePolling) return
            // A lost SDK binding otherwise only appears in the SDK's own logs.
            // Keep at most one read outstanding while checking for recovery.
            if (!pending.containsKey("distance_km/read"))
                read("distance_km", api::getCurrentDistanceDriven, "refresh")
            main.postDelayed(this, 10_000)
        }
    }

    private val distanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) = dispatch {
            distanceRevision++
            sample("distance_km", value, "update")
        }
        override fun onFailed(error: Exception) = dispatch {
            distanceRevision++
            failure("distance_km", "update", error)
        }
    }
    private val timeListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(value: Int?) = dispatch {
            sample("trip_minutes", value, "update")
        }
        override fun onFailed(error: Exception) = dispatch { failure("trip_minutes", "update", error) }
    }

    fun start() {
        check("distance_km", api::checkCurrentDistanceDrivenCapabilities) {
            // A subscription failure must not prevent a useful current-value read.
            attempt("distance_km", "subscribe") {
                distanceRegistered = true
                api.registerDistanceDriven(distanceListener)
            }
            read("distance_km", api::getCurrentDistanceDriven)
            startDistancePolling()
        }
        check("trip_minutes", api::checkTripCurrentDrivingTimeCapabilities) {
            attempt("trip_minutes", "subscribe") {
                timeRegistered = true
                api.registerTripCurrentDrivingTime(timeListener)
            }
            read("trip_minutes", api::getTripCurrentDrivingTime)
        }
    }

    private fun check(
        metric: String,
        request: ((CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit,
        onReadable: () -> Unit
    ) {
        val complete = waitFor(metric, "capability")
        try {
            request({ config ->
                complete {
                    val readable = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
                    emit("counter_capability", metric, config.accessType.name, "capability",
                        if (readable) "readable" else "unsupported")
                    if (readable) onReadable()
                }
            }, { complete { failure(metric, "capability", it) } })
        } catch (error: Exception) {
            complete { failure(metric, "capability", error) }
        }
    }

    private fun <T : Number> read(
        metric: String,
        request: ((T?) -> Unit, (Exception) -> Unit) -> Unit,
        source: String = "initial"
    ) {
        val revision = distanceRevision
        val complete = waitFor(metric, "read")
        try {
            request({ value -> complete {
                // A read started before a subscription update must not rebase newer mileage.
                if (metric != "distance_km" || revision == distanceRevision) sample(metric, value, source)
            } },
                { error -> complete {
                    if (metric != "distance_km" || revision == distanceRevision) failure(metric, "read", error)
                } })
        } catch (error: Exception) {
            complete { failure(metric, "read", error) }
        }
    }

    private fun waitFor(metric: String, source: String): (() -> Unit) -> Unit {
        val key = "$metric/$source"
        var timedOut = false
        lateinit var deadline: Runnable
        deadline = Runnable {
            if (!closed && pending[key] === deadline) {
                // The SDK may still hold this request while its service is disconnected.
                timedOut = true
                failure(metric, source, TimeoutException("No Vehicle SDK response within 20 seconds"))
            }
        }
        pending[key] = deadline
        main.postDelayed(deadline, 20_000)
        return { operation ->
            dispatch {
                if (pending[key] === deadline) {
                    pending.remove(key)
                    main.removeCallbacks(deadline)
                    if (!timedOut) operation()
                }
            }
        }
    }

    private fun sample(metric: String, value: Number?, source: String) {
        val invalid = value != null && (!value.toDouble().isFinite() || value.toDouble() < 0)
        if (metric == "distance_km" && !invalid && value != null && distanceRegistered)
            startDistancePolling()
        emit("counter", metric, if (invalid) null else value, source,
            when { invalid -> "invalid"; value == null -> "unavailable"; else -> "available" })
    }

    private fun failure(metric: String, source: String, error: Exception) {
        // SDK 2.3.2 obfuscates VehicleException in its AAR; preserve the original
        // error and match only this observed exact code, never arbitrary message substrings.
        val status = when {
            error is SecurityException -> "permission_denied"
            error is TimeoutException -> "timeout"
            error.message == "ERROR_NOT_SUPPORTED_PROPERTY" -> "unsupported"
            else -> "error"
        }
        if (metric == "distance_km" && source == "read" && status in setOf("permission_denied", "unsupported"))
            stopDistancePolling()
        emit("counter_error", metric, error.toString(), source, status)
    }

    private fun attempt(metric: String, source: String, operation: () -> Unit) {
        try { operation() } catch (error: Exception) { failure(metric, source, error) }
    }

    private fun dispatch(operation: () -> Unit) {
        main.post { if (!closed) operation() }
    }

    private fun startDistancePolling() {
        if (closed || distancePolling) return
        distancePolling = true
        main.postDelayed(refreshDistance, 10_000)
    }

    private fun stopDistancePolling() {
        distancePolling = false
        main.removeCallbacks(refreshDistance)
    }

    private fun emit(kind: String, metric: String, value: Any?, source: String, status: String) {
        if (closed) return
        val json = JSONObject().put("kind", kind).put("metric", metric)
            .put("value", value ?: JSONObject.NULL).put("source", source).put("status", status)
            .put("unit", if (metric == "distance_km") "km" else "min")
            .put("elapsed_ms", SystemClock.elapsedRealtime()).toString()
        if (BuildConfig.DEBUG) Log.i("DrivyVehicle", "DRIVY_NATIVE_EVENT $json")
        callback.onEvent(json)
    }

    fun stop() {
        // Called on the Android main thread before releasing the shared Vehicle connection.
        closed = true
        stopDistancePolling()
        pending.values.forEach { main.removeCallbacks(it) }
        pending.clear()
        try {
            if (distanceRegistered) api.unregisterDistanceDriven(distanceListener)
        } catch (error: Exception) {
            Log.w("DrivyVehicle", "Could not unregister distance listener", error)
        }
        try {
            if (timeRegistered) api.unregisterTripCurrentDrivingTime(timeListener)
        } catch (error: Exception) {
            Log.w("DrivyVehicle", "Could not unregister driving time listener", error)
        }
    }
}
