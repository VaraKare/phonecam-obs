package dev.phonecam.obs

import android.hardware.camera2.CameraCharacteristics as CC

/** Simple choices on the main screen. Every choice maps to one scanned, declared profile. */
enum class Quality(val label: String) {
    BEST("Best"), HIGH("High"), BALANCED("Balanced"), CUSTOM("Custom");

    companion object {
        /**
         * BEST: largest size, H.265 preferred, highest bitrate. HIGH: 1080p, highest bitrate.
         * BALANCED: 1080p at 10 Mb/s or the nearest lower. Limited profiles are never chosen
         * automatically. Returns null when the camera has no suitable profile.
         */
        fun pick(profiles: List<Profile>, cameraId: String, quality: Quality, customKey: String? = null): Profile? {
            val camera = profiles.filter { it.cameraId == cameraId }
            if (quality == CUSTOM) return camera.firstOrNull { key(it) == customKey }
            val usable = camera.filter { !it.limited }
            val hevcFirst = compareBy<Profile> { it.mime == "video/hevc" }
            return when (quality) {
                BEST -> usable.maxWithOrNull(compareBy<Profile> { it.width * it.height }.then(hevcFirst).thenBy { it.bitrate })
                HIGH -> usable.filter { it.width == 1920 }.maxWithOrNull(hevcFirst.thenBy { it.bitrate })
                BALANCED -> usable.filter { it.width == 1920 }
                    .sortedWith(compareBy<Profile> { if (it.bitrate <= 10_000_000) 0 else 1 }
                        .thenByDescending { if (it.bitrate <= 10_000_000) it.bitrate else -it.bitrate }
                        .thenByDescending { it.mime == "video/hevc" })
                    .firstOrNull()
                CUSTOM -> null
            }
        }

        fun key(p: Profile) = "${p.cameraId}/${p.width}x${p.height}/${p.mime}/${p.bitrate}"

        /** Rear cameras first, then front, then others; repeated facings are numbered. */
        fun cameras(profiles: List<Profile>): List<Pair<String, String>> {
            val ids = profiles.map { it.cameraId to it.facing }.distinct()
                .sortedWith(compareBy<Pair<String, Int>> { when (it.second) { CC.LENS_FACING_BACK -> 0; CC.LENS_FACING_FRONT -> 1; else -> 2 } }
                    .thenBy { it.first.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.first })
            val seen = mutableMapOf<String, Int>()
            return ids.map { (id, facing) ->
                val base = when (facing) { CC.LENS_FACING_BACK -> "Rear"; CC.LENS_FACING_FRONT -> "Front"; else -> "External" }
                val n = (seen[base] ?: 0) + 1; seen[base] = n
                id to if (n == 1) base else "$base $n"
            }
        }

        fun describe(p: Profile): String {
            val size = when (p.width) { 3840 -> "4K"; 1920 -> "1080p"; 1280 -> "720p"; else -> "${p.width}×${p.height}" }
            return "$size · ${if (p.mime == "video/avc") "H.264" else "H.265"} · ${p.bitrate / 1_000_000} Mb/s${if (p.limited) " · limited" else ""}"
        }
    }
}
