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

class FrameFlagsTest {
    @org.junit.Test fun flagsPackKeyTurnsAndMirror() {
        fun f(key: Boolean, turns: Int, mirror: Boolean) = LiveTransport.flags(LiveQueue.Frame(ByteArray(1), 0, 0, key, turns, mirror))
        org.junit.Assert.assertEquals(0, f(false, 0, false))
        org.junit.Assert.assertEquals(1 or (3 shl 1), f(true, 3, false))
        org.junit.Assert.assertEquals(8 or (1 shl 1), f(false, 1, true))
        org.junit.Assert.assertEquals(15, f(true, 3, true))
    }
}

class StopReasonTest {
    @Test fun macClosingIsExplained() {
        assertTrue(LiveTransport.stopReason(java.io.IOException("write failed: EPIPE (Broken pipe)")).startsWith("OBS stopped receiving"))
        assertTrue(LiveTransport.stopReason(java.net.SocketException("Connection reset")).startsWith("OBS stopped receiving"))
    }
    @Test fun otherErrorsKeepTheirMessage() =
        assertEquals("Live stopped: Encoded packet exceeds the 4 MiB limit", LiveTransport.stopReason(IllegalArgumentException("Encoded packet exceeds the 4 MiB limit")))
}
