package dev.phonecam.obs

import android.hardware.camera2.CameraCharacteristics as CC
import org.junit.Assert.*
import org.junit.Test

class QualityTest {
    private fun p(id: String, w: Int, mime: String, mbps: Int, facing: Int = CC.LENS_FACING_BACK, limited: Boolean = false) =
        Profile(id, w, w * 9 / 16, mime, "enc", mbps * 1_000_000, 30, 30, 90, facing, true, emptySet(), emptySet(), limited)

    private val vivo = listOf(
        p("0", 1920, "video/avc", 5), p("0", 1920, "video/avc", 10), p("0", 1920, "video/avc", 20),
        p("0", 1920, "video/hevc", 5), p("0", 1920, "video/hevc", 10), p("0", 1920, "video/hevc", 20),
        p("0", 3840, "video/avc", 40), p("0", 3840, "video/hevc", 40), p("0", 3840, "video/hevc", 60),
        p("0", 1280, "video/hevc", 5, limited = true),
        p("1", 1920, "video/hevc", 10, CC.LENS_FACING_FRONT), p("1", 1920, "video/hevc", 20, CC.LENS_FACING_FRONT),
    )

    @Test fun bestPrefersLargestHevcHighestBitrate() {
        assertEquals("0/3840x2160/video/hevc/60000000", Quality.key(Quality.pick(vivo, "0", Quality.BEST)!!))
        assertEquals("1/1920x1080/video/hevc/20000000", Quality.key(Quality.pick(vivo, "1", Quality.BEST)!!))
    }
    @Test fun highIs1080pTopBitrate() {
        assertEquals("0/1920x1080/video/hevc/20000000", Quality.key(Quality.pick(vivo, "0", Quality.HIGH)!!))
    }
    @Test fun balancedIs1080pAtTenOrBelow() {
        assertEquals("0/1920x1080/video/hevc/10000000", Quality.key(Quality.pick(vivo, "0", Quality.BALANCED)!!))
        assertEquals("1/1920x1080/video/hevc/10000000", Quality.key(Quality.pick(vivo, "1", Quality.BALANCED)!!))
    }
    @Test fun limitedNeverAutomaticButCustomAllowed() {
        val only = listOf(p("2", 1280, "video/hevc", 5, limited = true))
        assertNull(Quality.pick(only, "2", Quality.BEST))
        assertNotNull(Quality.pick(only, "2", Quality.CUSTOM, "2/1280x720/video/hevc/5000000"))
        assertNull(Quality.pick(vivo, "0", Quality.CUSTOM, "missing"))
    }
    @Test fun smoothPicksSixtyFpsOnlyWhenOffered() {
        fun fast(mime: String, mbps: Int) = Profile("0", 1920, 1080, mime, "enc", mbps * 1_000_000, 120, 120, 90,
            CC.LENS_FACING_BACK, false, emptySet(), emptySet(), false, fps = 60, highSpeed = true)
        assertNull(Quality.pick(vivo, "0", Quality.SMOOTH))
        val withFast = vivo + fast("video/avc", 30) + fast("video/hevc", 20) + fast("video/hevc", 30)
        val smooth = Quality.pick(withFast, "0", Quality.SMOOTH)!!
        assertEquals("0/1920x1080/video/hevc/30000000/60fps", Quality.key(smooth))
        assertEquals("1080p · H.265 · 30 Mb/s · 60 fps · experimental", Quality.describe(smooth))
        // Automatic choices never switch to the experimental high-speed mode.
        for (q in listOf(Quality.BEST, Quality.HIGH, Quality.BALANCED)) assertFalse(Quality.pick(withFast, "0", q)!!.highSpeed)
        assertEquals(smooth, Quality.pick(withFast, "0", Quality.CUSTOM, Quality.key(smooth)))
    }
    @Test fun camerasAreLabelledRearFirst() {
        val extra = vivo + p("3", 1920, "video/hevc", 10)
        assertEquals(listOf("0" to "Rear", "3" to "Rear 2", "1" to "Front"), Quality.cameras(extra))
    }
}
