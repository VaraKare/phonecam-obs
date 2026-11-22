package dev.phonecam.obs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTest {
    private fun le(vararg samples: Int) = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, s -> b[2 * i] = s.toByte(); b[2 * i + 1] = (s shr 8).toByte() }
    }
    @Test fun mixAddsAndClipsInsteadOfWrapping() {
        val into = le(1000, -1000, 30000, -30000)
        AudioSender.mix(into, le(234, -234, 10000, -10000), into.size)
        assertArrayEquals(le(1234, -1234, 32767, -32768), into)
    }
    @Test fun audioBodyHasRateChannelsAndFormat() {
        val body = AudioSender.body(le(1, 2), 4)
        assertEquals(12, body.size)
        assertArrayEquals(byteArrayOf(0, 0, 0xBB.toByte(), 0x80.toByte(), 0, 1, 0, 1), body.copyOfRange(0, 8))
    }
    @Test fun ptsFollowsTheTimestampPair() {
        assertEquals(1_000_000_000L + 10_000_000L, AudioSender.ptsNs(480, 0, 1_000_000_000L))
    }
}
