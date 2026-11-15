package io.github.silleruss.drivy

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.util.Rational
import androidx.activity.addCallback
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.car.Car
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.silleruss.drivy.vehicle.DrivingProbe
import io.github.silleruss.drivy.vehicle.VehicleConnection
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: PetSaveStore
    private lateinit var growth: DistanceGrowth
    private lateinit var habits: DrivingScore
    private var total by mutableStateOf(0.0)
    private var experience by mutableStateOf(0.0)
    private var drivingScore by mutableStateOf<Int?>(null)
    private var lastDistanceMs = -1L
    private var restricted by mutableStateOf(true)
    private var resumed by mutableStateOf(false)
    private var dataStatus by mutableStateOf("차량 데이터를 기다리고 있어요")
    private var storageStatus by mutableStateOf("new")
    private var companion by mutableStateOf(Companion.TORI)
    private lateinit var display: CompanionDisplay
    private var needsSelection by mutableStateOf(false)
    private var selectionSubmitted = false
    private var uxKnown by mutableStateOf(false)
    private var inPip by mutableStateOf(false)
    private var inGame by mutableStateOf(false)
    private var smallEnabled by mutableStateOf(false)
    private var smallMode by mutableStateOf(SmallWindowMode.OVERLAY)
    private var smallCorner by mutableStateOf(PetCorner.BOTTOM_RIGHT)
    private var overlayGranted by mutableStateOf(false)
    private var smallNotice by mutableStateOf<String?>(null)
    private var leavingForSettings = false
    private val pipSupported get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        leavingForSettings = false
        overlayGranted = Settings.canDrawOverlays(this)
        smallEnabled = overlayGranted
        display.enabled = smallEnabled
        smallNotice = if (overlayGranted) null else "다른 앱 위에 표시 권한이 없어 드라이비를 껐어요."
        display.notice = smallNotice
    }
    private var connection: VehicleConnection? = null
    private var driving: DrivingProbe? = null
    private var session = 0
    private var initialized by mutableStateOf(false)
    private var loadingError by mutableStateOf(false)
    private var started = false
    private var dirty = false
    private var queuedProgress: PetProgress? = null
    private var saveGeneration = 0
    private var smallRequested = false
    private val saveLater = Runnable { save() }
    private val scoreExpires = Runnable { refreshDrivingScore() }
    private val retryVehicleConnection = Runnable {
        if (started && initialized) {
            connection?.stop()
            driving?.stop()
            connection = null
            driving = null
            growth.invalidate()
            habits.invalidate()
            refreshDrivingScore()
            connectVehicle()
        }
    }
    private var speedPermissionGranted by mutableStateOf(false)
    private val speedPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        speedPermissionGranted = granted
        if (granted) connection?.retrySpeed()
    }

    private var vehicleSamples by mutableStateOf(emptyMap<String, String>())
    private var testTab by mutableStateOf(false)
    private var testSeed: Int? = null
    private lateinit var journalStore: DriveJournalStore
    private var journal by mutableStateOf(DriveJournal())
    private var journalStatus by mutableStateOf("loaded")
    private var recorder: DriveRecorder? = null
    private var journalGeneration = 0
    private val journalLater = Runnable { persistJournal() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreLegacyLauncherEntries(this)
        display = CompanionDisplay(this)
        if (display.mode == SmallWindowMode.PIP) display.mode = SmallWindowMode.OVERLAY
        readDisplaySettings()
        onBackPressedDispatcher.addCallback(this) {
            if (testTab) testTab = false else if (inGame) inGame = false else if (!initialized || !showSmallCompanion()) finish()
        }
        setContent {
            Column(Modifier.fillMaxSize()) {
                if (BuildConfig.DEBUG && initialized && resumed && uxKnown && !restricted && !inPip && !inGame) {
                    Surface(color = Color(0xFF141420)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            TextButton(onClick = { testTab = false }, modifier = Modifier.heightIn(min = 76.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = if (!testTab) Color(0xFF9DECC4) else Color(0xFFFFF1E2))) {
                                Text("드라이비", fontSize = 20.sp)
                            }
                            Button(onClick = { testTab = true }, modifier = Modifier.padding(vertical = 8.dp).heightIn(min = 56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9DECC4), contentColor = Color(0xFF141420))) {
                                Text("테스트", fontSize = 20.sp)
                            }
                        }
                    }
                }
                when {
                    BuildConfig.DEBUG && testTab && initialized && resumed && uxKnown && !restricted && !inPip -> TestControls(
                        PetProgress(total, experience, companion, habits.penalty), storageStatus, store.canSave,
                        onApply = { progress ->
                            if (BuildConfig.DEBUG && resumed && uxKnown && !restricted && store.canSave) {
                                growth = DistanceGrowth(progress.totalKm, progress.experience)
                                habits = DrivingScore(progress.drivingPenalty)
                                lastDistanceMs = -1
                                total = progress.totalKm
                                experience = progress.experience
                                companion = progress.companion
                                selectionSubmitted = true
                                refreshDrivingScore()
                                dirty = true
                                save()
                            }
                        }, onGame = { seed -> testSeed = seed; testTab = false; inGame = true },
                        onWindow = { showSmallCompanion() }, onReload = { save(); recreate() }, vehicleSamples = vehicleSamples)

                    !initialized -> Box(Modifier.fillMaxSize().background(Color(0xFF102127)).padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center) {
                        Text(if (loadingError) "동행 기록을 불러오지 못했어요. 앱을 다시 열어주세요." else "동행 기록을 불러오고 있어요",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Color.White, fontSize = 20.sp)
                    }
                    inPip -> if (uxKnown && !restricted && !needsSelection) {
                        CompanionSprite(false, companion, DistanceGrowth.stageFor(experience),
                            "${companion.displayName} 드라이비 화면 표시", Modifier.fillMaxSize())
                    }
                    needsSelection && uxKnown && !restricted && resumed -> CompanionSetup(storageStatus) { chosen ->
                        companion = chosen
                        selectionSubmitted = true
                        dirty = true
                        save()
                    }
                    inGame && uxKnown && !restricted && resumed && !needsSelection -> RunnerScreen(companion, DistanceGrowth.stageFor(experience), testSeed) { inGame = false }
                    else -> CompanionScreen(total, experience, drivingScore, restricted || !resumed,
                        dataStatus, storageStatus, companion, speedPermissionGranted,
                        onGame = { if (uxKnown && !restricted && resumed && !needsSelection) { testSeed = null; inGame = true } },
                        reportContent = {
                            DriveReportsPanel(journal, journalStatus, companion,
                                onEnabled = { enabled -> editJournal {
                                    finishReport("paused")
                                    journal = journal.copy(enabled = enabled)
                                } },
                                onGoal = { goal -> editJournal { journal = journal.copy(goal = goal) } },
                                onRecord = { record -> editJournal {
                                    journal = journal.copy(records = journal.records.map { if (it.id == record.id)
                                        it.copy(driver = record.driver, context = record.context, reflection = record.reflection,
                                            environment = record.environment) else it })
                                } },
                                onDelete = { deleteJournal() }, onRetry = { if (canEditJournal()) persistJournal() },
                                onEnvironment = { environment -> editJournal { journal = journal.copy(environment = environment) } })
                        }) {
                        SpeedPermissionSettings(speedPermissionGranted) {
                            if (resumed && uxKnown && !restricted) speedPermission.launch(Car.PERMISSION_SPEED)
                        }
                        CompanionDisplaySettings(smallEnabled, smallCorner, overlayGranted,
                            smallNotice, ::changeSmallEnabled, { corner -> smallCorner = corner; display.corner = corner }, {
                                showSmallCompanion()
                            })
                    }
                }
            }
        }
        // The same executor orders this load after the previous Activity's final save.
        saves.execute {
            try {
                val loadedStore = PetSaveStore(filesDir, getExternalFilesDir(null))
                val loadedJournalStore = DriveJournalStore(filesDir)
                val diskJournal = loadedJournalStore.load(System.currentTimeMillis())
                val loadedJournal = if (loadedJournalStore.deletionPending) DriveJournal()
                    else if (loadedJournalStore.healthy) unsavedJournal ?: diskJournal else diskJournal
                val journalSaved = loadedJournalStore.healthy && loadedJournalStore.save(loadedJournal)
                if (journalSaved) unsavedJournal = null
                val selected = getSharedPreferences("companion", MODE_PRIVATE).getString("selected", null)
                val loaded = loadedStore.load(Companion.fromSavedName(selected))
                val loadedStatus = loadedStore.status
                if (!loadedStore.canSave) unsavedProgress = null
                val recovered = if (loadedStore.canSave) unsavedProgress else null
                val progress = recovered ?: loaded
                val selectionNeeded = recovered == null && loadedStatus == "new" && selected == null
                val saved = !loadedStore.canSave || selectionNeeded || loadedStore.save(progress)
                if (saved && recovered != null) unsavedProgress = null
                val resultStatus = if (!saved) loadedStore.status else if (recovered != null) "saved" else loadedStatus
                main.post {
                    if (isDestroyed || isFinishing) return@post
                    store = loadedStore
                    journalStore = loadedJournalStore
                    journal = loadedJournal
                    journalStatus = if (loadedJournalStore.deletionPending) "delete_failed"
                        else if (!loadedJournalStore.healthy) "corrupt" else if (!journalSaved) "save_failed" else "loaded"
                    growth = DistanceGrowth(progress.totalKm, progress.experience)
                    habits = DrivingScore(progress.drivingPenalty)
                    total = growth.totalKm
                    experience = growth.experience
                    companion = progress.companion
                    storageStatus = resultStatus
                    needsSelection = selectionNeeded
                    dirty = !saved
                    if (dirty) main.postDelayed(saveLater, 5000)
                    initialized = true
                    logGrowth("loaded")
                    if (started) connectVehicle()
                }
            } catch (error: Exception) {
                Log.e("Drivy", "Could not load companion progress", error)
                main.post { if (!isDestroyed && !isFinishing) loadingError = true }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (initialized) connectVehicle()
    }

    private fun connectVehicle() {
        finishReport("disconnected")
        vehicleSamples = emptyMap()
        inGame = false
        restricted = true
        uxKnown = false
        dataStatus = "차량 데이터를 기다리고 있어요"
        val current = ++session
        val listener = VehicleConnection.Callback { message ->
            runOnUiThread { if (current == session) receive(message) }
        }
        driving = DrivingProbe(this, listener).also { it.start() }
        connection = VehicleConnection(this, listener).also { it.start() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        stopService(Intent(this, CompanionOverlayService::class.java))
        readDisplaySettings()
        val speedGranted = checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED
        if (speedGranted && !speedPermissionGranted) connection?.retrySpeed()
        if (!speedGranted && speedPermissionGranted && initialized) habits.invalidate()
        if (!speedGranted) { recorder?.invalidateSpeed(); checkpointReport() }
        speedPermissionGranted = speedGranted
        uxKnown = false
        restricted = true
        driving?.refresh()
        if (initialized) refreshDrivingScore()
    }

    override fun onPause() {
        inGame = false
        resumed = false
        smallRequested = false
        main.removeCallbacks(scoreExpires)
        save()
        persistJournal()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        inGame = false
    }

    override fun onStop() {
        finishReport("stopped")
        persistJournal()
        vehicleSamples = emptyMap()
        started = false
        main.removeCallbacks(retryVehicleConnection)
        ++session
        uxKnown = false
        restricted = true
        connection?.stop()
        driving?.stop()
        connection = null
        driving = null
        if (initialized) {
            habits.invalidate()
            refreshDrivingScore()
        }
        save()
        if (BuildConfig.DEBUG) Log.i("Drivy", "DRIVY_ACTIVITY stopped")
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(journalLater)
        main.removeCallbacks(saveLater)
        main.removeCallbacks(scoreExpires)
        main.removeCallbacks(retryVehicleConnection)
        super.onDestroy()
    }

    private fun receive(message: String) {
        if (BuildConfig.DEBUG) Log.i("Drivy", "DRIVY_VEHICLE_RECEIVED $message")
        val event = try { JSONObject(message) } catch (_: Exception) { return }
        if (BuildConfig.DEBUG) {
            val kind = event.optString("kind")
            val key = when {
                kind.startsWith("counter") -> "거리/시간 · " + event.optString("metric")
                kind == "speed" -> "차량 속도 · AAOS"
                kind == "door" || kind == "error" -> "운전석 도어 · Pleos SDK"
                kind == "ux" || kind == "ux_error" -> "주행 화면 제한 · AAOS"
                kind.startsWith("connection") -> "SDK 연결"
                else -> null
            }
            if (key != null) {
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
                vehicleSamples = vehicleSamples + (key to "$time\n$message")
            }
        }
        when (event.optString("kind")) {
            "ux" -> {
                uxKnown = true
                restricted = event.optBoolean("requires_do", true)
                if (restricted) {
                    inGame = false
                    smallRequested = false
                    save()
                    if (inPip) finish()
                }
            }
            "ux_error" -> { inGame = false; uxKnown = false; restricted = true; smallRequested = false; save(); if (inPip) finish() }
            "connection_error" -> {
                finishReport("disconnected")
                persistJournal()
                growth.invalidate()
                habits.invalidate()
                refreshDrivingScore()
                dataStatus = "차량에 연결할 수 없어요 · 성장은 간직하고 있어요"
            }
            "speed" -> {
                if (needsSelection || !store.canSave) return
                val before = habits.penalty
                val speed = if (event.optString("status") == "available")
                    (event.opt("value") as? Number)?.toDouble() else null
                habits.observe(speed, event.optLong("elapsed_ms", -1))
                reportRecorder()?.speed(speed, event.optLong("elapsed_ms", -1), System.currentTimeMillis())
                checkpointReport()
                refreshDrivingScore()
                if (habits.penalty != before) scheduleSave()
            }
            "counter" -> if (event.optString("metric") == "distance_km") {
                if (needsSelection || !store.canSave) return
                val distance = if (event.optString("status") == "available")
                    (event.opt("value") as? Number)?.toDouble() else null
                val elapsedMs = event.optLong("elapsed_ms", -1)
                reportRecorder()?.distance(distance, elapsedMs, System.currentTimeMillis())
                checkpointReport()
                val score = habits.forInterval(lastDistanceMs, elapsedMs)
                val before = growth.experience
                val outcome = growth.observe(distance, elapsedMs, score)
                if (outcome != "out_of_order") lastDistanceMs = elapsedMs
                refreshDrivingScore()
                dataStatus = when (outcome) {
                    "grown" -> when {
                        growth.experience < before -> "운전 습관을 반영해 경험치가 줄었어요"
                        score == null -> "함께한 거리 반영 · 운전 평가는 측정 대기예요"
                        score > 75 -> "좋은 운전 습관으로 보너스 경험치를 받았어요"
                        else -> "거리와 운전 습관을 경험치에 반영했어요"
                    }
                    "baseline" -> "차량 연결 완료 · 다음 여정을 기다려요"
                    "unchanged" -> "차량 연결 완료 · 새로 이동한 거리가 없어요"
                    "counter_reset" -> "차량 거리값이 바뀌어 새로 연결했어요"
                    "gap_too_long" -> "연결이 오래 끊긴 구간은 제외했어요"
                    "implausible_jump" -> "확인하기 어려운 거리 변화는 제외했어요"
                    "out_of_order" -> dataStatus
                    else -> "차량 데이터를 확인할 수 없어요 · 성장은 간직하고 있어요"
                }
                total = growth.totalKm
                experience = growth.experience
                if (outcome == "grown") {
                    scheduleSave()
                }
                if (outcome !in listOf("unchanged", "out_of_order")) logGrowth(outcome)
            }
            "counter_error", "counter_capability" -> if (event.optString("metric") == "distance_km" &&
                event.optString("status") != "readable") {
                growth.invalidate()
                recorder?.invalidateDistance()
                checkpointReport()
                dataStatus = "차량 데이터를 확인할 수 없어요 · 성장은 간직하고 있어요"
                val status = event.optString("status")
                if (event.optString("kind") == "counter_error" && status == "timeout" && started &&
                    !main.hasCallbacks(retryVehicleConnection)) main.postDelayed(retryVehicleConnection, 5000)
                if (status == "permission_denied" || status == "unsupported") main.removeCallbacks(retryVehicleConnection)
            }
            "error" -> {
                // Door diagnostics do not determine odometer availability.
                if (BuildConfig.DEBUG) Log.w("Drivy", event.optString("value"))
            }
        }
    }

    private fun readDisplaySettings() {
        smallEnabled = display.enabled
        smallMode = display.mode
        smallCorner = display.corner
        overlayGranted = Settings.canDrawOverlays(this)
        smallNotice = display.notice
    }

    private fun canEditJournal() = initialized && resumed && uxKnown && !restricted && !needsSelection

    private fun editJournal(edit: () -> Unit) {
        if (!canEditJournal() || journalStatus in listOf("corrupt", "deleting", "delete_failed")) return
        edit()
        if (recorder?.hasSamples == false) recorder = null
        persistJournal()
    }

    private fun reportRecorder(): DriveRecorder? {
        if (!journal.enabled || journalStatus in listOf("corrupt", "deleting", "delete_failed")) return null
        val now = System.currentTimeMillis()
        // Bound each partial record even when an Activity remains visible for days.
        if (recorder?.let { now - it.startedAt >= REPORT_DAY_MS } == true) finishReport("paused")
        return recorder ?: DriveRecorder(now, journal.goal, journal.environment).also { recorder = it }
    }

    private fun checkpointReport() {
        val current = recorder ?: return
        if (!current.hasSamples) return
        val snapshot = current.snapshot("collecting")
        val previous = journal.records.find { it.id == snapshot.id }
        journal = journal.upsert(snapshot.copy(driver = previous?.driver ?: snapshot.driver,
            context = previous?.context ?: snapshot.context, reflection = previous?.reflection ?: snapshot.reflection,
            environment = previous?.environment ?: snapshot.environment), System.currentTimeMillis())
        if (!main.hasCallbacks(journalLater)) main.postDelayed(journalLater, 5000)
    }

    private fun finishReport(reason: String) {
        val current = recorder ?: return
        checkpointReport()
        val id = current.snapshot(reason).id
        journal = journal.copy(records = journal.records.map { if (it.id == id) it.copy(endReason = reason) else it })
        recorder = null
    }

    private fun persistJournal() {
        main.removeCallbacks(journalLater)
        if (!initialized || journalStatus in listOf("corrupt", "deleting", "delete_failed")) return
        journal = journal.retained(System.currentTimeMillis())
        val snapshot = journal
        val generation = ++journalGeneration
        journalStatus = "saving"
        saves.execute {
            val saved = journalStore.save(snapshot)
            unsavedJournal = if (saved) null else snapshot
            main.post {
                if (generation != journalGeneration || isDestroyed) return@post
                journalStatus = if (saved) "saved" else "save_failed"
                if (!saved && started && !main.hasCallbacks(journalLater)) main.postDelayed(journalLater, 5000)
            }
        }
    }

    private fun deleteJournal() {
        if (!canEditJournal() || journalStatus == "deleting") return
        main.removeCallbacks(journalLater)
        recorder = null
        journal = DriveJournal()
        val generation = ++journalGeneration
        journalStatus = "deleting"
        saves.execute {
            unsavedJournal = null
            val deleted = journalStore.delete()
            main.post {
                if (generation != journalGeneration || isDestroyed) return@post
                journalStatus = if (deleted) "loaded" else "delete_failed"
            }
        }
    }

    private fun changeSmallEnabled(enabled: Boolean) {
        if (enabled && smallMode == SmallWindowMode.OVERLAY && !Settings.canDrawOverlays(this)) {
            leavingForSettings = true
            try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) {
                try {
                    smallNotice = "차량 앱 설정에서 다른 앱 위에 표시 권한을 확인해 주세요."
                    overlayPermission.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    leavingForSettings = false
                    smallNotice = "차량 설정에서 다른 앱 위에 표시 권한을 확인해 주세요."
                }
            }
            return
        }
        smallEnabled = enabled
        display.enabled = enabled
        if (enabled) { smallNotice = null; display.notice = null }
        if (!enabled) stopService(Intent(this, CompanionOverlayService::class.java))
    }

    private fun showSmallCompanion(): Boolean {
        if (!initialized || leavingForSettings || isFinishing || inPip || !canShowSmallCompanion(smallEnabled,
                !needsSelection && store.canSave, uxKnown, restricted)) return false
        save()
        if (dirty) {
            smallRequested = true
            smallNotice = "동행 기록을 저장한 뒤 드라이비를 표시할게요."
            return true
        }
        return try {
            if (smallMode == SmallWindowMode.PIP) {
                if (!pipSupported) { smallNotice = "이 기기는 시스템 PiP를 지원하지 않아요."; false }
                else enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(1, 1)).build())
            } else {
                if (!Settings.canDrawOverlays(this)) { smallNotice = "다른 앱 위에 표시 권한을 확인해 주세요."; false }
                else {
                    startForegroundService(Intent(this, CompanionOverlayService::class.java))
                    // Pleos can leave a multi-window task visible after moveTaskToBack.
                    finish()
                    true
                }
            }
        } catch (error: Exception) {
            Log.w("Drivy", "Could not show small companion", error)
            smallNotice = "이 기기에서 드라이비 창을 열지 못했어요."
            false
        }
    }

    override fun onUserLeaveHint() {
        inGame = false
        showSmallCompanion()
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) inGame = false
        if (inPip && (!uxKnown || restricted)) finish()
    }

    private fun refreshDrivingScore() {
        main.removeCallbacks(scoreExpires)
        val now = SystemClock.elapsedRealtime()
        drivingScore = habits.current(now)
        if (resumed && drivingScore != null) habits.sampleExpiresAtMs?.let {
            main.postDelayed(scoreExpires, (it - now).coerceAtLeast(1))
        }
    }

    private fun scheduleSave() {
        dirty = true
        if (!main.hasCallbacks(saveLater)) main.postDelayed(saveLater, 5000)
    }

    private fun save() {
        main.removeCallbacks(saveLater)
        if (!dirty || (needsSelection && !selectionSubmitted)) return
        val progress = PetProgress(growth.totalKm, growth.experience, companion, habits.penalty)
        val committedSelection = !needsSelection
        if (queuedProgress == progress) return
        queuedProgress = progress
        val generation = ++saveGeneration
        saves.execute {
            val saved = store.save(progress)
            val status = store.status
            if (saved) unsavedProgress = null else if (store.canSave && committedSelection) unsavedProgress = progress
            main.post {
                if (generation != saveGeneration || queuedProgress == null) return@post
                queuedProgress = null
                if (saved && progress == PetProgress(growth.totalKm, growth.experience, companion, habits.penalty)) {
                    dirty = false
                    needsSelection = false
                    if (!isDestroyed) logGrowth("saved")
                } else if (!isDestroyed && store.canSave && !main.hasCallbacks(saveLater)) main.postDelayed(saveLater, 5000)
                if (!isDestroyed) {
                    storageStatus = status
                    if (smallRequested) {
                        if (!saved) {
                            smallRequested = false
                            smallNotice = "진행 기록을 저장하지 못해 드라이비를 표시하지 않았어요."
                        } else if (resumed && uxKnown && !restricted) {
                            if (dirty) save()
                            else {
                                smallRequested = false
                                smallNotice = null
                                showSmallCompanion()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun logGrowth(reason: String) {
        if (BuildConfig.DEBUG) Log.i("Drivy", "DRIVY_GROWTH $reason total_km=${growth.totalKm} stage=${growth.stage} storage=${store.status} xp=${growth.experience} score=$drivingScore companion=${companion.name} penalty=${habits.penalty}")
    }

    private companion object Persistence {
        private val saves = java.util.concurrent.Executors.newSingleThreadExecutor()
        // Accessed only by saves; retains the latest failed snapshot across Activity instances.
        private var unsavedProgress: PetProgress? = null
        private var unsavedJournal: DriveJournal? = null
    }
}
