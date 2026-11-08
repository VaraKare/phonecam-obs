package dev.phonecam.obs

import android.content.Context
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest as CR
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.AtomicFile
import android.util.Range
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class Profile(
    val cameraId: String, val width: Int, val height: Int, val mime: String,
    val encoder: String, val bitrate: Int, val fpsLower: Int, val fpsUpper: Int,
    val sensorOrientation: Int, val facing: Int, val highQuality: Boolean,
    val edgeModes: Set<Int>, val noiseModes: Set<Int>, val limited: Boolean,
    /** Output frame rate. [highSpeed] profiles capture faster (e.g. 120 fps) in a constrained high-speed session and the encoder drops to [fps]. */
    val fps: Int = 30, val highSpeed: Boolean = false,
) {
    /** Camera2 request value. Plain ints keep Profile usable in JVM unit tests. */
    val fpsRange: Range<Int> get() = Range(fpsLower, fpsUpper)
    fun json(hq: Boolean): JSONObject = JSONObject().apply {
        put("cameraId", cameraId); put("width", width); put("height", height)
        put("fps", fps); put("fpsRange", JSONArray(listOf(fpsLower, fpsUpper))); put("highSpeed", highSpeed)
        put("mime", mime); put("encoder", encoder); put("bitrate", bitrate)
        put("processing", if (highSpeed) "camera_default" else if (hq) "high_quality" else "fast")
        put("edgeMode", processingMode(hq, edgeModes)); put("noiseMode", processingMode(hq, noiseModes))
        put("stabilization", "off"); put("maxBFrames", 0); put("keyframeSeconds", 1)
        put("color", "SDR_BT709"); put("sensorOrientation", sensorOrientation)
        put("facing", facing); put("limited", limited)
        put("deviceBuild", Build.FINGERPRINT)
    }
    fun id(hq: Boolean): String = MessageDigest.getInstance("SHA-256")
        .digest(json(hq).toString().toByteArray()).joinToString("") { "%02x".format(it) }
    override fun toString() = "Camera $cameraId · ${width}×$height · ${if (mime == "video/avc") "H.264" else "H.265"} · ${bitrate / 1_000_000} Mb/s · $fps fps${if (highSpeed) " · high-speed" else ""}${if (limited) " · LIMITED" else " · unverified"}"
}

fun processingMode(hq: Boolean, available: Set<Int>): Int? = when {
    hq && CR.EDGE_MODE_HIGH_QUALITY in available -> CR.EDGE_MODE_HIGH_QUALITY
    !hq && CR.EDGE_MODE_FAST in available -> CR.EDGE_MODE_FAST
    CR.EDGE_MODE_OFF in available -> CR.EDGE_MODE_OFF
    else -> null
}

data class Scan(val report: JSONObject, val profiles: List<Profile>)

