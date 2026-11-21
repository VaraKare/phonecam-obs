package dev.phonecam.obs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlTest {
    @Test fun controlMessagesFromObsAreRead() {
        val body = "camera next".toByteArray()
        val message = LiveTransport.header(LiveTransport.CONTROL, 0, 0, 0, body.size) + body
        assertEquals("camera next", LiveTransport.controlText(message))
        // A frame, a wrong length or an oversized message is not a command.
        assertNull(LiveTransport.controlText(LiveTransport.header(2, 1, 1, 1, body.size) + body))
        assertNull(LiveTransport.controlText(LiveTransport.header(LiveTransport.CONTROL, 0, 0, 0, body.size + 1) + body))
        val big = ByteArray(LiveTransport.MAX_CONTROL + 1)
        assertNull(LiveTransport.controlText(LiveTransport.header(LiveTransport.CONTROL, 0, 0, 0, big.size) + big))
        assertNull(LiveTransport.controlText(ByteArray(10)))
    }
    @Test fun batteryWarnsOncePerStepAndNotWhileCharging() {
        val shown = mutableSetOf<Int>()
        assertNull(Warnings.batteryStep(25, false, shown))
        assertEquals(20, Warnings.batteryStep(20, false, shown)); shown += 20
        assertNull(Warnings.batteryStep(18, false, shown))
        assertEquals(10, Warnings.batteryStep(9, false, shown))   // skipped 15: the lowest step reached
        assertNull(Warnings.batteryStep(4, true, shown))          // charging
        assertNull(Warnings.batteryStep(-1, false, shown))        // unknown
    }
}
