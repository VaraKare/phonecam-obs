package dev.phonecam.obs

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest as CR
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit foreground-only live session. No muxer, media files, or automatic reconnect. */
class LiveStreamer(private val context: Context, private val profile: Profile,
                   private val highQuality: Boolean, private val rotation: Int,
                   private val notify: (String, Boolean) -> Unit) {
    private val thread = HandlerThread("phonecam-live-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(context.mainLooper)
    private var camera: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var input: Surface? = null
    private var finished = false
    private var sequence = 0L
    private var lastKeyRequest = 0L
    private var lastFrameAt = 0L
    private val stats = FrameStats()
    private val transport = LiveTransport(LiveTransport.token(context.filesDir),
        { handler.post { prepare() } }, { reason -> handler.post { finish(reason) } })
    private val startDeadline = Runnable { finish("No hardware video after 15 seconds; choose another profile or close other camera apps.") }
    private val status = object : Runnable {
        override fun run() {
            if (finished) return
            if (lastFrameAt > 0 && SystemClock.elapsedRealtime() - lastFrameAt > 5_000) {
                finish("Hardware encoder stalled for 5 seconds; restart live capture"); return
            }
            message("USB LIVE · ${profile.width}×${profile.height} · ${profile.mime} · ${if (highQuality) "HQ" else "FAST"}\nEncoded frames: $sequence · queue drops: ${transport.dropped}\nNo media is saved. Stop ends the session. Quality/optical latency unverified.")
            handler.postDelayed(this, 2_000)
        }
    }
    fun start() {
        handler.post {
            if (finished) return@post
            message("Waiting for the Mac/OBS connection (30-second limit). No camera capture until the authenticated USB receiver connects.")
            transport.start()
        }
    }
    fun stop(reason: String = "Stopped by you") { handler.post { finish(reason) } }
    private fun message(value: String, done: Boolean = false) { main.post { notify(value, done) } }

    @SuppressLint("MissingPermission")
    private fun prepare() {
        if (finished) return
        try {
            handler.postDelayed(startDeadline, 15_000)
            val encoder = MediaCodec.createByCodecName(profile.encoder)
            codec = encoder
            val format = MediaFormat.createVideoFormat(profile.mime, profile.width, profile.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(c: MediaCodec, index: Int) = Unit
                override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { finish("Hardware encoder failed: ${e.diagnosticInfo}") }
                override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
                    if (finished) return
                    try {
                        val csd = (0..2).mapNotNull { index -> format.getByteBuffer("csd-$index")?.duplicate()?.let {
                            ByteArray(it.remaining()).also(it::get)
                        } }
                        check(csd.isNotEmpty()) { "No encoder parameter sets" }
                        val bytes = ByteBuffer.allocate(16 + csd.sumOf { it.size }).order(ByteOrder.BIG_ENDIAN)
                        bytes.putInt(if (profile.mime == "video/avc") 1 else 2)
                        bytes.putInt(profile.width); bytes.putInt(profile.height); bytes.putInt(rotation)
                        csd.forEach(bytes::put)
                        transport.configure(bytes.array())
                    } catch (e: Exception) { finish("USB format rejected: ${e.message}") }
                }
                override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (finished) return
                    var error: String? = null
                    try {
                        check(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) { "Encoder ended unexpectedly" }
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(info.size <= LiveTransport.MAX_PAYLOAD) { "Encoded frame exceeds 4 MiB; lower resolution or bitrate" }
                            val buffer = c.getOutputBuffer(index) ?: error("Missing encoded buffer")
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); buffer.get(bytes)
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val accepted = transport.offer(LiveQueue.Frame(bytes, sequence++, info.presentationTimeUs, key))
                            stats.add(info.presentationTimeUs)
                            lastFrameAt = SystemClock.elapsedRealtime()
                            handler.removeCallbacks(startDeadline)
                            if (sequence == 1L) handler.post(status)
                            if (!accepted && SystemClock.elapsedRealtime() - lastKeyRequest > 250) {
                                lastKeyRequest = SystemClock.elapsedRealtime()
                                c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                            }
                        }
                    } catch (e: Exception) { error = "USB encoding stopped: ${e.message}" }
                    finally { runCatching { c.releaseOutputBuffer(index, false) } }
                    error?.let { finish(it) }
                }
            }, handler)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = encoder.createInputSurface(); encoder.start()
            context.getSystemService(CameraManager::class.java).openCamera(profile.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    // The main looper outlives the capture worker. A late open must still close its device.
                    if (!handler.post {
                        if (finished) device.close()
                        else {
                            camera = device
                            try { configureCamera(device) } catch (e: Exception) { finish("Camera session failed: ${e.message}") }
                        }
                    }) device.close()
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); handler.post { finish("Camera disconnected") } }
                override fun onError(device: CameraDevice, error: Int) { device.close(); handler.post { finish("Camera error $error") } }
            }, main)
        } catch (e: Exception) { finish("Could not start USB live: ${e.message}. No settings silently substituted.") }
    }

    @Suppress("DEPRECATION")
    private fun configureCamera(device: CameraDevice) {
        val surface = input ?: error("No encoder surface")
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (finished) { session.close(); return }
                capture = session
                try {
                    val chars = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(profile.cameraId)
                    val keys = chars.availableCaptureRequestKeys.toSet()
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        if (CR.CONTROL_AE_TARGET_FPS_RANGE in keys) set(CR.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)
                        if (CR.CONTROL_AF_MODE in keys && CR.CONTROL_AF_MODE_CONTINUOUS_VIDEO in chars[CC.CONTROL_AF_AVAILABLE_MODES]?.toSet().orEmpty())
                            set(CR.CONTROL_AF_MODE, CR.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        processingMode(highQuality, profile.edgeModes)?.let { if (CR.EDGE_MODE in keys) set(CR.EDGE_MODE, it) }
                        processingMode(highQuality, profile.noiseModes)?.let { if (CR.NOISE_REDUCTION_MODE in keys) set(CR.NOISE_REDUCTION_MODE, it) }
                        if (CR.CONTROL_VIDEO_STABILIZATION_MODE in keys) set(CR.CONTROL_VIDEO_STABILIZATION_MODE, CR.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        if (CR.LENS_OPTICAL_STABILIZATION_MODE in keys) set(CR.LENS_OPTICAL_STABILIZATION_MODE, CR.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    }.build()
                    session.setRepeatingRequest(request, null, handler)
                } catch (e: Exception) { finish("Camera rejected live settings: ${e.message}") }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) { session.close(); finish("Camera rejected this live profile") }
        }, handler)
    }

    private fun finish(reason: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(startDeadline); handler.removeCallbacks(status)
        transport.close()
        runCatching { capture?.stopRepeating() }; runCatching { capture?.close() }; capture = null
        runCatching { camera?.close() }; camera = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        runCatching { input?.release() }; input = null
        message("$reason\nLive capture stopped. $sequence encoded frames; ${transport.dropped} queue drops. No media saved.", true)
        thread.quitSafely()
    }
}
