package dev.phonecam.obs

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WifiLinkTest {
    @Test fun parsesTheQrLinkFromObs() {
        val link = PairingLink.parse("phonecam://pair?v=1&id=0123456789abcdef&c=000G40R40M30E209&p=47183&a=192.168.1.143,10.0.0.2&n=Vara%27s%20MacBook%20Air")!!
        assertEquals("0123456789abcdef", link.macId)
        assertArrayEquals(ByteArray(10) { it.toByte() }, link.secret)
        assertEquals(47183, link.port)
        assertEquals(listOf("192.168.1.143", "10.0.0.2"), link.addresses)
        assertEquals("Vara's MacBook Air", link.name)
    }
    @Test fun rejectsAnythingElse() {
        val good = "phonecam://pair?v=1&id=0123456789abcdef&c=000G40R40M30E209&p=47183&a=192.168.1.143&n=Mac"
        assertNotNull(PairingLink.parse(good))
        listOf("https://example.com", good.replace("v=1", "v=2"), good.replace("id=0123456789abcdef", "id=XYZ"),
            good.replace("c=000G40R40M30E209", "c=123456"), good.replace("p=47183", "p=70000"), good + "&x=" + "a".repeat(600))
            .forEach { assertNull(it, PairingLink.parse(it)) }
        // Bad addresses are dropped rather than trusted.
        assertEquals(listOf("10.0.0.2"), PairingLink.parse(good.replace("a=192.168.1.143", "a=999.1.1.1,10.0.0.2,evil.example"))!!.addresses)
    }
    @Test fun storeKeepsPairingsAndPhoneId() {
        val file = Files.createTempFile("pairings", ".txt").toFile().also { it.delete() }
        val first = WifiPairings(file)
        assertTrue(WifiPairings.isId(first.phoneId))
        first.remember(PairedMac("0123456789abcdef", "Mac with spaces & symbols", ByteArray(32) { 7 }, 47183, listOf("192.168.1.143")))
        first.remember(PairedMac("0123456789abcdef", "Renamed", ByteArray(32) { 9 }, 50000, emptyList()))  // same Mac replaces
        val again = WifiPairings(file)
        assertEquals(first.phoneId, again.phoneId)
        val mac = again.macs.single()
        assertEquals("Renamed", mac.name); assertEquals(50000, mac.port); assertTrue(mac.addresses.isEmpty())
        assertArrayEquals(ByteArray(32) { 9 }, mac.key)
        again.forgetAll()
        assertTrue(WifiPairings(file).macs.isEmpty())
        file.writeText("garbage\nmac nonsense")
        assertTrue(WifiPairings(file).macs.isEmpty())   // unreadable store starts fresh
    }
}
