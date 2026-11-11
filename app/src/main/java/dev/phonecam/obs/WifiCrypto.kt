package dev.phonecam.obs

import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Direct Wi-Fi security, the phone's half of docs/WIFI.md: pairing codes, P-256 ECDH, the handshake
 * tags and keys, and AES-256-CTR + HMAC records. Plain JVM crypto, so the unit tests run it unchanged;
 * the tests check the same reference numbers as the Mac's native/wifi_test.cpp.
 */
object WifiCrypto {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CODE_BYTES = 10
    const val PUBLIC_KEY_BYTES = 65
    const val CLIENT_HELLO_BYTES = 118
    const val SERVER_HELLO_BYTES = 134
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)
    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }
    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach(::update); doFinal() }
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); var block = ByteArray(0); var counter = 1
        while (out.size() < length) { block = hmac(prk, block, info, byteArrayOf(counter++.toByte())); out.write(block) }
        return out.toByteArray().copyOf(length)
    }
    fun equal(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    fun encodeCode(secret: ByteArray): String {
        require(secret.size == CODE_BYTES)
        val bits = BigInteger(1, secret)
        return (0 until 16).joinToString("") { ALPHABET[bits.shiftRight(75 - 5 * it).toInt() and 31].toString() }
    }
    /** Ignores case, spaces and dashes; reads I/L as 1 and O as 0. Null for anything else. */
    fun decodeCode(text: String): ByteArray? {
        val clean = text.uppercase().filter { it != ' ' && it != '-' }.map { when (it) { 'I', 'L' -> '1'; 'O' -> '0'; else -> it } }
        if (clean.size != 16 || clean.any { it !in ALPHABET }) return null
        var bits = BigInteger.ZERO
        clean.forEach { bits = bits.shiftLeft(5).or(BigInteger.valueOf(ALPHABET.indexOf(it).toLong())) }
        val raw = bits.toByteArray()
        return ByteArray(CODE_BYTES).also { out ->
            val take = minOf(raw.size, CODE_BYTES)
            System.arraycopy(raw, raw.size - take, out, CODE_BYTES - take, take)
        }
    }
    fun formatCode(code: String) = code.chunked(4).joinToString("-")
    fun codeKey(secret: ByteArray) = sha256("PhoneCam pairing code v1".toByteArray(), secret)

    /** Ephemeral P-256 key: [publicKey] is the 65-byte uncompressed point, [shared] the 32-byte ECDH x coordinate. */
    class Ephemeral {
        private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val publicKey: ByteArray get() {
            val w = (pair.public as ECPublicKey).w
            return byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)
        }
        /** Throws for a malformed or off-curve point, which ends the handshake. */
        fun shared(peer: ByteArray): ByteArray {
            require(peer.size == PUBLIC_KEY_BYTES && peer[0] == 4.toByte()) { "Invalid session key from OBS" }
            val params = (pair.public as ECPublicKey).params
            val point = ECPoint(BigInteger(1, peer.copyOfRange(1, 33)), BigInteger(1, peer.copyOfRange(33, 65)))
            val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
            return KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(key, true); generateSecret() }
        }
        private fun fixed(v: BigInteger): ByteArray { val b = v.toByteArray(); return ByteArray(32).also { System.arraycopy(b, maxOf(0, b.size - 32), it, maxOf(0, 32 - b.size), minOf(32, b.size)) } }
    }

    class Keys(val encryption: ByteArray, val authentication: ByteArray, val longTerm: ByteArray)
    /** t1 = ClientHello || ServerHello without tagM. */
    class Handshake(key: ByteArray, z: ByteArray, private val t1: ByteArray) {
        private val prk = hmac(key, z)
        val tagM: ByteArray = hmac(prk, "pcam mac".toByteArray(), sha256(t1))
        val tagP: ByteArray = hmac(prk, "pcam phone".toByteArray(), sha256(t1, tagM))
        fun keys(): Keys {
            val h = sha256(t1, tagM, tagP)
            val material = hkdfExpand(prk, "pcam keys".toByteArray() + h, 64)
            return Keys(material.copyOfRange(0, 32), material.copyOfRange(32, 64), hkdfExpand(prk, "pcam pairing".toByteArray() + h, 32))
        }
    }

    /** Writes length || AES-256-CTR(header || payload) || tag for one record, without copying the payload. */
    class Sealer(private val keys: Keys) {
        private var record = 0L
        private val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keys.authentication, "HmacSHA256")) }
        fun seal(out: OutputStream, vararg parts: ByteArray) {
            val iv = ByteArray(16).also { b -> for (i in 0 until 8) b[i] = (record ushr (56 - 8 * i)).toByte() }
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encryption, "AES"), IvParameterSpec(iv))
            val length = parts.sumOf { it.size }
            val prefix = java.nio.ByteBuffer.allocate(12).putLong(record).putInt(length).array()
            mac.update(prefix)
            out.write(prefix, 8, 4)
            parts.forEachIndexed { index, part ->
                val encrypted = if (index == parts.lastIndex) cipher.doFinal(part) else cipher.update(part)
                if (encrypted != null) { mac.update(encrypted); out.write(encrypted) }
            }
            out.write(mac.doFinal(), 0, 16)
            record++
        }
    }
}
