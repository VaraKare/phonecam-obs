package dev.phonecam.obs

import android.hardware.camera2.CameraCharacteristics as CC
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationTest {
    @Test fun snapsToNearestQuarterFromUnknown() {
        assertEquals(0, Orientation.quantize(10, Orientation.UNKNOWN))
        assertEquals(90, Orientation.quantize(80, Orientation.UNKNOWN))
        assertEquals(0, Orientation.quantize(350, Orientation.UNKNOWN))
        assertEquals(270, Orientation.quantize(300, Orientation.UNKNOWN))
    }
    @Test fun hysteresisKeepsPreviousNearDiagonal() {
        assertEquals(0, Orientation.quantize(50, 0))   // nearest 90 but 40° away
        assertEquals(90, Orientation.quantize(65, 0))  // within 30° of 90
        assertEquals(90, Orientation.quantize(130, 90))
        assertEquals(0, Orientation.quantize(330, 0))
    }
    @Test fun flatPhoneKeepsPrevious() { assertEquals(180, Orientation.quantize(-1, 180)) }
    @Test fun displayToDevice() {
        assertEquals(0, Orientation.fromDisplay(0)); assertEquals(270, Orientation.fromDisplay(90))
        assertEquals(90, Orientation.fromDisplay(270)); assertEquals(180, Orientation.fromDisplay(180))
    }
    // Vivo V2437: rear sensor 90, front sensor 270 (capability scan, 5 November 2026).
    @Test fun rearCameraMatchesObservedUprightLandscape() {
        // Top of phone pointing left = display ROTATION_90 = device 270: physically upright in OBS.
        assertEquals(0, Orientation.encodedRotation(90, CC.LENS_FACING_BACK, Orientation.fromDisplay(90)))
        // Top pointing right was the refused direction: needs a half turn.
        assertEquals(180, Orientation.encodedRotation(90, CC.LENS_FACING_BACK, Orientation.fromDisplay(270)))
        assertEquals(90, Orientation.encodedRotation(90, CC.LENS_FACING_BACK, 0))
        assertEquals(270, Orientation.encodedRotation(90, CC.LENS_FACING_BACK, 180))
    }
    @Test fun frontCamera() {
        assertEquals(270, Orientation.encodedRotation(270, CC.LENS_FACING_FRONT, 0))
        assertEquals(0, Orientation.encodedRotation(270, CC.LENS_FACING_FRONT, 270))
        assertEquals(180, Orientation.encodedRotation(270, CC.LENS_FACING_FRONT, 90))
    }
}
