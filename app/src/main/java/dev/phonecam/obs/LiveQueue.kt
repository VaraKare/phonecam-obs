package dev.phonecam.obs

/** Dropping a dependent frame invalidates the queued GOP. Resume only at a keyframe. */
class LiveQueue(private val maxFrames: Int = 2, private val maxBytes: Int = 8 * 1024 * 1024) {
    /** [quarterTurns] is the clockwise rotation (0..3 × 90°) the receiver applies to show this frame upright; [mirror] flips it after that. */
    data class Frame(val bytes: ByteArray, val sequence: Long, val ptsUs: Long, val key: Boolean, val quarterTurns: Int = 0, val mirror: Boolean = false)
    private val frames = ArrayDeque<Frame>()
    private var bytes = 0
    private var waitingForKey = true
    @Volatile var dropped: Long = 0; private set

    @Synchronized fun offer(frame: Frame): Boolean {
        if (frame.bytes.size > maxBytes) {
            dropped += frames.size + 1; frames.clear(); bytes = 0; waitingForKey = true
            return false
        }
        if (frames.size >= maxFrames || bytes + frame.bytes.size > maxBytes) {
            dropped += frames.size; frames.clear(); bytes = 0; waitingForKey = true
        }
        if (waitingForKey && !frame.key) { dropped++; return false }
        if (frame.key) waitingForKey = false
        frames.addLast(frame); bytes += frame.bytes.size
        return true
    }
    @Synchronized fun poll(): Frame? = frames.removeFirstOrNull()?.also { bytes -= it.bytes.size }
}
