package dev.phonecam.obs

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CaptureRequest as CR
import android.os.Build
import kotlin.math.roundToInt

/**
 * Live camera adjustments chosen on the phone. They change the camera's capture requests only;
 * the stream format never changes. [focusX]/[focusY] are a tapped point in the sensor-oriented
 * output frame (0..1), or null for continuous autofocus. [mirror] flips the picture in OBS.
 */
data class Controls(
    val zoom: Float = 1f, val exposure: Int = 0, val whiteBalance: Int = CR.CONTROL_AWB_MODE_AUTO,
    val torch: Boolean = false, val focusX: Float? = null, val focusY: Float? = null, val mirror: Boolean = false,
)

/** What one camera supports, read from its characteristics. Unsupported controls are hidden. */
data class Abilities(
    val maxZoom: Float, val zoomRatio: Boolean, val exposureMin: Int, val exposureMax: Int, val exposureStep: Float,
    val whiteBalances: List<Int>, val torch: Boolean, val tapFocus: Boolean, val tapExposure: Boolean,
    val activeWidth: Int, val activeHeight: Int,
) {
    companion object {
        /** Presets offered in this order when the camera lists them. */
        val PRESETS = listOf(CR.CONTROL_AWB_MODE_AUTO, CR.CONTROL_AWB_MODE_DAYLIGHT, CR.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CR.CONTROL_AWB_MODE_INCANDESCENT, CR.CONTROL_AWB_MODE_FLUORESCENT)

        fun from(c: android.hardware.camera2.CameraCharacteristics): Abilities {
            val ratio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c[CC.CONTROL_ZOOM_RATIO_RANGE] else null
            val maxZoom = ratio?.upper ?: c[CC.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f
            val ev = c[CC.CONTROL_AE_COMPENSATION_RANGE]
            val step = c[CC.CONTROL_AE_COMPENSATION_STEP]?.toFloat() ?: 0f
            val awb = c[CC.CONTROL_AWB_AVAILABLE_MODES]?.toSet().orEmpty()
            val af = c[CC.CONTROL_AF_AVAILABLE_MODES]?.toSet().orEmpty()
            val active = c[CC.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: Rect(0, 0, 1, 1)
            return Abilities(
                maxZoom = maxZoom.coerceAtLeast(1f), zoomRatio = ratio != null,
                exposureMin = if (step > 0f) ev?.lower ?: 0 else 0, exposureMax = if (step > 0f) ev?.upper ?: 0 else 0, exposureStep = step,
                whiteBalances = PRESETS.filter { it in awb }.ifEmpty { listOf(CR.CONTROL_AWB_MODE_AUTO) },
                torch = c[CC.FLASH_INFO_AVAILABLE] == true,
                tapFocus = CR.CONTROL_AF_MODE_AUTO in af && (c[CC.CONTROL_MAX_REGIONS_AF] ?: 0) > 0,
                tapExposure = (c[CC.CONTROL_MAX_REGIONS_AE] ?: 0) > 0,
                activeWidth = active.width(), activeHeight = active.height(),
            )
        }

        fun whiteBalanceName(mode: Int) = when (mode) {
            CR.CONTROL_AWB_MODE_DAYLIGHT -> "Daylight"; CR.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Cloudy"
            CR.CONTROL_AWB_MODE_INCANDESCENT -> "Indoor"; CR.CONTROL_AWB_MODE_FLUORESCENT -> "Fluorescent"; else -> "Auto"
        }
    }
}

/** Pure geometry for tap-to-focus and zoom; unit-tested without a camera. */
object CameraGeometry {
    /**
     * Converts a tap on the on-screen preview (0..1, as displayed) to the sensor-oriented output
     * frame (0..1). The camera framework shows the preview upright for the current display rotation
     * and mirrors front cameras; this undoes both.
     */
    fun viewToSensor(x: Float, y: Float, sensorOrientation: Int, front: Boolean, displayDegrees: Int): Pair<Float, Float> {
        var u = if (front) 1f - x else x
        var v = y
        val clockwise = ((if (front) sensorOrientation + displayDegrees else sensorOrientation - displayDegrees) % 360 + 360) % 360
        repeat(clockwise / 90) { val nu = v; val nv = 1f - u; u = nu; v = nv }  // one counter-clockwise quarter turn
        return u.coerceIn(0f, 1f) to v.coerceIn(0f, 1f)
    }

    /** The active-array rectangle [l, t, r, b] the output frame shows: centred aspect crop, then zoom. */
    fun frameRect(activeW: Int, activeH: Int, outW: Int, outH: Int, zoom: Float): IntArray {
        var w = activeW.toFloat(); var h = activeH.toFloat()
        if (w / h > outW.toFloat() / outH) w = h * outW / outH else h = w * outH / outW
        w /= zoom.coerceAtLeast(1f); h /= zoom.coerceAtLeast(1f)
        val left = ((activeW - w) / 2).roundToInt(); val top = ((activeH - h) / 2).roundToInt()
        return intArrayOf(left, top, left + w.roundToInt(), top + h.roundToInt())
    }

    /**
     * Metering rectangle (about a tenth of the frame) around a point of the output frame, in
     * active-array coordinates. With CONTROL_ZOOM_RATIO the coordinates already describe the zoomed
     * view, so zoom is applied only for the older crop-region method.
     */
    fun meteringRect(x: Float, y: Float, a: Abilities, outW: Int, outH: Int, zoom: Float): IntArray {
        val (fl, ft, fr, fb) = frameRect(a.activeWidth, a.activeHeight, outW, outH, if (a.zoomRatio) 1f else zoom)
        val cx = fl + x * (fr - fl); val cy = ft + y * (fb - ft)
        val half = maxOf(fr - fl, fb - ft) / 20f
        val l = (cx - half).roundToInt().coerceIn(0, a.activeWidth - 1); val t = (cy - half).roundToInt().coerceIn(0, a.activeHeight - 1)
        val r = (cx + half).roundToInt().coerceIn(l + 1, a.activeWidth); val b = (cy + half).roundToInt().coerceIn(t + 1, a.activeHeight)
        return intArrayOf(l, t, r, b)
    }

    /** Crop region for digital zoom on cameras without CONTROL_ZOOM_RATIO (keeps the sensor aspect). */
    fun zoomCrop(activeW: Int, activeH: Int, zoom: Float): IntArray {
        val z = zoom.coerceAtLeast(1f)
        val w = (activeW / z).roundToInt(); val h = (activeH / z).roundToInt()
        val l = (activeW - w) / 2; val t = (activeH - h) / 2
        return intArrayOf(l, t, l + w, t + h)
    }
}