object Capabilities {
    fun scan(context: Context): Scan {
        val manager = context.getSystemService(CameraManager::class.java)
        val errors = JSONArray()
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            it.isEncoder && it.isHardwareAccelerated && !it.isSoftwareOnly && !it.isAlias
        }
        val encoderReports = JSONArray()
        for (encoder in encoders) for (mime in listOf("video/avc", "video/hevc")) {
            if (!encoder.supportedTypes.contains(mime)) continue
            try {
                val c = encoder.getCapabilitiesForType(mime)
                encoderReports.put(JSONObject().apply {
                    put("name", encoder.name); put("mime", mime); put("hardwareAdvertised", true)
                    put("surfaceInput", c.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
                    put("bitrateRange", c.videoCapabilities?.bitrateRange?.toString())
                    put("profileLevels", JSONArray(c.profileLevels.map { "${it.profile}:${it.level}" }))
                })
            } catch (e: Exception) { errors.put("Encoder ${encoder.name}: ${e.message}") }
        }
        val cameras = JSONArray()
        val profiles = mutableListOf<Profile>()
        for (id in manager.cameraIdList) {
            try {
                val c = manager.getCameraCharacteristics(id)
                val map = c[CC.SCALER_STREAM_CONFIGURATION_MAP] ?: continue
                val sizes = map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
                val ranges = c[CC.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.toList().orEmpty()
                val fpsRange = ranges.filter { it.lower <= 30 && it.upper >= 30 }
                    .minWithOrNull(compareBy<Range<Int>> { it.upper - it.lower }.thenBy { it.upper })
                val edge = c[CC.EDGE_AVAILABLE_EDGE_MODES]?.toSet().orEmpty()
                val noise = c[CC.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES]?.toSet().orEmpty()
                val facing = c[CC.LENS_FACING] ?: CC.LENS_FACING_EXTERNAL
                cameras.put(JSONObject().apply {
                    put("id", id); put("facing", facing); put("hardwareLevel", c[CC.INFO_SUPPORTED_HARDWARE_LEVEL])
                    put("sensorOrientation", c[CC.SENSOR_ORIENTATION]); put("timestampSource", c[CC.SENSOR_INFO_TIMESTAMP_SOURCE])
                    put("focalLengths", JSONArray(c[CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.toList().orEmpty()))
                    put("physicalIds", JSONArray(c.physicalCameraIds.toList()))
                    put("fpsRanges", JSONArray(ranges.map { listOf(it.lower, it.upper) }))
                    // Constrained high-speed modes (e.g. 120 fps) need a special session; reported only.
                    put("highSpeedVideo", JSONArray(runCatching { map.highSpeedVideoSizes.flatMap { s ->
                        map.getHighSpeedVideoFpsRangesFor(s).map { r -> listOf(s.width, s.height, r.lower, r.upper) } } }.getOrDefault(emptyList())))
                    put("edgeModes", JSONArray(edge.toList())); put("noiseModes", JSONArray(noise.toList()))
                    put("videoStabilizationModes", JSONArray(c[CC.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.toList().orEmpty()))
                    put("opticalStabilizationModes", JSONArray(c[CC.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.toList().orEmpty()))
                    put("recordingSizes", JSONArray(sizes.map { s -> JSONObject().apply {
                        put("width", s.width); put("height", s.height)
                        put("minFrameDurationNs", map.getOutputMinFrameDuration(MediaCodec::class.java, s))
                    } }))
                })
                if (fpsRange == null) continue
                for (s in sizes.filter { (it.width to it.height) in setOf(3840 to 2160, 1920 to 1080, 1280 to 720) }) {
                    val minDuration = map.getOutputMinFrameDuration(MediaCodec::class.java, s)
                    if (minDuration > 33_666_667L) continue
                    for (mime in listOf("video/avc", "video/hevc")) {
                        val encoder = encoderFor(encoders, mime, s.width, s.height, 30) ?: continue
                        val bitrateRange = encoder.getCapabilitiesForType(mime).videoCapabilities?.bitrateRange ?: continue
                        for (rate in if (s.width == 3840) listOf(20, 40, 60) else if (s.width == 1920) listOf(5, 10, 20) else listOf(3, 5)) {
                            val bitrate = rate * 1_000_000
                            if (!bitrateRange.contains(bitrate)) continue
                            profiles.add(Profile(id, s.width, s.height, mime, encoder.name, bitrate, fpsRange.lower, fpsRange.upper,
                                c[CC.SENSOR_ORIENTATION] ?: 0, facing,
                                CR.EDGE_MODE_HIGH_QUALITY in edge && CR.NOISE_REDUCTION_MODE_HIGH_QUALITY in noise,
                                edge, noise, s.width < 1920 || fpsRange.lower != 30 || fpsRange.upper != 30))
                        }
                    }
                }
                // Experimental 1080p60: a fixed high-speed range that is a multiple of 60 (e.g. [120,120]),
                // with the encoder dropping to 60. Offered only as an explicit, unverified choice.
                val fast = runCatching { map.highSpeedVideoSizes.filter { it.width == 1920 && it.height == 1080 } }.getOrDefault(emptyList())
                for (s in fast) {
                    val range = runCatching { map.getHighSpeedVideoFpsRangesFor(s).toList() }.getOrDefault(emptyList())
                        .filter { it.lower == it.upper && it.upper >= 60 && it.upper % 60 == 0 }.minByOrNull { it.upper } ?: continue
                    for (mime in listOf("video/avc", "video/hevc")) {
                        val encoder = encoderFor(encoders, mime, s.width, s.height, 60) ?: continue
                        val bitrateRange = encoder.getCapabilitiesForType(mime).videoCapabilities?.bitrateRange ?: continue
                        for (rate in listOf(10, 20, 30)) {
                            if (!bitrateRange.contains(rate * 1_000_000)) continue
                            profiles.add(Profile(id, s.width, s.height, mime, encoder.name, rate * 1_000_000, range.lower, range.upper,
                                c[CC.SENSOR_ORIENTATION] ?: 0, facing, false, edge, noise, false, fps = 60, highSpeed = true))
                        }
                    }
                }
            } catch (e: Exception) { errors.put("Camera $id: ${e.message}") }
        }
        val ordered = profiles.sortedWith(compareBy<Profile> { it.facing != CC.LENS_FACING_BACK }
            .thenBy { it.highSpeed }.thenBy { it.width != 1920 }.thenBy { it.cameraId }.thenBy { it.mime != "video/avc" }
            .thenBy { it.bitrate })
        return Scan(JSONObject().apply {
            put("schemaVersion", 1); put("createdAtMs", System.currentTimeMillis())
            put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("android", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT).put("build", Build.FINGERPRINT))
            put("support", if (ordered.isEmpty()) "unsupported" else "compatible_unverified")
            put("cameras", cameras); put("hardwareEncoders", encoderReports); put("errors", errors)
            put("profiles", JSONArray(ordered.map { it.json(false) }))
            put("notes", "Advertised combinations require physical capture validation. No camera extensions or stock-app processing are assumed.")
        }, ordered)
    }
}

/** One deterministic hardware surface-input encoder per profile; the full list remains in the report. */
private fun encoderFor(encoders: List<MediaCodecInfo>, mime: String, width: Int, height: Int, fps: Int): MediaCodecInfo? =
    encoders.sortedBy { it.name }.firstOrNull { e ->
        runCatching {
            if (!e.supportedTypes.contains(mime)) false else {
                val cap = e.getCapabilitiesForType(mime)
                cap.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                    cap.videoCapabilities?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
            }
        }.getOrDefault(false)
    }

fun writeJson(file: File, value: JSONObject) {
    val atomic = AtomicFile(file)
    val output = atomic.startWrite()
    try { output.write(value.toString(2).toByteArray()); atomic.finishWrite(output) }
    catch (e: Exception) { atomic.failWrite(output); throw e }
}
