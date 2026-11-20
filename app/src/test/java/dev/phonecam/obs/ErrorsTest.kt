package dev.phonecam.obs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class ErrorsTest {
    @Test fun refusedConnectionNamesTheNextStepWithoutAddresses() {
        val raw = ConnectException("failed to connect to /192.168.1.78 (port 47183) from /192.168.1.52 (port 36156) after 3000ms: isConnected failed: ECONNREFUSED (Connection refused)")
        val text = Errors.friendly(raw, "Kare's laptop")
        assertTrue(text, text.startsWith("Kare's laptop refused the connection"))
        assertFalse(text, Regex("[0-9]{2,}").containsMatchIn(text))
    }
    @Test fun wrappedTimeoutAndDefaults() {
        assertTrue(Errors.friendly(IOException("x", SocketTimeoutException("connect timed out"))).startsWith("The computer didn't answer"))
        assertEquals("Wrong pairing code.", Errors.friendly(WifiClient.Refused("Wrong pairing code.")))
        assertTrue(Errors.friendly(null).startsWith("Pairing didn't work"))
    }
}
