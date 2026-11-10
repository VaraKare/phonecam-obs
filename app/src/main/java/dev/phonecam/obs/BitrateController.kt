package dev.phonecam.obs

/**
 * Keeps live video flowing when the link to OBS (Wi-Fi especially) can't carry the chosen bitrate.
 *
 * Called once a second with the total number of dropped frames. While frames are being dropped it
 * lowers the encoder bitrate to 70%, at most every 2 seconds, never below [minimum]. After a calm
 * period it raises it by 15% towards [requested], never above it. If raising caused drops again
 * soon after, the calm period needed next time doubles (up to a minute), so it settles instead of
 * oscillating. Resolution and frame rate never change, and the phone shows the current bitrate
 * whenever it is below the user's choice (requested and actual settings are reported separately).
 */
class BitrateController(val requested: Int, val minimum: Int = defaultMinimum(requested)) {
    companion object {
        /** A sixth of the choice, but at least 2 Mb/s. */
        fun defaultMinimum(requested: Int) = maxOf(2_000_000, requested / 6).coerceAtMost(requested)
        private fun roundMbps(value: Double) = (Math.round(value / 1_000_000) * 1_000_000).toInt()
    }
    init { require(requested > 0 && minimum in 1..requested) }

    var current = requested; private set
    /** True after several seconds of drops while already at [minimum]: lower the quality or use the cable. */
    var starved = false; private set
    val limited: Boolean get() = current < requested

    private var lastDrops = -1L
    private var cooldown = 0
    private var calm = 0
    private var calmNeeded = 10
    private var sinceRaise = Int.MAX_VALUE
    private var droppingAtMinimum = 0

    /** Returns the new bitrate when it should change now, otherwise null. */
    fun update(totalDrops: Long): Int? {
        val fresh = if (lastDrops < 0) 0 else totalDrops - lastDrops
        lastDrops = totalDrops
        if (cooldown > 0) cooldown--
        if (sinceRaise != Int.MAX_VALUE) sinceRaise++
        if (fresh > 0) {
            calm = 0
            if (current <= minimum) {
                starved = ++droppingAtMinimum >= 5
                return null
            }
            if (cooldown > 0) return null
            if (sinceRaise <= 5) calmNeeded = minOf(60, calmNeeded * 2)
            cooldown = 2
            current = maxOf(minimum, minOf(current - 1_000_000, roundMbps(current * 0.7)))
            return current
        }
        droppingAtMinimum = 0; starved = false
        if (current < requested && ++calm >= calmNeeded) {
            calm = 0; sinceRaise = 0
            current = minOf(requested, maxOf(current + 1_000_000, roundMbps(current * 1.15)))
            return current
        }
        return null
    }
}
