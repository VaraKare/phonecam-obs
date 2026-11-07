package dev.phonecam.obs

import android.hardware.camera2.CameraCharacteristics

/**
 * Physical phone orientation → clockwise rotation the Mac must apply to the un-mirrored
 * encoded camera image so it appears upright. Uses gravity (OrientationEventListener), so it
 * works with the system rotation lock on.
 */
object Orientation {
    /** OrientationEventListener convention: 0 natural, 90 left side up, 180 upside down, 270 right side up. */
    const val UNKNOWN = -1

    /**
     * Snap a raw sensor angle to a quarter turn. A new quarter is accepted only within 30° of its
     * centre, so holding the phone near a diagonal does not flicker between orientations.
     */
    fun quantize(degrees: Int, previous: Int): Int {
        if (degrees < 0) return previous
        val angle = degrees % 360
        val nearest = ((angle + 45) / 90 % 4) * 90
        if (previous == UNKNOWN || nearest == previous) return nearest
        val distance = minOf((angle - nearest + 360) % 360, (nearest - angle + 360) % 360)
        return if (distance <= 30) nearest else previous
    }

    /** Display rotation in degrees (Surface.ROTATION_*) → physical device orientation. */
    fun fromDisplay(displayDegrees: Int): Int = (360 - displayDegrees) % 360

    /** Clockwise degrees (0/90/180/270) to make the encoded image upright for the viewer. */
    fun encodedRotation(sensorOrientation: Int, facing: Int, device: Int): Int =
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) (sensorOrientation - device + 360) % 360
        else (sensorOrientation + device) % 360
}
