package dev.phonecam.obs

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Dimmed camera view with a rounded scan window, moving scan line, and a green tick on success. */
class ScanOverlay(context: Context) : View(context) {
    enum class State { SCANNING, WORKING, DONE, FAILED }
    var state = State.SCANNING; set(value) { field = value; invalidate() }
    private val density = resources.displayMetrics.density
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * density; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * density; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator()
        addUpdateListener { progress = it.animatedValue as Float; if (state == State.SCANNING) invalidate() }
    }
    /** The scan window, in view coordinates: a rounded square slightly above centre. */
    val window: RectF get() {
        val side = minOf(width, height) * 0.68f
        val cx = width / 2f; val cy = height * 0.44f
        return RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = window; val radius = 22 * density
        canvas.drawPath(Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRoundRect(w, radius, radius, Path.Direction.CW)
        }, dim)
        val accent = when (state) { State.DONE -> 0xFF22C55E.toInt(); State.FAILED -> 0xFFE5484D.toInt(); else -> Color.WHITE }
        stroke.color = accent
        val arm = 30 * density
        canvas.drawPath(Path().apply {   // four corner brackets
            moveTo(w.left, w.top + arm); lineTo(w.left, w.top + radius); quadTo(w.left, w.top, w.left + radius, w.top); lineTo(w.left + arm, w.top)
            moveTo(w.right - arm, w.top); lineTo(w.right - radius, w.top); quadTo(w.right, w.top, w.right, w.top + radius); lineTo(w.right, w.top + arm)
            moveTo(w.right, w.bottom - arm); lineTo(w.right, w.bottom - radius); quadTo(w.right, w.bottom, w.right - radius, w.bottom); lineTo(w.right - arm, w.bottom)
            moveTo(w.left + arm, w.bottom); lineTo(w.left + radius, w.bottom); quadTo(w.left, w.bottom, w.left, w.bottom - radius); lineTo(w.left, w.bottom - arm)
        }, stroke)
        when (state) {
            State.SCANNING -> {
                val y = w.top + 16 * density + progress * (w.height() - 32 * density)
                line.alpha = 200
                canvas.drawLine(w.left + 18 * density, y, w.right - 18 * density, y, line)
            }
            State.DONE -> {
                wash.color = 0x3322C55E
                canvas.drawRoundRect(w, radius, radius, wash)
                val c = w.centerX(); val m = w.centerY(); val s = w.width() * 0.16f
                stroke.strokeWidth = 7 * density
                canvas.drawPath(Path().apply { moveTo(c - s, m); lineTo(c - s * 0.3f, m + s * 0.7f); lineTo(c + s, m - s * 0.6f) }, stroke)
                stroke.strokeWidth = 4 * density
            }
            else -> Unit
        }
    }
}

/**
 * Scans the pairing QR code shown in the OBS source and pairs over Wi-Fi. Frames are decoded in
 * memory only and never saved. The camera closes as soon as a PhoneCam code is read, or on leaving.
 * Result: RESULT_OK with a "message", or RESULT_TYPE_CODE when the user prefers typing the code.
 */
