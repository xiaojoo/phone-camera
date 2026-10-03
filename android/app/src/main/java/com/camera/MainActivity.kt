package com.camera

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.util.Range
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {

    companion object {
        private const val DEFAULT_PORT = 8080
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535

        private const val JPEG_QUALITY = 80

        /*
         * 帧率运行时可调，这里只是默认值和边界。上限 30 是这台机器上
         * CameraX 分析流能稳定拿到的最高档。
         */
        private const val DEFAULT_FPS = 30
        private const val MIN_FPS = 5
        private const val MAX_FPS = 30

        private const val POLL_INTERVAL_MS = 1000L

        private const val PREFS_UI = "ui"
        private const val KEY_LANGUAGE = "lang"
        private const val KEY_CAMERA = "camera"
        private const val KEY_FPS = "fps"
        private const val KEY_FOCUS = "focus"
        private const val KEY_ROTATION = "rotation"
        private const val KEY_ZOOM = "zoom"


        /*
         * 对焦默认 auto：对一次就停住。
         * 不设 AF 模式时相机走默认的连续对焦，画面一动就重新拉风箱，
         * 电脑端实测只有 27% 的帧是清晰的，采集根本不能用。
         */
        private const val DEFAULT_FOCUS = "auto"
        private val FOCUS_MODES = setOf("auto", "continuous", "locked")

        /* AF_TRIGGER 是电平语义，START 之后要回 IDLE，留一点对焦时间 */
        private const val AF_SETTLE_MS = 700L

        /*
         * 输出画面方向，顺时针角度，叠在缓冲区自己的方向之上。
         * 0° ＝ 和手机预览同向（人眼看过去是正的），90/180/270 从那里再转。
         *
         * 它只由界面/接口决定：跟手机怎么拿、以及相机这次给的是横缓冲区还是
         * 竖缓冲区都无关，后者由 ImageInfo.rotationDegrees 抵掉，见 processFrame。
         */
        private const val DEFAULT_ROTATION = 0
        private val ROTATIONS = listOf(0, 90, 180, 270)

        /* 变焦默认 1 倍＝不裁。存的是倍率而不是滑块位置，换镜头才有意义 */
        private const val DEFAULT_ZOOM = 1f

        private const val LENS_BACK = "back"
        private const val LENS_FRONT = "front"
    }

    private lateinit var previewView: PreviewView

    private lateinit var previewBox: View
    private lateinit var previewOverlay: View
    private lateinit var rotateButton: TextView
    private lateinit var zoomDial: ZoomDialView

    private var rotationPopup: PopupWindow? = null

    private lateinit var topBar: View
    private lateinit var controls: View
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var wifiSegment: TextView
    private lateinit var usbSegment: TextView
    private lateinit var backSegment: TextView
    private lateinit var frontSegment: TextView
    private lateinit var lightButton: TextView
    private lateinit var wifiPanel: View
    private lateinit var usbPanel: View
    private lateinit var streamText: TextView
    private lateinit var clientsText: TextView
    private lateinit var portField: EditText
    private lateinit var cableText: TextView
    private lateinit var adbCommand: TextView
    private lateinit var hint: TextView
    private lateinit var actionButton: TextView
    private lateinit var langButton: TextView

    private lateinit var cameraExecutor: ExecutorService

    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null

    private var preview: Preview? = null

    private lateinit var floating: FloatingPreview

    /*
     * 相机绑在这个常驻 RESUMED 的生命周期上而不是 Activity 上：
     * Activity 一 stop，绑在它上面的 CameraX 就解绑，悬浮窗会定住。
     * 退到后台还能用相机，靠的是悬浮窗本身算「可见窗口」。
     */
    private val cameraOwner = AlwaysResumedOwner()

    private var mjpegServer: MjpegServer? = null

    private var port = DEFAULT_PORT

    private var lens = LENS_BACK

    private var camera: Camera? = null

    private var lightOn = false

    @Volatile
    private var targetFps = DEFAULT_FPS

    @Volatile
    private var focusMode = DEFAULT_FOCUS

    /* 采集线程每帧要读一次这个值来决定像素往哪写，所以 @Volatile */
    @Volatile
    private var rotationDegrees = DEFAULT_ROTATION

    /*
     * 当前倍率，只在主线程读写。已经按这颗镜头的真实区间钳制过，
     * 所以界面显示、/status 回读、偏好里存的是同一个数。
     */
    private var zoomRatio = DEFAULT_ZOOM

    /*
     * 这颗镜头能给的倍率区间。
     *
     * 不用 zoomState 的 min/max：实测换到前置之后它只回调一次，
     * 报的是 min=max=1.0 而同一颗镜头的 characteristics 写的是 [1,4]
     * （dumpsys media.camera → android.control.zoomRatioRange）。
     * 拿那个假区间去钳制，前置的变焦就被锁死在 1 倍。
     */
    private var zoomMin = 1f
    private var zoomMax = 1f

    /*
     * 阈值往下降 3ms：1000/30 整除成 33，而 30fps 的帧周期是 33.3ms，
     * 卡在边界上的帧会被自己的节流判掉，实测因此少掉约四分之一。
     */
    private val frameIntervalMs: Long
        get() = (1000L / targetFps - 3L).coerceAtLeast(1L)

    private var previewSideMargin = 0

    private var previewBottomMargin = 0

    private var streaming = false

    private var inPictureInPicture = false

    private var leaveDialog: AlertDialog? = null

    private val lastEncodeTime = AtomicLong(0L)

    private var pushed = 0
    private var windowStart = 0L

    private val statusPoller = object : Runnable {
        override fun run() {
            renderConnectionInfo()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderUsbState()
        }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                setStatus(R.string.status_error, R.color.danger)
                hint.setText(R.string.permission_denied)
            }
        }

    override fun attachBaseContext(newBase: Context) {
        val tag = newBase
            .getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "")

        val base = if (tag.isNullOrEmpty()) newBase else newBase.withLanguage(tag)

        super.attachBaseContext(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lens = getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getString(KEY_CAMERA, LENS_BACK) ?: LENS_BACK

        targetFps = getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, DEFAULT_FPS)

        focusMode = getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getString(KEY_FOCUS, DEFAULT_FOCUS)
            ?.takeIf { it in FOCUS_MODES } ?: DEFAULT_FOCUS

        rotationDegrees = getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getInt(KEY_ROTATION, DEFAULT_ROTATION)
            ?.takeIf { it in ROTATIONS } ?: DEFAULT_ROTATION

        zoomRatio = getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getFloat(KEY_ZOOM, DEFAULT_ZOOM)

        setContentView(R.layout.activity_main)

        initViews()
        wireControls()

        floating = FloatingPreview(this) { openFromFloat() }

        cameraExecutor = Executors.newSingleThreadExecutor()

        startServer()
        checkCameraPermission()
        renderUsbState()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        streaming = false

        if (::floating.isInitialized) floating.hide()

        mainHandler.removeCallbacks(statusPoller)

        runCatching { leaveDialog?.dismiss() }
        runCatching { rotationPopup?.dismiss() }
        runCatching { unregisterReceiver(powerReceiver) }
        runCatching { cameraProvider?.unbindAll() }
        runCatching { mjpegServer?.stop() }
        runCatching { cameraExecutor.shutdownNow() }

        super.onDestroy()
    }

    // ---------------- views ----------------

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        previewBox = findViewById(R.id.previewBox)
        previewOverlay = findViewById(R.id.previewOverlay)
        rotateButton = findViewById(R.id.rotateButton)
        zoomDial = findViewById(R.id.zoomDial)

        /* 边距现在挂在 previewBox 上：小窗要清零，得动这一层 */
        (previewBox.layoutParams as LinearLayout.LayoutParams).let {
            previewSideMargin = it.marginStart
            previewBottomMargin = it.bottomMargin
        }
        topBar = findViewById(R.id.topBar)
        controls = findViewById(R.id.controls)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        wifiSegment = findViewById(R.id.wifiSegment)
        usbSegment = findViewById(R.id.usbSegment)
        wifiPanel = findViewById(R.id.wifiPanel)
        usbPanel = findViewById(R.id.usbPanel)
        streamText = findViewById(R.id.streamText)
        clientsText = findViewById(R.id.clientsText)
        portField = findViewById(R.id.portField)
        cableText = findViewById(R.id.cableText)
        adbCommand = findViewById(R.id.adbCommand)
        hint = findViewById(R.id.wifiHint)
        actionButton = findViewById(R.id.actionButton)
        langButton = findViewById(R.id.langButton)
        backSegment = findViewById(R.id.backSegment)
        frontSegment = findViewById(R.id.frontSegment)
        lightButton = findViewById(R.id.lightButton)

        portField.setText(port.toString())
        renderLens()
        renderRotation()
    }

    private fun renderLens() {
        val selected = ContextCompat.getDrawable(this, R.drawable.bg_segment_selected)
        val active = ContextCompat.getColor(this, R.color.text_primary)
        val inactive = ContextCompat.getColor(this, R.color.text_secondary)

        backSegment.background = if (lens == LENS_BACK) selected else null
        frontSegment.background = if (lens == LENS_FRONT) selected else null

        backSegment.setTextColor(if (lens == LENS_BACK) active else inactive)
        frontSegment.setTextColor(if (lens == LENS_FRONT) active else inactive)

        renderLight()
    }

    /*
     * 方向写在画面右上角那颗胶囊上。带一个下箭头是必要的：
     * 光写「0°」看着像读数，不像能点的东西。
     */
    private fun renderRotation() {
        rotateButton.text = rotationLabel(rotationDegrees)
    }

    private fun rotationLabel(degrees: Int): String =
        getString(
            when (degrees) {
                90 -> R.string.rotation_90
                180 -> R.string.rotation_180
                270 -> R.string.rotation_270
                else -> R.string.rotation_0
            }
        ) + " ▾"

    /*
     * 下拉框每次打开都重新装一遍：当前角度只有一份（rotationDegrees），
     * 弹层里高亮哪一行由它决定，省掉「弹层选中态」和「外面当前值」两份数同步。
     */
    private fun showRotationPopup() {
        if (rotationPopup?.isShowing == true) return

        val rowIds = listOf(R.id.rotRow0, R.id.rotRow90, R.id.rotRow180, R.id.rotRow270)

        val content = LayoutInflater.from(this)
            .inflate(R.layout.popup_rotation, null)

        val minWidth = (92 * resources.displayMetrics.density).toInt()

        val popup = PopupWindow(
            content,
            maxOf(rotateButton.width, minWidth),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        /* PopupWindow 的 setter 叫 setBackgroundDrawable，没有对应的属性语法 */
        popup.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.bg_card))
        popup.elevation = 12f * resources.displayMetrics.density

        rowIds.forEachIndexed { index, id ->
            val row = content.findViewById<TextView>(id)
            val degrees = ROTATIONS[index]

            row.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (degrees == rotationDegrees) R.color.accent else R.color.text_secondary
                )
            )

            row.setOnClickListener {
                popup.dismiss()
                setRotationDegrees(degrees)
            }
        }

        /* 右边缘和胶囊对齐：弹层比胶囊宽，所以往左挪 */
        popup.showAsDropDown(
            rotateButton,
            rotateButton.width - popup.width,
            (4 * resources.displayMetrics.density).toInt()
        )

        rotationPopup = popup
    }

    /*
     * 倍率写到画面上的拨盘里。
     * 手指正按着拨盘的时候不回写，否则会把正在拖的位置抢走。
     */
    private fun renderZoom() {
        zoomDial.setRange(zoomMin, zoomMax)

        if (!zoomDial.isDragging) {
            val span = zoomMax - zoomMin

            zoomDial.syncProgress(if (span <= 0f) 0f else (zoomRatio - zoomMin) / span)
        }
    }

    /*
     * 前置没有闪光灯，只能把这块屏幕拉到最亮当补光板。
     */
    private fun usesTorch(): Boolean =
        lens == LENS_BACK && camera?.cameraInfo?.hasFlashUnit() == true

    private fun renderLight() {
        val torch = usesTorch()

        lightButton.text = getString(
            when {
                lightOn && torch -> R.string.light_torch_on
                lightOn -> R.string.light_screen_on
                torch -> R.string.light_torch
                else -> R.string.light_screen
            }
        )

        lightButton.setTextColor(
            ContextCompat.getColor(
                this,
                if (lightOn) R.color.accent else R.color.text_secondary
            )
        )
    }

    private fun applyLight() {
        val torch = usesTorch()

        runCatching { camera?.cameraControl?.enableTorch(lightOn && torch) }

        window.attributes = window.attributes.apply {
            screenBrightness = if (lightOn && !torch) {
                1f
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    private fun setLight(on: Boolean) {
        if (lightOn == on) return

        lightOn = on

        applyLight()
        renderLight()
        mjpegServer?.light = on
    }

    private fun setFps(value: Int) {
        val next = value.coerceIn(MIN_FPS, MAX_FPS)

        if (next == targetFps) return

        targetFps = next

        getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FPS, next)
            .apply()

        mjpegServer?.fpsTarget = next
        applyFrameRate()
    }

    /*
     * CameraX 的 ResolutionSelector 没有帧率入口，只能下沉到 Camera2 直接要
     * CONTROL_AE_TARGET_FPS_RANGE。不给的话相机在 960x720 只跑 20 fps。
     *
     * 这里钉死 [N,N]，帧率优先。这台 K40 实测是个二选一：下界不等于 N 时
     * HAL 直接选 20 fps 的工作点（[5,30] 和 [25,30] 都是 19.9 fps，
     * 开 LED 也救不回来），只有 [30,30] 才给 30 fps 到达率。
     *
     * 代价是 AE 不能靠拉长曝光换画质，暗光下更暗更噪
     * （实测 JPEG 从 30 KB 缩到 20.6 KB）。要画质优先就把下界改回 MIN_FPS。
     */
    private fun applyFrameRate() = applyCameraOptions()

    /*
     * CameraX 没有对焦模式入口，同样下沉 Camera2。
     * 不设的话相机走默认连续对焦，画面一动就重新拉风箱。
     *
     * auto       触发一次后停住，再调一次 /focus?mode=auto 会重新对
     * continuous 相机自己一直找
     * locked     AF_MODE_OFF，镜头位置冻在原地
     *
     * AF_TRIGGER 是电平语义：START 之后必须回 IDLE，
     * 否则每一帧都在重新触发，auto 就退化成 continuous 了。
     */
    private fun applyFocus() {
        if (focusMode != "auto") {
            applyCameraOptions()

            return
        }

        if (!applyCameraOptions(CaptureRequest.CONTROL_AF_TRIGGER_START)) return

        mainHandler.postDelayed(
            { applyCameraOptions(CaptureRequest.CONTROL_AF_TRIGGER_IDLE) },
            AF_SETTLE_MS
        )
    }

    /*
     * AE 区间和 AF 模式必须打包成一次请求下发。
     * 分成两次 setCaptureRequestOptions 时，后一次会覆盖前一次的整套选项：
     * 实测先下发 AE、再单独下发 AF，AE 就退回 [5,30] 的 20 fps 工作点。
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCameraOptions(
        afTrigger: Int = CaptureRequest.CONTROL_AF_TRIGGER_IDLE
    ): Boolean {
        val afMode = when (focusMode) {
            "auto" -> CaptureRequest.CONTROL_AF_MODE_AUTO
            "locked" -> CaptureRequest.CONTROL_AF_MODE_OFF
            else -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }

        val control = camera?.let { Camera2CameraControl.from(it.cameraControl) }
            ?: return false

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(targetFps, targetFps)
            )
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afMode)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)
            .build()

        return runCatching { control.setCaptureRequestOptions(options) }.isSuccess
    }

    /*
     * 同一个 mode 也要重新执行：/focus?mode=auto 的语义是"重新对一次"，
     * 所以这里不能像 setFps 那样提前返回。
     */
    private fun setFocus(mode: String) {
        if (mode !in FOCUS_MODES) return

        focusMode = mode

        getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOCUS, mode)
            .apply()

        mjpegServer?.focusMode = mode

        applyFocus()
    }

    private fun setRotationDegrees(degrees: Int) {
        if (degrees !in ROTATIONS) return
        if (degrees == rotationDegrees) return

        rotationDegrees = degrees

        getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ROTATION, degrees)
            .apply()

        mjpegServer?.rotation = degrees

        /*
         * 下一帧的像素就按新角度排，不用重新绑定，也不用清缓冲：
         * 观看者拿到的是完整的一帧，尺寸从 960x720 变成 720x960 而已。
         */
        renderRotation()
    }

    /*
     * 变焦走 CameraX 的 CameraControl.setZoomRatio：它是逐帧的请求参数，
     * 既不重建会话，也不和 AE/AF 那一套 setCaptureRequestOptions 抢下发
     * （那两个必须打包成一次，见 applyCameraOptions）。
     *
     * 不用 setLinearZoom：实测这颗相机 linearZoom 的下半程全落在 1 倍上
     * （滑块到 52% 才 1.17 倍），因为 CameraX 把 0.5 定义成 1 倍的分界，
     * 而这台机器最小就是 1 倍。这里让滑块直接线性对应真实倍率区间。
     */
    private fun setZoom(ratio: Float, persist: Boolean = true) {
        if (!ratio.isFinite()) return

        zoomRatio = ratio.coerceIn(zoomMin, zoomMax)

        if (persist) {
            getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_ZOOM, zoomRatio)
                .apply()
        }

        applyZoom()
    }

    /* 拨盘给的是 0..1 的位置，按真实区间线性换成倍率；落库等松手，见 dial 监听 */
    private fun setZoomByPosition(position: Float) {
        setZoom(zoomMin + (zoomMax - zoomMin) * position.coerceIn(0f, 1f), persist = false)
    }

    private fun applyZoom() {
        val cam = camera ?: return

        runCatching { cam.cameraControl.setZoomRatio(zoomRatio) }

        renderZoom()
        mjpegServer?.let {
            it.zoomRatio = zoomRatio
            it.zoomMin = zoomMin
            it.zoomMax = zoomMax
        }
    }

    /*
     * 区间直接读 Camera2 的 characteristics：bind 完就是确定值。
     * 实测后置 [1,10]、前置 [1,4]，和 dumpsys media.camera 里
     * android.control.zoomRatioRange 一一对上。
     *
     * 老路子是问 zoomState，但它换镜头后只回调一次、报 min=max=1.0
     * 且之后不再更新，拿它钳制会把前置的变焦锁死在 1 倍。
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readZoomBounds() {
        val cam = camera ?: return

        val info = Camera2CameraInfo.from(cam.cameraInfo)

        val range =
            info.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                ?: info.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
                )?.let { Range(1f, it) }
                ?: Range(1f, 1f)

        zoomMin = range.lower
        zoomMax = range.upper.coerceAtLeast(range.lower)

        zoomRatio = zoomRatio.coerceIn(zoomMin, zoomMax)
    }

    /*
     * 一秒一个窗口把实测帧率报给服务端：电脑端读 /status 就能核对
     * 「设的帧率」和「真跑出来的帧率」是不是一个数。
     */
    private fun countPushed() {
        val now = SystemClock.elapsedRealtime()

        if (windowStart == 0L) windowStart = now

        pushed += 1

        val span = now - windowStart

        if (span < 1000L) return

        mjpegServer?.actualFps = pushed * 1000.0 / span

        pushed = 0
        windowStart = now
    }

    private fun wireControls() {
        wifiSegment.setOnClickListener { showUsbMode(false) }
        usbSegment.setOnClickListener { showUsbMode(true) }

        backSegment.setOnClickListener { selectLens(LENS_BACK) }
        frontSegment.setOnClickListener { selectLens(LENS_FRONT) }

        lightButton.setOnClickListener { setLight(!lightOn) }

        rotateButton.setOnClickListener { showRotationPopup() }

        zoomDial.listener = object : ZoomDialView.Listener {

            override fun onProgress(progress: Float) {
                setZoomByPosition(progress)
            }

            /* 松手才落库：拖一次要回调上百次，每写一次偏好没意义 */
            override fun onCommit() {
                setZoom(zoomRatio)
            }
        }

        onBackPressedDispatcher.addCallback(this) { showLeaveChoice() }

        actionButton.setOnClickListener {
            if (mjpegServer == null) startServer() else stopServer()
        }

        findViewById<TextView>(R.id.copyUrlButton).setOnClickListener {
            copyToClipboard("stream", streamText.text.toString())
        }

        findViewById<TextView>(R.id.copyCommandButton).setOnClickListener {
            copyToClipboard("adb command", adbCommand.text.toString())
        }

        langButton.setOnClickListener {
            val current = resources.configuration.locales[0].language
            val next = if (current == "zh") "en" else "zh"

            getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, next)
                .apply()

            recreate()
        }

        portField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT
            ) {
                applyPort()
                portField.clearFocus()
                true
            } else {
                false
            }
        }

        portField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyPort()
        }
    }

    private fun showUsbMode(usb: Boolean) {
        val selected = ContextCompat.getDrawable(this, R.drawable.bg_segment_selected)

        wifiPanel.visibility = if (usb) View.GONE else View.VISIBLE
        usbPanel.visibility = if (usb) View.VISIBLE else View.GONE

        wifiSegment.background = if (usb) null else selected
        usbSegment.background = if (usb) selected else null

        val active = ContextCompat.getColor(this, R.color.text_primary)
        val inactive = ContextCompat.getColor(this, R.color.text_secondary)

        wifiSegment.setTextColor(if (usb) inactive else active)
        usbSegment.setTextColor(if (usb) active else inactive)
    }

    private fun selectLens(next: String) {
        if (next == lens) return

        lens = next

        getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CAMERA, lens)
            .apply()

        renderLens()
        mjpegServer?.lens = lens

        cameraProvider?.let { bindCamera(it) }
    }

    // ---------------- 小窗 / 退出 ----------------

    private fun showLeaveChoice() {
        if (mjpegServer == null) {
            finishAndRemoveTask()
            return
        }

        if (leaveDialog?.isShowing == true) return

        leaveDialog = AlertDialog.Builder(this)
            .setTitle(R.string.bg_title)
            .setMessage(R.string.bg_message)
            .setNegativeButton(R.string.bg_exit) { _, _ ->
                stopServer()
                finishAndRemoveTask()
            }
            .setPositiveButton(R.string.bg_keep) { _, _ -> leaveToSmallWindow(prompt = true) }
            .show()
    }

    /*
     * 有悬浮窗权限就用自己的小窗（右上角、圆角自绘），没有就退回系统 PiP：
     * PiP 是系统窗口，不需要授权，是唯一的降级路径。
     *
     * prompt=false 是按 Home 的路径：这时应用正要退到后台，弹授权框等于弹了个
     * 看不见的框，所以直接走系统小窗，不打断。
     */
    private fun leaveToSmallWindow(prompt: Boolean) {
        /* 弹层是独立窗口，收进小窗之前先关掉，免得回来时它还挂着 */
        runCatching { rotationPopup?.dismiss() }

        if (Settings.canDrawOverlays(this)) {
            enterFloat()
        } else if (prompt) {
            askOverlayPermission()
        } else {
            enterPip()
        }
    }

    private fun enterFloat() {
        preview?.setSurfaceProvider(floating.previewView.surfaceProvider)

        floating.show()
        moveTaskToBack(true)
    }

    private fun exitFloat() {
        if (!floating.isShowing) return

        floating.hide()
        preview?.setSurfaceProvider(previewView.surfaceProvider)
    }

    private fun openFromFloat() {
        exitFloat()

        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        )
    }

    private fun askOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle(R.string.float_perm_title)
            .setMessage(R.string.float_perm_message)
            .setNegativeButton(R.string.float_perm_pip) { _, _ -> enterPip() }
            .setPositiveButton(R.string.float_perm_open) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            .show()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            moveTaskToBack(true)
            return
        }

        val loc = IntArray(2)
        previewView.getLocationInWindow(loc)

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(3, 4))
            .setSourceRectHint(
                Rect(loc[0], loc[1], loc[0] + previewView.width, loc[1] + previewView.height)
            )
            .build()

        runCatching { enterPictureInPictureMode(params) }
    }

    /*
     * 小窗里只留画面：fitCenter 会把 3:4 的画面塞进竖窗，上下各空出一条黑边，
     * 预览自己的 20dp 外边距再补一圈，所以进小窗换成裁剪填充并把边距清零。
     */
    private fun applyPipLayout(pip: Boolean) {
        previewView.scaleType = if (pip) {
            PreviewView.ScaleType.FILL_CENTER
        } else {
            PreviewView.ScaleType.FIT_CENTER
        }

        (previewBox.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.setMargins(
                if (pip) 0 else previewSideMargin,
                0,
                if (pip) 0 else previewSideMargin,
                if (pip) 0 else previewBottomMargin
            )
            previewBox.layoutParams = it
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        inPictureInPicture = isInPictureInPictureMode

        val visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE

        topBar.visibility = visibility
        controls.visibility = visibility

        /* 拨盘和方向胶囊浮在画面上，小窗里也得跟着收掉 */
        previewOverlay.visibility = visibility

        applyPipLayout(isInPictureInPictureMode)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        if (mjpegServer == null || inPictureInPicture) return
        if (leaveDialog?.isShowing == true) return

        leaveToSmallWindow(prompt = false)
    }

    override fun onResume() {
        super.onResume()

        /* 从桌面图标或最近任务回来时，悬浮窗不会自己消失，这里收掉 */
        exitFloat()
    }

    // ---------------- server ----------------

    private fun applyKeepScreenOn() {
        val flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        if (mjpegServer != null) window.addFlags(flags) else window.clearFlags(flags)
    }

    private fun startServer() {
        if (mjpegServer != null) return

        mjpegServer = MjpegServer(port).apply {
            lens = this@MainActivity.lens
            light = this@MainActivity.lightOn
            fpsTarget = this@MainActivity.targetFps
            focusMode = this@MainActivity.focusMode
            rotation = this@MainActivity.rotationDegrees
            zoomRatio = this@MainActivity.zoomRatio
            zoomMin = this@MainActivity.zoomMin
            zoomMax = this@MainActivity.zoomMax

            onLens = { next ->
                mainHandler.post { selectLens(next) }
            }

            onLight = { on ->
                mainHandler.post { setLight(on) }
            }

            onFps = { value ->
                mainHandler.post { setFps(value) }
            }

            onFocus = { mode ->
                mainHandler.post { setFocus(mode) }
            }

            onRotation = { degrees ->
                mainHandler.post { setRotationDegrees(degrees) }
            }

            onZoom = { ratio ->
                mainHandler.post { setZoom(ratio) }
            }

            start()
        }

        mainHandler.postDelayed(statusPoller, POLL_INTERVAL_MS)

        applyKeepScreenOn()
        renderConnectionInfo()
        updateActionButton()
    }

    private fun stopServer() {
        val server = mjpegServer ?: return

        mainHandler.removeCallbacks(statusPoller)
        server.stop()
        mjpegServer = null

        applyKeepScreenOn()
        renderConnectionInfo()
        updateActionButton()
    }

    private fun restartServer() {
        stopServer()
        startServer()
    }

    private fun applyPort() {
        val value = portField.text.toString().toIntOrNull()

        if (value == null || value !in MIN_PORT..MAX_PORT) {
            portField.error = getString(R.string.port_invalid)
            return
        }

        portField.error = null

        if (value == port) return

        port = value

        if (mjpegServer != null) restartServer() else renderConnectionInfo()
    }

    private fun updateActionButton() {
        actionButton.setText(
            if (mjpegServer != null) R.string.action_stop else R.string.action_start
        )
    }

    private fun renderConnectionInfo() {
        val server = mjpegServer
        val ip = server?.getLocalIpAddress()

        streamText.text = if (server != null && ip != null) {
            "http://$ip:$port/video"
        } else {
            getString(R.string.value_none)
        }

        hint.setText(
            when {
                server == null -> R.string.hint_server_stopped
                ip == null -> R.string.wifi_no_network
                else -> R.string.wifi_hint
            }
        )

        adbCommand.text = "adb forward tcp:$port tcp:$port"

        val clients = server?.activeClients ?: 0

        clientsText.text =
            resources.getQuantityString(R.plurals.clients, clients, clients)

        clientsText.setTextColor(
            ContextCompat.getColor(
                this,
                if (clients > 0) R.color.accent else R.color.text_muted
            )
        )

        setStatus(
            when {
                server == null -> R.string.status_stopped
                clients > 0 -> R.string.status_streaming
                else -> R.string.status_waiting
            },
            when {
                server == null -> R.color.text_muted
                clients > 0 -> R.color.accent
                else -> R.color.warn
            }
        )
    }

    private fun renderUsbState() {
        val sticky =
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        val attached = (plugged and BatteryManager.BATTERY_PLUGGED_USB) != 0

        cableText.setText(
            if (attached) R.string.cable_connected else R.string.cable_disconnected
        )

        cableText.setTextColor(
            ContextCompat.getColor(
                this,
                if (attached) R.color.accent else R.color.danger
            )
        )
    }

    private fun setStatus(textRes: Int, colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)

        statusText.setText(textRes)
        statusText.setTextColor(color)
        statusDot.background.mutate().setTint(color)
    }

    private fun copyToClipboard(label: String, value: String) {
        if (value.isEmpty() || value == getString(R.string.value_none)) return

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))

        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // ---------------- camera ----------------

    private fun checkCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) startCamera()
        else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera(cameraProvider!!)
            } catch (e: Exception) {
                setStatus(R.string.status_error, R.color.danger)
                hint.text = e.message
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        provider.unbindAll()

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        this.preview = preview

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(cameraExecutor) { image -> processFrame(image) }

        try {
            camera = provider.bindToLifecycle(
                cameraOwner,
                if (lens == LENS_FRONT) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                },
                preview,
                analysis
            )

            /* 换镜头就是换了一台相机，补光得重新落到新的 CameraControl 上 */
            applyLight()
            renderLight()
            applyFrameRate()
            applyFocus()

            /*
             * 换镜头就是换了一台相机：区间不一样（实测后置 10 倍、前置 4 倍），
             * 倍率也会掉回 1 倍，所以先读新区间、再把存下来的倍率下发一次。
             */
            readZoomBounds()
            applyZoom()

            streaming = true
        } catch (e: Exception) {
            camera = null
            streaming = false
            setStatus(R.string.status_error, R.color.danger)
            hint.text = e.message
        }
    }

    /*
     * 相机给的缓冲区尺寸和它报的旋转角，只在变了的时候回写。
     *
     * 要量这个是因为推出去的方向 = 缓冲区自己的方向 + 界面设的角度，
     * 而前者会变：同一颗后置实测昨天给 960x720、今天给 720x960。
     */
    private var lastSourceKey = ""

    private fun reportSource(image: ImageProxy) {
        val size = image.width.toString() + "x" + image.height
        val key = size + "@" + image.imageInfo.rotationDegrees

        if (key == lastSourceKey) return

        lastSourceKey = key

        mjpegServer?.let {
            it.sourceSize = size
            it.sourceRotation = image.imageInfo.rotationDegrees
        }
    }

    private fun processFrame(image: ImageProxy) {
        try {
            if (!streaming) return

            reportSource(image)

            val server = mjpegServer

            /*
             * 没有观看者时跳过编码，省掉手机侧的 CPU 与发热。
             */
            if (server == null || server.activeClients == 0) {
                lastEncodeTime.set(0L)
                return
            }

            /*
             * 必须用单调时钟：currentTimeMillis 是墙上时钟，毫秒粒度还会被 NTP 调。
             */
            val now = SystemClock.elapsedRealtime()
            val previous = lastEncodeTime.get()

            if (now - previous < frameIntervalMs) return
            if (!lastEncodeTime.compareAndSet(previous, now)) return

            /*
             * 缓冲区自己的方向也要算进来：ImageInfo.rotationDegrees 是
             * CameraX 给的「这一帧要顺时针转多少才正」，叠加界面上设的角度，
             * 推出去的方向就只取决于界面设的那个数。
             *
             * 不叠的话，同一颗镜头在不同次绑定里可能给 960x720 也可能给
             * 720x960（实测遇到过一次），界面上设的 90° 就会转成别的东西。
             */
            val jpeg = YuvToJpegConverter.convert(
                image,
                JPEG_QUALITY,
                (image.imageInfo.rotationDegrees + rotationDegrees) % 360
            )

            if (jpeg != null && jpeg.isNotEmpty()) {
                server.updateFrame(jpeg)
                countPushed()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

}

private class AlwaysResumedOwner : LifecycleOwner {

    private val registry = LifecycleRegistry.createUnsafe(this)

    override val lifecycle: Lifecycle get() = registry

    init {
        registry.currentState = Lifecycle.State.RESUMED
    }
}

private fun Context.withLanguage(tag: String): Context {
    val locale = Locale.forLanguageTag(tag)

    Locale.setDefault(locale)

    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList(locale))

    return createConfigurationContext(config)
}
