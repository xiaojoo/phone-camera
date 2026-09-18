package com.camera

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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
         * 第一版控制在 15 FPS 左右，
         * 每一个 CameraX frame 都编码会让手机明显发热。
         */
        private const val TARGET_FPS = 15
        private const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS

        private const val POLL_INTERVAL_MS = 1000L

        private const val PREFS_UI = "ui"
        private const val KEY_LANGUAGE = "lang"
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var wifiSegment: TextView
    private lateinit var usbSegment: TextView
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

    private var mjpegServer: MjpegServer? = null

    private var port = DEFAULT_PORT

    private var streaming = false

    private val lastEncodeTime = AtomicLong(0L)

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

        setContentView(R.layout.activity_main)

        initViews()
        wireControls()

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

        mainHandler.removeCallbacks(statusPoller)

        runCatching { unregisterReceiver(powerReceiver) }
        runCatching { cameraProvider?.unbindAll() }
        runCatching { mjpegServer?.stop() }
        runCatching { cameraExecutor.shutdownNow() }

        super.onDestroy()
    }

    // ---------------- views ----------------

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
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

        portField.setText(port.toString())
    }

    private fun wireControls() {
        wifiSegment.setOnClickListener { showUsbMode(false) }
        usbSegment.setOnClickListener { showUsbMode(true) }

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

    // ---------------- server ----------------

    private fun startServer() {
        if (mjpegServer != null) return

        mjpegServer = MjpegServer(port).apply { start() }

        mainHandler.postDelayed(statusPoller, POLL_INTERVAL_MS)

        renderConnectionInfo()
        updateActionButton()
    }

    private fun stopServer() {
        val server = mjpegServer ?: return

        mainHandler.removeCallbacks(statusPoller)
        server.stop()
        mjpegServer = null

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
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )

            streaming = true
        } catch (e: Exception) {
            streaming = false
            setStatus(R.string.status_error, R.color.danger)
            hint.text = e.message
        }
    }

    private fun processFrame(image: ImageProxy) {
        try {
            if (!streaming) return

            val server = mjpegServer

            /*
             * 没有观看者时跳过编码，省掉手机侧的 CPU 与发热。
             */
            if (server == null || server.activeClients == 0) {
                lastEncodeTime.set(0L)
                return
            }

            val now = System.currentTimeMillis()
            val previous = lastEncodeTime.get()

            if (now - previous < FRAME_INTERVAL_MS) return
            if (!lastEncodeTime.compareAndSet(previous, now)) return

            val jpeg = YuvToJpegConverter.convert(image, JPEG_QUALITY)

            if (jpeg != null && jpeg.isNotEmpty()) {
                server.updateFrame(jpeg)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

}

private fun Context.withLanguage(tag: String): Context {
    val locale = Locale(tag)

    Locale.setDefault(locale)

    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList(locale))

    return createConfigurationContext(config)
}
