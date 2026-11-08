package dev.phonecam.obs

import org.junit.Assert.*
import org.junit.Test

class ControlsTest {
    private fun near(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 1e-4f); assertEquals(expected.second, actual.second, 1e-4f)
    }
    private val abilities = Abilities(10f, true, -20, 20, 1f / 6, listOf(1), true, true, true, 4000, 3000)

    @Test fun rearPortraitTopOfScreenIsSensorLeft() =
        near(0f to 0.5f, CameraGeometry.viewToSensor(0.5f, 0f, 90, false, 0))
    @Test fun rearLandscapeMatchesSensor() =
        near(0.2f to 0.7f, CameraGeometry.viewToSensor(0.2f, 0.7f, 90, false, 90))
    @Test fun rearOtherLandscapeIsHalfTurn() =
        near(0.75f to 0.75f, CameraGeometry.viewToSensor(0.25f, 0.25f, 90, false, 270))
    @Test fun frontPortraitUndoesMirrorAndRotation() =
        near(1f to 0.5f, CameraGeometry.viewToSensor(0.5f, 0f, 270, true, 0))
    @Test fun frontLeftOfScreenIsMirrored() =
        // Front, landscape (display 90): 270 + 90 = 360, so only the mirror applies.
        near(0.9f to 0.4f, CameraGeometry.viewToSensor(0.1f, 0.4f, 270, true, 90))

    @Test fun frameRectCropsAspectThenZooms() {
        assertArrayEquals(intArrayOf(0, 375, 4000, 2625), CameraGeometry.frameRect(4000, 3000, 1920, 1080, 1f))
        assertArrayEquals(intArrayOf(1000, 938, 3000, 2063), CameraGeometry.frameRect(4000, 3000, 1920, 1080, 2f))
    }
    @Test fun meteringRectCentredAndClamped() {
        assertArrayEquals(intArrayOf(1800, 1300, 2200, 1700), CameraGeometry.meteringRect(0.5f, 0.5f, abilities, 1920, 1080, 4f))
        assertArrayEquals(intArrayOf(0, 175, 200, 575), CameraGeometry.meteringRect(0f, 0f, abilities, 1920, 1080, 1f))
    }
    @Test fun cropRegionMethodAppliesZoom() {
        val old = abilities.copy(zoomRatio = false)
        // Zoom 2: frame [1000, 938, 3000, 2063]; centre (2000, 1500.5); half = 2000 / 20 = 100.
        assertArrayEquals(intArrayOf(1900, 1401, 2100, 1601), CameraGeometry.meteringRect(0.5f, 0.5f, old, 1920, 1080, 2f))
        assertArrayEquals(intArrayOf(1000, 750, 3000, 2250), CameraGeometry.zoomCrop(4000, 3000, 2f))
    }
}
