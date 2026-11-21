package dev.phonecam.obs

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sends the live stream to OBS over whichever connection authenticates first: the local abstract
 * socket (USB or Wireless debugging, forwarded by ADB) or direct Wi-Fi to a paired Mac (docs/WIFI.md).
 * The other attempt then stops. [connected] receives "USB" or "Wi-Fi".
 */
class LiveTransport(private val token: String, private val connected: (String) -> Unit,
                    private val failed: (String) -> Unit, private val wifi: WifiConnector? = null) {
    /** One connection to OBS. [writeTimeoutMs] bounds a single message write before the session is called stalled. */
    private abstract class Sink(val route: String, val writeTimeoutMs: Long) {
        abstract fun write(header: ByteArray, payload: ByteArray)
        abstract fun close()
    }
    private class UsbSink(private val socket: LocalSocket) : Sink("USB", 750) {
        private val output = socket.outputStream
        override fun write(header: ByteArray, payload: ByteArray) { output.write(header); output.write(payload); output.flush() }
        // close() alone does not wake a thread blocked writing to a LocalSocket; shutting it down does.
        override fun close() { runCatching { socket.shutdownInput() }; runCatching { socket.shutdownOutput() }; runCatching { socket.close() } }
    }
    private class WifiSink(private val session: WifiClient.Session) : Sink("Wi-Fi", 3000) {
        override fun write(header: ByteArray, payload: ByteArray) = session.send(header, payload)
        override fun close() = session.close()
    }
    companion object {
        /** OBS closing the connection is normal (source hidden, OBS quit, cable out); say so instead of "Broken pipe". */
        fun stopReason(e: Exception): String {
            val message = e.message.orEmpty()
            val closedByMac = e is java.io.IOException && listOf("EPIPE", "Broken pipe", "ECONNRESET", "Connection reset").any { it in message }
            return if (closedByMac) "OBS stopped receiving: the PhoneCam source was hidden, OBS was closed, or the cable or Wi-Fi was disconnected. Tap the record button to go live again."
            else "Live stopped: $message"
        }
        const val NAME = "phonecam-live-v1"
        const val MAX_PAYLOAD = 4 * 1024 * 1024
        const val WARM_UP_MS = 5_000L
        const val WARM_UP_WRITE_MS = 4_000L
        /** v3 frame flags: bit 0 keyframe, bits 1-2 clockwise quarter turns for upright display, bit 3 mirror in OBS. */
        const val VERSION = 3
        fun flags(frame: LiveQueue.Frame) = (if (frame.key) 1 else 0) or ((frame.quarterTurns and 3) shl 1) or (if (frame.mirror) 8 else 0)
        const val MAX_AUTH_FAILURES = 5
        /** The 32-byte PCAM v3 message header (docs/USB_LIVE.md). */
        fun header(type: Int, flags: Int, seq: Long, pts: Long, length: Int): ByteArray = java.nio.ByteBuffer.allocate(32)
            .putInt(0x5043414d).putShort(VERSION.toShort()).putShort(type.toShort()).putInt(length).putInt(flags).putLong(seq).putLong(pts).array()
        fun token(directory: File): String {
            val file = File(directory, "live-token")
            val atomic = android.util.AtomicFile(file)
            val existing = runCatching { atomic.openRead().use { it.readBytes().toString(Charsets.US_ASCII) } }.getOrNull()
            if (existing != null && existing.matches(Regex("[0-9a-f]{64}"))) return existing
            val value = SecureRandom().generateSeed(32).joinToString("") { "%02x".format(it) }
            val output = atomic.startWrite()
            try { output.write(value.toByteArray(Charsets.US_ASCII)); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
            return value
        }
    }
    // About 200 ms at 30 fps. Encoder bursts (camera movement, keyframes) can briefly exceed a USB 2.0
    // ADB link (measured ~17 MB/s on the vivo V2437); a 2-frame queue turned each burst into up to a
    // second of dropped frames. The queue only fills, and adds delay, while the link is behind.
    private val queue = LiveQueue(maxFrames = 6)
    private val signal = Object()
    @Volatile private var closed = false
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var deadline = Long.MAX_VALUE
    @Volatile private var configuration: ByteArray? = null
    @Volatile private var active: Sink? = null
    /** The first connection to authenticate wins; later ones are closed. */
    private fun claim(sink: Sink): Boolean = synchronized(this) {
        if (closed || active != null) false else { active = sink; warmUpUntil = SystemClock.elapsedRealtime() + WARM_UP_MS; true }
    }
    /** The computer sets up its decoder on the first picture (about 1.4 s on the 17 Nov Windows laptop) and reads
     *  nothing meanwhile, so the first seconds of a session allow longer writes before calling it stalled. */
    @Volatile private var warmUpUntil = 0L
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    val dropped: Long get() = queue.dropped

    fun start() {
        deadline = SystemClock.elapsedRealtime() + 30_000
        watchdog.scheduleAtFixedRate({
            if (!closed && SystemClock.elapsedRealtime() > deadline) {
                val wifiProblem = wifi?.problem?.let { " Direct Wi-Fi: $it" }.orEmpty()
                close()
                failed(if (active == null) "OBS did not connect within 30 seconds. Open OBS with the PhoneCam source visible, check the USB cable, Wi-Fi pairing or Wireless debugging, then tap the record button again.$wifiProblem"
                       else "The connection to OBS stalled. Tap the record button to try again.")
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
        Thread({
            var mine: Sink? = null
            try {
                val listener = LocalServerSocket(NAME)
                server = listener
                if (closed || active != null) { listener.close(); return@Thread }
                // The OBS plugin retries automatically, so a stale key (e.g. after reinstalling) or a
                // stray local connection must not end the session. Bounded: 5 failures per Start.
                var failures = 0
                var client: LocalSocket
                while (true) {
                    client = listener.accept()
                    socket = client
                    if (closed || active != null) { client.close(); return@Thread }
                    if (authenticate(client)) break
                    runCatching { client.close() }; socket = null
                    check(++failures < MAX_AUTH_FAILURES) { "Rejected $failures connections with the wrong key. Reconnect the USB cable or Wi-Fi so OBS re-pairs, then tap the record button again." }
                }
                val sink = UsbSink(client)
                if (!claim(sink)) { sink.close(); return@Thread }
                mine = sink
                pump(sink)
            } catch (e: Exception) {
                // Losing the race to Wi-Fi closes this listener on purpose; only the winner reports errors.
                if (!closed && (active == null || active === mine)) { close(); failed(stopReason(e)) }
            } finally { if (mine != null) close() }
        }, "phonecam-usb-writer").start()
        wifi?.let { connector -> Thread({
            var mine: Sink? = null
            try {
                val session = connector.connect { closed || active != null } ?: return@Thread
                val sink = WifiSink(session)
                if (!claim(sink)) { sink.close(); return@Thread }
                mine = sink
                stopListening()  // stop waiting for USB
                pump(sink)
            } catch (e: Exception) {
                if (!closed && active === mine && mine != null) { close(); failed(stopReason(e)) }
            } finally { if (mine != null) close() }
        }, "phonecam-wifi-writer").start() }
    }
    private fun pump(sink: Sink) {
        deadline = Long.MAX_VALUE
        connected(sink.route)
        var sent = 0
        while (!closed) {
            val config = configuration; val version = configVersion
            if (config != null && version != sent) { packet(sink, 1, 0, 0, 0, config); sent = version }
            val frame = if (config != null && sent == version) queue.poll() else null
            if (frame != null) packet(sink, 2, flags(frame), frame.sequence, frame.ptsUs, frame.bytes)
            else synchronized(signal) { if (!closed) signal.wait(10) }
        }
    }
    /** Reads the receiver's key line with a 2-second limit; constant-time comparison. */
    private fun authenticate(client: LocalSocket): Boolean = runCatching {
        client.soTimeout = 2_000
        val auth = ByteArray(65)
        val input = client.inputStream
        var offset = 0
        while (offset < auth.size) {
            val count = input.read(auth, offset, auth.size - offset)
            if (count <= 0) return false
            offset += count
        }
        MessageDigest.isEqual(auth, "$token\n".toByteArray(Charsets.US_ASCII))
    }.getOrDefault(false)

    @Volatile private var configVersion = 0
    /** Sent before the next frame. A later call (camera or quality switch) is sent again on the same connection;
     *  plugins from 0.4.1 rebuild their decoder, older ones end the connection and the phone restarts instead. */
    fun configure(value: ByteArray) {
        require(value.size <= 65536) { "Encoder configuration too large" }
        synchronized(signal) { configuration = value; configVersion++; signal.notifyAll() }
    }
    /** Camera or quality switch: drop the old encoder's settings and queued frames, keep the connection. */
    fun restart() {
        synchronized(signal) { configuration = null; queue.clear() }
        warmUpUntil = SystemClock.elapsedRealtime() + WARM_UP_MS  // the computer sets up a new decoder
    }
    fun offer(frame: LiveQueue.Frame): Boolean = queue.offer(frame)

    private fun packet(sink: Sink, type: Int, flags: Int, seq: Long, pts: Long, data: ByteArray) {
        require(data.isNotEmpty() && data.size <= MAX_PAYLOAD) { "Encoded packet exceeds the 4 MiB limit" }
        val now = SystemClock.elapsedRealtime()
        deadline = now + if (now < warmUpUntil) maxOf(sink.writeTimeoutMs, WARM_UP_WRITE_MS) else sink.writeTimeoutMs
        sink.write(header(type, flags, seq, pts, data.size), data)
        deadline = Long.MAX_VALUE
    }
    /**
     * Ends the wait for a USB connection. Closing a LocalServerSocket does not wake a thread blocked in
     * accept(): the socket name then stays bound and the next Start fails with "Address already in use"
     * (audit/FINDINGS.md AUDIT-001). Shutting the socket down and connecting to it once makes accept()
     * return; the waiting thread sees the session is over and discards that connection.
     */
    private fun stopListening() {
        val listener = server ?: return
        server = null
        runCatching { Os.shutdown(listener.fileDescriptor, OsConstants.SHUT_RDWR) }
        runCatching { LocalSocket().use { it.connect(LocalSocketAddress(NAME)) } }
        runCatching { listener.close() }
    }
    fun close() {
        closed = true
        runCatching { socket?.close() }; stopListening(); active?.close()
        synchronized(signal) { signal.notifyAll() }
        watchdog.shutdownNow()
    }
}
