package dev.phonecam.obs

import org.junit.Assert.*
import org.junit.Test

class LiveQueueTest {
    private fun frame(seq: Long, key: Boolean = false, size: Int = 2) = LiveQueue.Frame(ByteArray(size), seq, seq * 33333, key)
    @Test fun requiresKeyAtStartup() {
        val q = LiveQueue()
        assertFalse(q.offer(frame(0)))
        assertTrue(q.offer(frame(1, true)))
        assertEquals(1L, q.poll()!!.sequence)
        assertEquals(1L, q.dropped)
    }
    @Test fun overflowDiscardsDependentGopUntilKey() {
        val q = LiveQueue()
        q.offer(frame(0, true)); q.offer(frame(1))
        assertFalse(q.offer(frame(2)))
        assertNull(q.poll())
        assertFalse(q.offer(frame(3)))
        assertTrue(q.offer(frame(4, true)))
        assertTrue(q.offer(frame(5)))
        assertEquals(4L, q.poll()!!.sequence)
        assertEquals(5L, q.poll()!!.sequence)
        assertEquals(4L, q.dropped)
    }
    @Test fun byteBudgetAlsoRequiresRecovery() {
        val q = LiveQueue(maxFrames = 10, maxBytes = 4)
        q.offer(frame(0, true, 3))
        assertFalse(q.offer(frame(1, size = 2)))
        assertNull(q.poll())
        assertFalse(q.offer(frame(2, true, 5)))
        assertFalse(q.offer(frame(3)))
        assertTrue(q.offer(frame(4, true)))
    }
    @Test fun keyframeCanRecoverImmediatelyAtOverflow() {
        val q = LiveQueue(maxFrames = 1)
        q.offer(frame(0, true))
        assertTrue(q.offer(frame(1, true)))
        assertEquals(1L, q.poll()!!.sequence)
        assertNull(q.poll())
    }
}
