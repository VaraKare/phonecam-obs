package dev.phonecam.obs

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * Phone ⇄ Mac interoperability without a phone: tools/test_native.py starts the real Mac Wi-Fi server
 * (phonecam-receive --wifi) and runs this test with PHONECAM_WIFI_PEER="host port macId code" and
 * PHONECAM_WIFI_PACKETS (a file of plain PCAM v3 messages). Skipped in ordinary unit-test runs.
 */
class WifiInteropTest {
    @Test fun pairThenStreamToTheMacServer() {
        val peer = System.getenv("PHONECAM_WIFI_PEER"); val packets = System.getenv("PHONECAM_WIFI_PACKETS")
        assumeTrue("Run through tools/test_native.py", peer != null && packets != null)
        val (host, port, macId, code) = peer!!.split(' ')
        val store = WifiPairings(Files.createTempFile("pairings", ".txt").toFile().also { it.delete() })

        // A wrong code is refused and does not pair.
        val wrong = WifiCrypto.decodeCode(code)!!.copyOf().also { it[9] = (it[9] + 1).toByte() }
        assertThrows(WifiClient.Refused::class.java) {
            WifiClient.pair(WifiClient.connect(host, port.toInt()), macId, store.phoneId, wrong, "Test phone")
        }
        val key = WifiClient.pair(WifiClient.connect(host, port.toInt()), macId, store.phoneId, WifiCrypto.decodeCode(code)!!, "Test phone")
        assertEquals(32, key.size)
        store.remember(PairedMac(macId, "Test Mac", key, port.toInt(), listOf(host)))

        // The pairing window closed after one success: pairing again is refused.
        assertThrows(WifiClient.Refused::class.java) {
            WifiClient.pair(WifiClient.connect(host, port.toInt()), macId, store.phoneId, WifiCrypto.decodeCode(code)!!, "Test phone")
        }
        val mac = WifiPairings(storeFile(store)).macs.single()
        val session = WifiClient.open(WifiClient.connect(host, mac.port), mac.id, store.phoneId, mac.key)
        val data = File(packets!!).readBytes()
        var offset = 0
        while (offset < data.size) {
            val length = ByteBuffer.wrap(data, offset + 8, 4).int
            session.send(data.copyOfRange(offset, offset + 32), data.copyOfRange(offset + 32, offset + 32 + length))
            offset += 32 + length
        }
        Thread.sleep(1500)  // let the Mac decode before closing
        session.close()
    }
    private fun storeFile(store: WifiPairings): File =
        WifiPairings::class.java.getDeclaredField("file").apply { isAccessible = true }.get(store) as File
}
