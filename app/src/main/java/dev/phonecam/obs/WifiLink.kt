package dev.phonecam.obs

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

/** A Mac this phone has paired with for direct Wi-Fi (docs/WIFI.md). [key] is the long-term key. */
class PairedMac(val id: String, val name: String, val key: ByteArray, val port: Int, val addresses: List<String>)

/**
 * Pairings in app-private storage (excluded from backups): this phone's id and the paired Macs.
 * A small line format instead of JSON so the unit tests run it on a plain JVM.
 */
class WifiPairings(private val file: File) {
    var phoneId: String = ""; private set
    var macs: List<PairedMac> = emptyList(); private set
    init { load() }

    private fun load() {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.firstOrNull() == "phonecam-wifi 1") {
            phoneId = lines.firstOrNull { it.startsWith("phone ") }?.substringAfter(' ')?.takeIf(::isId).orEmpty()
            macs = lines.filter { it.startsWith("mac ") }.mapNotNull(::parseMac)
        }
        if (phoneId.isEmpty()) { phoneId = hex(WifiCrypto.randomBytes(8)); macs = emptyList(); save() }
    }
    private fun parseMac(line: String): PairedMac? {
        val parts = line.split(' ')
        if (parts.size != 6 || !isId(parts[1]) || parts[2].length != 64) return null
        val key = runCatching { unhex(parts[2]) }.getOrNull() ?: return null
        val port = parts[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val addresses = if (parts[4] == "-") emptyList() else parts[4].split(',').filter(PairingLink::isIpv4).take(4)
        return PairedMac(parts[1], URLDecoder.decode(parts[5], "UTF-8"), key, port, addresses)
    }
    private fun save() {
        val text = buildString {
            appendLine("phonecam-wifi 1"); appendLine("phone $phoneId")
            macs.forEach { m -> appendLine("mac ${m.id} ${hex(m.key)} ${m.port} ${m.addresses.joinToString(",").ifEmpty { "-" }} ${URLEncoder.encode(m.name, "UTF-8")}") }
        }
        val temporary = File(file.parentFile, file.name + ".new")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) { file.writeText(text); temporary.delete() }
    }
    @Synchronized fun remember(mac: PairedMac) { macs = macs.filter { it.id != mac.id } + mac; save() }
    @Synchronized fun forgetAll() { macs = emptyList(); save() }

    companion object {
        fun isId(text: String) = text.length == 16 && text.all { it in '0'..'9' || it in 'a'..'f' }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

/** Contents of the QR code OBS shows: phonecam://pair?v=1&id=…&c=…&p=…&a=…&n=… */
class PairingLink(val macId: String, val secret: ByteArray, val port: Int, val addresses: List<String>, val name: String) {
    companion object {
        private val ipv4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")
        fun isIpv4(text: String) = ipv4.matches(text)
        fun parse(text: String): PairingLink? {
            if (!text.startsWith("phonecam://pair?") || text.length > 512) return null
            val q = text.substringAfter('?').split('&').mapNotNull { part ->
                part.indexOf('=').takeIf { it > 0 }?.let { part.substring(0, it) to part.substring(it + 1) }
            }.toMap()
            if (q["v"] != "1") return null
            val id = q["id"]?.takeIf(WifiPairings::isId) ?: return null
            val secret = q["c"]?.let(WifiCrypto::decodeCode) ?: return null
            val port = q["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val addresses = q["a"].orEmpty().split(',').filter(::isIpv4).take(4)
            val name = runCatching { URLDecoder.decode(q["n"].orEmpty(), "UTF-8") }.getOrDefault("").take(64).ifEmpty { "Mac" }
            return PairingLink(id, secret, port, addresses, name)
        }
    }
}

/** The phone's side of the direct Wi-Fi handshake and records (docs/WIFI.md). Plain JVM sockets. */
object WifiClient {
    class Refused(message: String) : IOException(message)

    fun connect(host: String, port: Int, timeoutMs: Int = 1500): Socket = Socket().apply {
        try { connect(InetSocketAddress(host, port), timeoutMs); soTimeout = 5_000; tcpNoDelay = true }
        catch (e: Exception) { runCatching { close() }; throw e }
    }

    /** Pairs using the code from OBS; returns the new long-term key. Closes the socket. */
    fun pair(socket: Socket, macId: String, phoneId: String, secret: ByteArray, phoneName: String): ByteArray =
        socket.use { handshake(it, 2, macId, phoneId, WifiCrypto.codeKey(secret), phoneName).longTerm }

    /** Opens an encrypted live session with a paired Mac. */
    fun open(socket: Socket, macId: String, phoneId: String, key: ByteArray): Session =
        try { Session(socket, handshake(socket, 1, macId, phoneId, key, "")) } catch (e: Exception) { runCatching { socket.close() }; throw e }

    private fun handshake(socket: Socket, mode: Int, macId: String, phoneId: String, key: ByteArray, name: String): WifiCrypto.Keys {
        val mine = WifiCrypto.Ephemeral()
        val hello = "PCW1".toByteArray() + byteArrayOf(mode.toByte()) + WifiPairings.unhex(macId) + WifiPairings.unhex(phoneId) +
            mine.publicKey + WifiCrypto.randomBytes(32)
        check(hello.size == WifiCrypto.CLIENT_HELLO_BYTES)
        val output = socket.getOutputStream(); val input = DataInputStream(socket.getInputStream())
        output.write(hello); output.flush()
        val reply = ByteArray(WifiCrypto.SERVER_HELLO_BYTES)
        input.readFully(reply, 0, 5)
        if (!reply.copyOfRange(0, 4).contentEquals("PCW1".toByteArray())) throw IOException("Not a PhoneCam OBS source")
        when (reply[4].toInt()) {
            0 -> Unit
            1 -> throw Refused(if (mode == 2) "This OBS source changed; scan its new QR code." else "This Mac no longer knows this phone. Pair again in Settings.")
            2 -> throw Refused("OBS isn't showing a pairing code right now. Click Pair a phone over Wi-Fi in the PhoneCam source.")
            else -> throw Refused("OBS is busy with another phone.")
        }
        input.readFully(reply, 5, reply.size - 5)
        val shake = WifiCrypto.Handshake(key, mine.shared(reply.copyOfRange(5, 70)), hello + reply.copyOfRange(0, 102))
        if (!WifiCrypto.equal(shake.tagM, reply.copyOfRange(102, 134)))
            throw Refused(if (mode == 2) "Wrong pairing code." else "This Mac failed the security check. Pair again in Settings.")
        var shortName = name
        while (shortName.toByteArray().size > 64) shortName = shortName.dropLast(1)  // whole characters only
        val nameBytes = shortName.toByteArray()
        output.write(shake.tagP + byteArrayOf(nameBytes.size.toByte()) + nameBytes); output.flush()
        if (input.readUnsignedByte() != 0) throw Refused("OBS refused the connection.")
        return shake.keys()
    }

    /** Sends PCAM v3 messages as encrypted records. */
    class Session(private val socket: Socket, keys: WifiCrypto.Keys) {
        private val sealer = WifiCrypto.Sealer(keys)
        private val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val address: String get() = socket.inetAddress?.hostAddress.orEmpty()
        fun send(header: ByteArray, payload: ByteArray) { sealer.seal(output, header, payload); output.flush() }
        fun close() { runCatching { socket.close() } }
    }
}
