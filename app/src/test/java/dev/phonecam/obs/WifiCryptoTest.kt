package dev.phonecam.obs

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Same reference numbers as native/wifi_test.cpp (independent Python + openssl computation). */
class WifiCryptoTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private val secret = ByteArray(10) { it.toByte() }

    @Test fun pairingCodes() {
        assertEquals("000G40R40M30E209", WifiCrypto.encodeCode(secret))
        assertEquals("000G-40R4-0M30-E209", WifiCrypto.formatCode("000G40R40M30E209"))
        assertArrayEquals(secret, WifiCrypto.decodeCode("000g-40r4 0m30-e2o9"))
        assertNull(WifiCrypto.decodeCode("000G40R40M30E20"))
        assertNull(WifiCrypto.decodeCode("000G40R40M30E20U"))
        val all = ByteArray(10) { 0xFF.toByte() }
        assertArrayEquals(all, WifiCrypto.decodeCode(WifiCrypto.encodeCode(all)))
        val zero = ByteArray(10)
        assertArrayEquals(zero, WifiCrypto.decodeCode(WifiCrypto.encodeCode(zero)))
    }
    @Test fun handshakeAndRecordMatchTheMac() {
        val key = WifiCrypto.codeKey(secret)
        assertArrayEquals(hex("afcbadc63d79cf9d9edf4fb8198b8746c27f375fdd84d0c74ef153e3d10ca33e"), key)
        val h = WifiCrypto.Handshake(key, ByteArray(32) { 0x11 }, ByteArray(250) { it.toByte() })
        assertArrayEquals(hex("c4404f67efd6ec3b22b9a2e1e34f073a6732e2af7f22dbd90edf7d96dfa8c948"), h.tagM)
        assertArrayEquals(hex("070ca3bb56d1d5da42008dbeda1f30bce18138256f1937a434af9ce57da85d2c"), h.tagP)
        val k = h.keys()
        assertArrayEquals(hex("a9172d591c753806e60a4c25c159d4513e75695c919562ecdb3186f46f70a19c"), k.encryption)
        assertArrayEquals(hex("b3e93ce8193295b2c4cbe362e80d66e22cd3cf7861eff256c3cb8653aa59dc27"), k.authentication)
        assertArrayEquals(hex("3d74719c074ee80437347c9d848fa0b545a50394dfd903d43a16357519d53e8b"), k.longTerm)

        val sealer = WifiCrypto.Sealer(k)
        val sink = ByteArrayOutputStream()
        repeat(5) { sealer.seal(ByteArrayOutputStream(), byteArrayOf(1)) }   // records 0..4
        val plain = "PhoneCam test record, longer than one AES block.".toByteArray()
        sealer.seal(sink, plain.copyOfRange(0, 20), plain.copyOfRange(20, plain.size))  // record 5, split like header + payload
        assertArrayEquals(hex("000000302fc5f85429eaf481ef85d104d2faf55fb1f3de553bc17cff71b5f79a66c1bda81866006979d9d35f04da4c57cc9e8be2c4d9b16d459d31e3e634e6a5718e4bd8"), sink.toByteArray())
    }
    @Test fun ecdhAgreesAndRejectsBadPoints() {
        val a = WifiCrypto.Ephemeral(); val b = WifiCrypto.Ephemeral()
        assertEquals(65, a.publicKey.size)
        assertArrayEquals(a.shared(b.publicKey), b.shared(a.publicKey))
        val bad = a.publicKey.copyOf().also { it[40] = (it[40].toInt() xor 0x55).toByte() }
        assertThrows(Exception::class.java) { b.shared(bad) }
    }
}
