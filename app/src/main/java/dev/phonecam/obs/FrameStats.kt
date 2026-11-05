package dev.phonecam.obs

/** Bounded statistics: no retained frames and at most 512 recent gaps. */
class FrameStats(private val capacity: Int = 512) {
    init { require(capacity > 0) }
    private val gapsUs = LongArray(capacity)
    private var gapCount = 0L
    var frames = 0L; private set
    var firstPtsUs: Long? = null; private set
    var lastPtsUs: Long? = null; private set
    var maxGapUs = 0L; private set
    var nonMonotonic = 0L; private set

    fun add(ptsUs: Long) {
        require(ptsUs >= 0)
        val previous = lastPtsUs
        if (previous != null) {
            val gap = ptsUs - previous
            if (gap <= 0) nonMonotonic++
            else {
                gapsUs[(gapCount % capacity).toInt()] = gap
                gapCount++
                maxGapUs = maxOf(maxGapUs, gap)
            }
        }
        if (firstPtsUs == null) firstPtsUs = ptsUs
        lastPtsUs = ptsUs
        frames++
    }

    val durationUs: Long get() = (lastPtsUs ?: 0) - (firstPtsUs ?: 0)
    val fps: Double get() = if (durationUs > 0 && frames > 1) (frames - 1) * 1_000_000.0 / durationUs else 0.0
    val retainedGaps: Int get() = minOf(gapCount, capacity.toLong()).toInt()
    fun recentP95GapUs(): Long {
        val n = retainedGaps
        if (n == 0) return 0
        val sorted = gapsUs.copyOf(n).sorted()
        return sorted[kotlin.math.ceil(n * 0.95).toInt() - 1]
    }
}