class ScanActivity : Activity() {
    companion object { const val RESULT_TYPE_CODE = RESULT_FIRST_USER }
    private lateinit var container: FrameLayout
    private lateinit var preview: SurfaceView
    private lateinit var overlay: ScanOverlay
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var torchButton: TextView
    private lateinit var retryButton: TextView
    private val feedback by lazy { Feedback(this) }
    private val thread = HandlerThread("phonecam-scan").apply { start() }
    private val handler = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surfaceReady = false
    private var torch = false
    private var hasTorch = false
    @Volatile private var done = false
    private val decoder = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pill(text: String, click: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(24).toFloat() }
        setPadding(dp(20), dp(12), dp(20), dp(12)); setOnClickListener { click() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = SurfaceView(this).apply {
            holder.setFixedSize(1280, 720)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; open() }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; closeCamera() }
            })
        }
        overlay = ScanOverlay(this)
        title = TextView(this).apply { text = "Scan to pair with OBS"; setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        hint = TextView(this).apply {
            text = "In OBS: PhoneCam source → Properties → Pair a phone over Wi-Fi"
            setTextColor(0xCCFFFFFF.toInt()); textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(24), dp(8), dp(24), 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(24), 0, 0); addView(title); addView(hint) }
        torchButton = pill("Flashlight") { setTorch(!torch) }
        retryButton = pill("Scan again") { scanAgain() }.apply { visibility = View.GONE }
        val typeButton = pill("Type code instead") { setResult(RESULT_TYPE_CODE); finish() }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), dp(32))
            listOf(torchButton, retryButton, typeButton).forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), 0, dp(6), 0) }) }
        }
        val scanView = overlay; val camera = preview
        container = object : FrameLayout(this) {
            // Fill the screen with the 16:9 camera picture (portrait 9:16), cropping the overflow.
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val w = r - l; val h = b - t
                val pw: Int; val ph: Int
                if (w * 16 > h * 9) { pw = w; ph = w * 16 / 9 } else { ph = h; pw = h * 9 / 16 }
                camera.layout((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2)
                for (i in 0 until childCount) getChildAt(i).takeIf { it !== camera }?.let { child ->
                    val lp = child.layoutParams as LayoutParams
                    val y = if (lp.gravity and Gravity.BOTTOM == Gravity.BOTTOM) h - child.measuredHeight - paddingBottom else paddingTop
                    if (child === scanView) child.layout(0, 0, w, h) else child.layout(0, y, w, y + child.measuredHeight)
                }
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            addView(camera); addView(scanView, FrameLayout.LayoutParams(-1, -1))
            addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP)); addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            setOnApplyWindowInsetsListener { v, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).let { v.setPadding(0, it.top, 0, it.bottom) }
                insets
            }
        }
        setContentView(container)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) open() else hint.text = "Camera access is needed to scan. You can type the code instead."
    }
    override fun onPause() { super.onPause(); closeCamera() }
    override fun onResume() { super.onResume(); open() }
    override fun onDestroy() { super.onDestroy(); closeCamera(); thread.quitSafely(); feedback.release() }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (done || camera != null || !surfaceReady || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
            ?: manager.cameraIdList.firstOrNull() ?: run { hint.text = "No camera found. You can type the code instead."; return }
        hasTorch = manager.getCameraCharacteristics(id)[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true
        torchButton.visibility = if (hasTorch) View.VISIBLE else View.GONE
        val images = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2).also { reader = it }
        images.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (done) return@setOnImageAvailableListener
                val plane = image.planes[0]
                val luma = ByteArray(plane.buffer.remaining()).also(plane.buffer::get)
                val source = PlanarYUVLuminanceSource(luma, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                val text = runCatching { decoder.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
                decoder.reset()
                if (text != null) found(text)
            } finally { image.close() }
        }, handler)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (done || isFinishing) { device.close(); return }
                camera = device
                val targets = listOf(preview.holder.surface, images.surface)
                @Suppress("DEPRECATION")
                device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; repeat() }
                    override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { hint.text = "The camera could not start. You can type the code instead." } }
                }, handler)
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
            override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; runOnUiThread { hint.text = "Camera error $error. You can type the code instead." } }
        }, handler)
    }
    private fun repeat() {
        val device = camera ?: return; val s = session ?: return; val images = reader ?: return
        runCatching {
            s.setRepeatingRequest(device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview.holder.surface); addTarget(images.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }.build(), null, handler)
        }
    }
    private fun setTorch(on: Boolean) {
        torch = on
        torchButton.text = if (on) "Flashlight on" else "Flashlight"
        handler.post { repeat() }
    }
    private fun closeCamera() {
        runCatching { camera?.close() }; camera = null; session = null
        runCatching { reader?.close() }; reader = null
    }
    private fun scanAgain() {
        retryButton.visibility = View.GONE; title.text = "Scan to pair with OBS"
        hint.text = "In OBS: PhoneCam source → Properties → Pair a phone over Wi-Fi"
        overlay.state = ScanOverlay.State.SCANNING
        done = false; open()
    }

    private fun found(text: String) {
        val link = PairingLink.parse(text)
        if (link == null) { runOnUiThread { hint.text = "That isn't a PhoneCam code. Point at the QR code in the OBS source." }; return }
        done = true
        runOnUiThread {
            feedback.play(Feedback.Event.TICK, overlay)
            closeCamera()
            overlay.state = ScanOverlay.State.WORKING
            title.text = "Pairing with ${link.name}…"; hint.text = "Keep the phone and the Mac on the same Wi-Fi."
        }
        Thread {
            val result = runCatching { WifiConnector(this).pair(link) }
            runOnUiThread {
                result.onSuccess { name ->
                    overlay.state = ScanOverlay.State.DONE
                    title.text = "Paired with $name"; hint.text = "Tap Start live to go live over Wi-Fi."
                    feedback.play(Feedback.Event.SUCCESS, overlay)
                    setResult(RESULT_OK, intent.putExtra("message", "Paired with $name. Tap Start live with the phone and Mac on the same Wi-Fi."))
                    overlay.postDelayed({ finish() }, 1400)
                }.onFailure { e ->
                    overlay.state = ScanOverlay.State.FAILED
                    title.text = "Couldn't pair"; hint.text = e.message ?: "Pairing failed."
                    feedback.play(Feedback.Event.ERROR, overlay)
                    retryButton.visibility = View.VISIBLE
                }
            }
        }.start()
    }
}
