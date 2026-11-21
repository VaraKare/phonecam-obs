package dev.phonecam.obs

import android.app.Activity
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics

/**
 * Screen sharing (0.6, owner's idea of 21 Nov 2026): the phone's screen instead of the camera, through the same
 * encoder and connection to OBS. Android asks the user's consent every time; nothing is stored on the phone.
 */
object ScreenSource {
    const val ID = "screen"
    const val BITRATE = 12_000_000  // sharp text at 1080p without filling a busy Wi-Fi link

    /** The screen's full size in pixels, in its current orientation. */
    fun displaySize(activity: Activity): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else DisplayMetrics().also { @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }

    /** Scales [width]×[height] so the long side is at most [longest], keeping the shape; even sizes for the encoder. */
    fun scaled(width: Int, height: Int, longest: Int): Pair<Int, Int> {
        val scale = minOf(1.0, longest.toDouble() / maxOf(width, height))
        fun even(v: Double) = (v.toInt() / 2 * 2).coerceAtLeast(2)
        return even(width * scale) to even(height * scale)
    }

    /**
     * A profile for the screen at its current shape: a hardware encoder (H.265 first) that accepts the size,
     * trying a smaller size if needed. Null when the phone has none.
     */
    fun profile(width: Int, height: Int): Profile? {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            it.isEncoder && it.isHardwareAccelerated
        }
        for (longest in listOf(1920, 1600, 1280)) {
            val (w, h) = scaled(width, height, longest)
            for (mime in listOf("video/hevc", "video/avc")) for (encoder in encoders) {
                if (!encoder.supportedTypes.contains(mime)) continue
                val video = runCatching { encoder.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: continue
                if (!video.isSizeSupported(w, h)) continue
                return Profile(ID, w, h, mime, encoder.name, BITRATE, 30, 30, 0, -1, false, emptySet(), emptySet(), false)
            }
        }
        return null
    }

    /** Camera controls don't apply to the screen. */
    val noAbilities = Abilities(1f, false, 0, 0, 0f, emptyList(), false, false, false, 0, 0)
}
