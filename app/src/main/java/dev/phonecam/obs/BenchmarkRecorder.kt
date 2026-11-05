package dev.phonecam.obs

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest as CR
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One serialized owner for camera/codec/muxer; callbacks never retain video buffers. */
class BenchmarkRecorder(
    private val context: Context,
    private val directory: File,
    private val profile: Profile,
    private val highQuality: Boolean,
    private val seconds: Int,
    private val scene: String,
    private val rotationDegrees: Int,
    private val notify: (String, Boolean) -> Unit,
) {
    private val thread = HandlerThread("phonecam-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(context.mainLooper)
    private val sessionId = UUID.randomUUID().toString()
    private val partial = File(directory, "$sessionId.partial.mp4")
    private val finalFile = File(directory, "$sessionId.mp4")
    private val sidecar = File(directory, "$sessionId.json")
    private var camera: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var input: Surface? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxerStarted = false
    private var finished = false
    private var stopping = false
    private var stopReason = "duration"
    private var started = false
    private var codecStarted = false
    private val frameStats = FrameStats()
    private var bytes = 0L
    private var sensorFrames = 0L
    private var processingMismatchFrames = 0L
    private var processingUnconfirmedFrames = 0L
    private var firstSensorNs: Long? = null
    private var lastSensorNs: Long? = null
    private var observed = JSONObject()
    private var outputFormat = ""
    private val openedAtMs = SystemClock.elapsedRealtime()
    private val power = context.getSystemService(PowerManager::class.java)
    private var maxThermal = power.currentThermalStatus
    private var firstJava = 0L
    private var maxJava = 0L
    private var firstNative = 0L
    private var maxNative = 0L
    private var samples = 0
    private val initialBattery = batteryTemperature()

    private val startDeadline = Runnable { fail("Camera/encoder produced no video within 15 seconds. Try another declared profile or restart camera access.") }
    private val drainDeadline = Runnable { fail("Encoder did not finish within 5 seconds; partial recording retained.") }
    private val durationStop = Runnable { stopOnThread("duration") }
    private val sample = object : Runnable {
        override fun run() {
            if (finished) return
            val runtime = Runtime.getRuntime()
            val javaBytes = runtime.totalMemory() - runtime.freeMemory()
            val nativeBytes = Debug.getNativeHeapAllocatedSize()
            if (samples++ == 0) { firstJava = javaBytes; firstNative = nativeBytes }
            maxJava = maxOf(maxJava, javaBytes); maxNative = maxOf(maxNative, nativeBytes)
            maxThermal = maxOf(maxThermal, power.currentThermalStatus)
            handler.postDelayed(this, 2_000)
        }
    }

    fun start() { handler.post { prepare() } }
    fun stop(reason: String = "user_stop") { handler.post { stopOnThread(reason) } }

    private fun message(text: String, done: Boolean = false) { main.post { notify(text, done) } }
    private fun batteryTemperature(): Int? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeUnless { it == Int.MIN_VALUE }

    @SuppressLint("MissingPermission")
    private fun prepare() {
        try {
            require(seconds in 10..60) { "Benchmark duration must be 10–60 seconds." }
            require(!highQuality || profile.highQuality) { "This profile does not advertise both high-quality processing modes." }
            if (stopping || finished) return
            directory.mkdirs()
            val estimatedBytes = profile.bitrate.toLong() * seconds / 8
            require(directory.usableSpace > estimatedBytes * 12 / 10 + 32L * 1024 * 1024) {
                "Insufficient free storage for this explicit recording. Delete benchmark files or choose a shorter/lower-bitrate profile."
            }
            handler.postDelayed(startDeadline, 15_000)
            handler.post(sample)
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
                override fun onInputBufferAvailable(c: MediaCodec, index: Int) = Unit // Surface input.
                override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
                    if (finished) return
                    try {
                        check(!muxerStarted) { "Unexpected second encoder output format" }
                        outputFormat = format.toString()
                        track = muxer!!.addTrack(format)
                        muxer!!.start(); muxerStarted = true
                    } catch (e: Exception) { fail("Could not start MP4 output: ${e.message}") }
                }
                override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                    fail("Hardware encoder error: ${e.diagnosticInfo}. Try another declared profile.")
                }
                override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (finished) return
                    var failure: String? = null
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted) { "Video arrived before codec configuration" }
                            if (!started) {
                                check(info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) { "First encoded frame is not a keyframe" }
                                started = true
                                handler.removeCallbacks(startDeadline)
                                handler.postDelayed(durationStop, seconds * 1000L)
                                message("Recording $seconds seconds of $scene. Keep this app visible; Stop ends capture.")
                            }
                            val buffer = c.getOutputBuffer(index) ?: error("Missing encoder output buffer")
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer!!.writeSampleData(track, buffer, info)
                            frameStats.add(info.presentationTimeUs); bytes += info.size
                        }
                    } catch (e: Exception) { failure = "Recording failed: ${e.message}. Partial evidence retained." }
                    finally { runCatching { c.releaseOutputBuffer(index, false) } }
                    if (failure != null) fail(failure)
                    else if (eos) finish(if (stopping) null else "Encoder ended unexpectedly before the requested stop")
                }
            }, handler)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = encoder.createInputSurface()
            muxer = MediaMuxer(partial.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                val orientation = if (profile.facing == CC.LENS_FACING_FRONT)
                    (profile.sensorOrientation + rotationDegrees) % 360
                else (profile.sensorOrientation - rotationDegrees + 360) % 360
                setOrientationHint(orientation)
            }
            encoder.start(); codecStarted = true
            val manager = context.getSystemService(CameraManager::class.java)
            manager.openCamera(profile.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (finished || stopping) { device.close(); return }
                    camera = device
                    try { configureCamera(device) }
                    catch (e: Exception) { fail("Camera session failed: ${e.message}") }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); fail("Camera disconnected. Close other camera apps and retry.") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); fail("Camera error $error. Check camera permissions and other camera apps.") }
            }, handler)
            message("Opening camera ${profile.cameraId} and hardware encoder…")
        } catch (e: Exception) { fail("Could not start this profile: ${e.message}. No settings were silently substituted.") }
    }

    @Suppress("DEPRECATION")
    private fun configureCamera(device: CameraDevice) {
        val surface = input ?: error("Encoder input surface unavailable")
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (finished || stopping) { session.close(); return }
                capture = session
                try {
                    val chars = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(profile.cameraId)
                    val keys = chars.availableCaptureRequestKeys.toSet()
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        if (CR.CONTROL_AE_TARGET_FPS_RANGE in keys) set(CR.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)
                        val afModes = chars[CC.CONTROL_AF_AVAILABLE_MODES]?.toSet().orEmpty()
                        if (CR.CONTROL_AF_MODE in keys && CR.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes)
                            set(CR.CONTROL_AF_MODE, CR.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        processingMode(highQuality, profile.edgeModes)?.let { if (CR.EDGE_MODE in keys) set(CR.EDGE_MODE, it) }
                        processingMode(highQuality, profile.noiseModes)?.let { if (CR.NOISE_REDUCTION_MODE in keys) set(CR.NOISE_REDUCTION_MODE, it) }
                        if (CR.CONTROL_VIDEO_STABILIZATION_MODE in keys) set(CR.CONTROL_VIDEO_STABILIZATION_MODE, CR.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        if (CR.LENS_OPTICAL_STABILIZATION_MODE in keys) set(CR.LENS_OPTICAL_STABILIZATION_MODE, CR.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    }.build()
                    session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, request: CR, result: TotalCaptureResult) {
                            if (finished) return
                            sensorFrames++
                            val expectedEdge = processingMode(highQuality, profile.edgeModes)
                            val expectedNoise = processingMode(highQuality, profile.noiseModes)
                            val actualEdge = result[CaptureResult.EDGE_MODE]
                            val actualNoise = result[CaptureResult.NOISE_REDUCTION_MODE]
                            if ((expectedEdge != null && actualEdge == null) || (expectedNoise != null && actualNoise == null))
                                processingUnconfirmedFrames++
                            if ((expectedEdge != null && actualEdge != null && expectedEdge != actualEdge) ||
                                (expectedNoise != null && actualNoise != null && expectedNoise != actualNoise))
                                processingMismatchFrames++
                            result[CaptureResult.SENSOR_TIMESTAMP]?.let { if (firstSensorNs == null) firstSensorNs = it; lastSensorNs = it }
                            observed = JSONObject().apply {
                                put("exposureTimeNs", result[CaptureResult.SENSOR_EXPOSURE_TIME])
                                put("sensitivityIso", result[CaptureResult.SENSOR_SENSITIVITY])
                                put("edgeMode", result[CaptureResult.EDGE_MODE])
                                put("noiseMode", result[CaptureResult.NOISE_REDUCTION_MODE])
                                put("autofocusMode", result[CaptureResult.CONTROL_AF_MODE])
                                put("autofocusState", result[CaptureResult.CONTROL_AF_STATE])
                                put("videoStabilizationMode", result[CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE])
                                put("opticalStabilizationMode", result[CaptureResult.LENS_OPTICAL_STABILIZATION_MODE])
                                put("fpsRange", result[CaptureResult.CONTROL_AE_TARGET_FPS_RANGE]?.toString())
                            }
                        }
                    }, handler)
                } catch (e: Exception) { fail("Camera rejected the recording configuration: ${e.message}") }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close(); fail("Camera rejected this stream combination. Try 1080p or a limited profile.")
            }
        }, handler)
    }

    private fun stopOnThread(reason: String) {
        if (finished || stopping) return
        stopping = true; stopReason = reason
        handler.removeCallbacks(durationStop)
        runCatching { capture?.stopRepeating() }
        runCatching { capture?.abortCaptures() }
        runCatching { capture?.close() }; capture = null
        runCatching { camera?.close() }; camera = null
        if (!codecStarted) { finish("Capture stopped before encoder startup"); return }
        try {
            codec!!.signalEndOfInputStream()
            handler.postDelayed(drainDeadline, 5_000)
            message("Finishing recording…")
        } catch (e: Exception) { fail("Could not drain hardware encoder: ${e.message}") }
    }

    private fun fail(reason: String) { if (!finished) finish(reason) }

    private fun finish(error: String?) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        runCatching { capture?.close() }; capture = null
        runCatching { camera?.close() }; camera = null
        var failure = error
        if (muxerStarted) {
            try { muxer?.stop() } catch (e: Exception) { if (failure == null) failure = "MP4 finalization failed: ${e.message}" }
        }
        runCatching { muxer?.release() }; muxer = null
        if (codecStarted) runCatching { codec?.stop() }
        runCatching { codec?.release() }; codec = null
        runCatching { input?.release() }; input = null
        if (frameStats.frames == 0L && failure == null) failure = "No encoded frames were produced"
        if (failure == null && !partial.renameTo(finalFile)) failure = "Could not finalize filename; partial retained"
        val success = failure == null
        try {
            writeJson(sidecar, JSONObject().apply {
                put("schemaVersion", 1); put("source", "probe"); put("sessionId", sessionId)
                put("appVersion", "0.1.0-research"); put("createdAtMs", System.currentTimeMillis())
                put("success", success); put("error", failure ?: JSONObject.NULL)
                put("stopReason", stopReason); put("scene", scene)
                put("mediaFile", if (success) finalFile.name else partial.name)
                put("profileId", profile.id(highQuality)); put("requested", profile.json(highQuality))
                put("observedCameraLast", observed); put("encoderOutputFormat", outputFormat)
                put("sensorFrames", sensorFrames); put("firstSensorNs", firstSensorNs); put("lastSensorNs", lastSensorNs)
                put("processingMismatchFrames", processingMismatchFrames)
                put("processingUnconfirmedFrames", processingUnconfirmedFrames)
                put("encoded", JSONObject().apply {
                    put("frames", frameStats.frames); put("bytes", bytes); put("fps", frameStats.fps)
                    put("durationUs", frameStats.durationUs); put("firstPtsUs", frameStats.firstPtsUs); put("lastPtsUs", frameStats.lastPtsUs)
                    put("maxGapUs", frameStats.maxGapUs); put("recentP95GapUs", frameStats.recentP95GapUs())
                    put("retainedGaps", frameStats.retainedGaps); put("nonMonotonicPts", frameStats.nonMonotonic)
                })
                put("telemetry", JSONObject().apply {
                    put("wallDurationMs", SystemClock.elapsedRealtime() - openedAtMs)
                    put("maxThermalStatus", maxThermal); put("batteryStartTenthsC", initialBattery)
                    put("batteryEndTenthsC", batteryTemperature()); put("memorySamples", samples)
                    put("firstJavaHeapBytes", firstJava); put("maxJavaHeapBytes", maxJava)
                    put("firstNativeHeapBytes", firstNative); put("maxNativeHeapBytes", maxNative)
                    put("notes", "Bounded samples; excludes total RSS. Encode timing is not optical latency.")
                })
                put("qualityStatus", "not_reviewed"); put("opticalLatencyStatus", "not_measured")
            })
            message(if (success) "Recorded ${finalFile.name}\n${"%.2f".format(frameStats.fps)} fps · ${frameStats.frames} frames. Quality unverified. Collect using the Mac tool."
                else "Stopped: $failure\nPartial evidence retained in ${directory.absolutePath}", true)
        } catch (e: Exception) { message("Capture stopped, but evidence report could not be saved: ${e.message}. Do not qualify this recording.", true) }
        thread.quitSafely()
    }
}
