package dev.phonecam.obs

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
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
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Scans the pairing QR code shown in the OBS source and pairs over Wi-Fi. Frames are decoded in
 * memory only and never saved. The camera closes as soon as a PhoneCam code is read, or on leaving.
 */
class ScanActivity : Activity() {
    private lateinit var preview: SurfaceView
    private lateinit var hint: TextView
    private val thread = HandlerThread("phonecam-scan").apply { start() }
    private val handler = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var reader: ImageReader? = null
    private var surfaceReady = false
    @Volatile private var done = false
    private val decoder = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
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
        hint = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xCC000000.toInt()); textSize = 16f; gravity = Gravity.CENTER
            val pad = (16 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
            text = "Point the camera at the QR code in OBS.\nIn OBS: PhoneCam source → Properties → Pair a phone over Wi-Fi."
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) open() else { hint.text = "Camera access is needed to scan the code. You can type the code instead."; }
    }
    override fun onPause() { super.onPause(); closeCamera() }
    override fun onResume() { super.onResume(); open() }
    override fun onDestroy() { super.onDestroy(); closeCamera(); thread.quitSafely() }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (done || camera != null || !surfaceReady || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
            ?: manager.cameraIdList.firstOrNull() ?: run { hint.text = "No camera found. Type the code instead."; return }
        val images = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2).also { reader = it }
        images.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (done) return@setOnImageAvailableListener
                val plane = image.planes[0]
                val buffer = plane.buffer
                val luma = ByteArray(buffer.remaining()).also(buffer::get)
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
                    override fun onConfigured(session: CameraCaptureSession) {
                        runCatching {
                            session.setRepeatingRequest(device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                targets.forEach(::addTarget)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }.build(), null, handler)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) { runOnUiThread { hint.text = "The camera could not start. Type the code instead." } }
                }, handler)
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
            override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; runOnUiThread { hint.text = "Camera error $error. Type the code instead." } }
        }, handler)
    }
    private fun closeCamera() {
        runCatching { camera?.close() }; camera = null
        runCatching { reader?.close() }; reader = null
    }

    private fun found(text: String) {
        val link = PairingLink.parse(text)
        if (link == null) { runOnUiThread { hint.text = "That isn't a PhoneCam pairing code. Point at the QR code in the OBS source." }; return }
        done = true
        runOnUiThread { closeCamera(); hint.text = "Pairing with ${link.name}…" }
        Thread {
            val result = runCatching { WifiConnector(this).pair(link) }
            runOnUiThread {
                result.onSuccess { name ->
                    setResult(RESULT_OK, intent.putExtra("message", "Paired with $name. Tap Start live with the phone and Mac on the same Wi-Fi."))
                    finish()
                }.onFailure { e ->
                    hint.text = "${e.message}\nTap to scan again."
                    hint.setOnClickListener { hint.setOnClickListener(null); done = false; hint.text = "Point the camera at the QR code in OBS."; open() }
                }
            }
        }.start()
    }
}
