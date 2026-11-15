package io.github.silleruss.drivy

import android.app.*
import android.annotation.SuppressLint
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import io.github.silleruss.drivy.vehicle.DrivingProbe
import io.github.silleruss.drivy.vehicle.VehicleConnection
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** A user-enabled parked desktop pet, with its own live Car UX subscription. */
class CompanionOverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var display: CompanionDisplay
    private var probe: DrivingProbe? = null
    private var pet: PetView? = null
    private var manager: WindowManager? = null
    private var layout: WindowManager.LayoutParams? = null
    private var safeArea = Rect()
    private var stopping = false
    private val permissionChanged = android.app.AppOpsManager.OnOpChangedListener { _, _ ->
        main.post { if (!stopping && !Settings.canDrawOverlays(this)) fail("표시 권한이 해제되어 드라이비를 숨겼어요.") }
    }
    private var progress: PetProgress? = null
    private val timeout = Runnable { fail("차량 상태를 확인하지 못해 드라이비를 숨겼어요.") }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { stopSelf() }
    }

    override fun onCreate() {
        super.onCreate()
        display = CompanionDisplay(this)
        getSystemService(android.app.AppOpsManager::class.java).startWatchingMode(
            android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionChanged)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "hide") { stopSelf(); return START_NOT_STICKY }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("companion", "드라이비 화면 표시", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val hide = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("hide"), PendingIntent.FLAG_IMMUTABLE)
            startForeground(1, Notification.Builder(this, "companion").setSmallIcon(R.drawable.ic_drivy)
                .setContentTitle("드라이비 화면 표시").setContentText("주차 중 동행 · 누르면 앱으로 돌아가요")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "숨기기", hide).build()).build())
            if (!display.enabled || display.mode != SmallWindowMode.OVERLAY || !Settings.canDrawOverlays(this)) {
                fail("드라이비 화면 표시 설정과 다른 앱 위에 표시 권한을 확인해 주세요.")
                return START_NOT_STICKY
            }
            if (probe != null) return START_NOT_STICKY
            val store = PetSaveStore(filesDir, getExternalFilesDir(null))
            val loaded = store.load()
            if (!store.canSave || store.status == "new") { stopSelf(); return START_NOT_STICKY }
            progress = loaded
            main.postDelayed(timeout, 10_000)
            probe = DrivingProbe(this, VehicleConnection.Callback { message ->
                if (stopping) return@Callback
                try {
                    val event = JSONObject(message)
                    when (event.optString("kind")) {
                        "ux" -> {
                            main.removeCallbacks(timeout)
                            if (event.optBoolean("requires_do", true)) fail("주행 제한으로 드라이비를 숨겼어요.")
                            else showPet()
                        }
                        "ux_error" -> fail("차량 상태를 확인하지 못해 드라이비를 숨겼어요.")
                    }
                } catch (error: Exception) {
                    Log.w("Drivy", "Could not create companion window", error)
                    fail("드라이비를 표시하지 못했어요. 앱에서 다시 시도해 주세요.")
                }
            }).also { it.start() }
        } catch (error: Exception) {
            Log.w("Drivy", "Could not start companion window", error)
            fail("이 기기에서 드라이비 창을 시작하지 못했어요.")
        }
        return START_NOT_STICKY
    }

    @SuppressLint("RtlHardcoded") // Positions refer to physical screen corners in every locale.
    private fun showPet() {
        if (BuildConfig.DEBUG) Log.i("Drivy", "Small companion show: stopping=$stopping existing=${pet != null} progress=${progress != null}")
        if (stopping || pet != null) return
        if (!display.enabled) { fail("드라이비 화면 표시 설정이 꺼져 있어요."); return }
        if (!Settings.canDrawOverlays(this)) { fail("다른 앱 위에 표시 권한을 확인해 주세요."); return }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            fail("잠금 화면에서는 드라이비를 숨겨요."); return
        }
        val base = getSystemService(WindowManager::class.java)
        val windowContext = if (Build.VERSION.SDK_INT >= 30) createDisplayContext(base.defaultDisplay)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else this
        val wm = windowContext.getSystemService(WindowManager::class.java)
        manager = wm
        val size = (160 * windowContext.resources.displayMetrics.density).roundToInt()
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "DRIVY Companion ${this@CompanionOverlayService.packageName}"
        }
        layout = params
        updateSafeArea()
        val (x, y) = display.position()
        params.x = safeArea.left + ((safeArea.width() - size).coerceAtLeast(0) * x).roundToInt()
        params.y = safeArea.top + ((safeArea.height() - size).coerceAtLeast(0) * y).roundToInt()
        val saved = progress ?: return
        val view = PetView(windowContext, saved.companion, DistanceGrowth.stageFor(saved.experience))
        pet = view
        wm.addView(view, params)
        if (BuildConfig.DEBUG) Log.i("Drivy", "Small companion window added: ${params.title}")
        display.notice = null
    }

    private fun updateSafeArea() {
        val wm = manager ?: return
        safeArea = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Rect(inset.left, inset.top, metrics.bounds.width() - inset.right, metrics.bounds.height() - inset.bottom)
        } else Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    private fun move(x: Int, y: Int, persist: Boolean) {
        val params = layout ?: return
        params.x = x.coerceIn(safeArea.left, maxOf(safeArea.left, safeArea.right - params.width))
        params.y = y.coerceIn(safeArea.top, maxOf(safeArea.top, safeArea.bottom - params.height))
        try { pet?.let { manager?.updateViewLayout(it, params) } }
        catch (_: Exception) { fail("표시 권한이나 화면이 바뀌어 드라이비를 숨겼어요."); return }
        if (persist) display.savePosition(
            (params.x - safeArea.left).toFloat() / (safeArea.width() - params.width).coerceAtLeast(1),
            (params.y - safeArea.top).toFloat() / (safeArea.height() - params.height).coerceAtLeast(1))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSafeArea()
        val (x, y) = display.position()
        layout?.let { move(safeArea.left + (x * (safeArea.width() - it.width)).roundToInt(),
            safeArea.top + (y * (safeArea.height() - it.height)).roundToInt(), false) }
    }

    private fun fail(message: String) {
        stopping = true
        Log.i("Drivy", "Small companion hidden: $message")
        display.notice = message
        pet?.visibility = View.GONE
        stopSelf()
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) Log.i("Drivy", "Small companion service destroyed")
        stopping = true
        main.removeCallbacks(timeout)
        probe?.stop()
        pet?.let {
            try { manager?.removeViewImmediate(it) } catch (_: IllegalArgumentException) { }
            it.bitmap.recycle()
        }
        pet = null
        getSystemService(android.app.AppOpsManager::class.java).stopWatchingMode(permissionChanged)
        unregisterReceiver(screenOff)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private inner class PetView(context: Context, val companion: Companion, val stage: Int) : View(context) {
        val bitmap = checkNotNull(BitmapFactory.decodeResource(resources, companion.forms[stage].sprite))
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val source = Rect()
        private val destination = RectF()
        private val started = SystemClock.uptimeMillis()
        private val animate = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragged = false
        init { contentDescription = "${companion.displayName} 드라이비 화면 표시 · 누르면 앱으로 돌아가기"; isClickable = true }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val phase = if (animate) ((SystemClock.uptimeMillis() - started) % 4800) / 4800f else 0f
            val pose = companionPose(companion, phase, false)
            val cell = bitmap.width / 3
            val scale = .8f + stage * .1f
            canvas.save()
            canvas.translate(width / 2f, height * .9f + pose.lift * resources.displayMetrics.density)
            canvas.rotate(pose.tilt)
            canvas.scale(scale * (1 - pose.stretch / 2), scale * (1 + pose.stretch))
            source.set(stage * cell, pose.frame * cell, (stage + 1) * cell, (pose.frame + 1) * cell)
            destination.set(-width * .45f, -height * .85f, width * .45f, height * .05f)
            canvas.drawBitmap(bitmap, source, destination, paint)
            canvas.restore()
            if (animate) postInvalidateOnAnimation()
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            val params = layout ?: return false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragged = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > ViewConfiguration.get(context).scaledTouchSlop) dragged = true
                    if (dragged) move(startX + dx.roundToInt(), startY + dy.roundToInt(), false)
                }
                android.view.MotionEvent.ACTION_UP -> if (dragged) move(params.x, params.y, true) else performClick()
                android.view.MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            startActivity(Intent(this@CompanionOverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
    }
}
