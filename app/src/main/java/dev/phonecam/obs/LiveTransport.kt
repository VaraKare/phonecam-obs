package dev.phonecam.obs

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.SystemClock
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Local abstract socket: reachable through ADB, without Android INTERNET permission. */
class LiveTransport(private val token: String, private val connected: () -> Unit,
                    private val failed: (String) -> Unit) {
    companion object {
        const val NAME = "phonecam-live-v1"
        const val MAX_PAYLOAD = 4 * 1024 * 1024
        /** v2: frame flags bits 1-2 carry the clockwise quarter turns for upright display. */
        const val VERSION = 2
        const val MAX_AUTH_FAILURES = 5
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
    private val queue = LiveQueue()
    private val signal = Object()
    @Volatile private var closed = false
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var deadline = Long.MAX_VALUE
    @Volatile private var configuration: ByteArray? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    val dropped: Long get() = queue.dropped

    fun start() {
        watchdog.scheduleAtFixedRate({
            if (!closed && SystemClock.elapsedRealtime() > deadline) {
                close(); failed("OBS did not connect within 30 seconds. Check the USB cable, open OBS with the PhoneCam source visible, then tap Start again.")
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
        Thread({
            try {
                val listener = LocalServerSocket(NAME)
                server = listener
                if (closed) { listener.close(); return@Thread }
                deadline = SystemClock.elapsedRealtime() + 30_000
                // The OBS plugin retries automatically, so a stale key (e.g. after reinstalling) or a
                // stray local connection must not end the session. Bounded: 5 failures per Start.
                var failures = 0
                var client: LocalSocket
                while (true) {
                    client = listener.accept()
                    socket = client
                    if (closed) { client.close(); return@Thread }
                    if (authenticate(client)) break
                    runCatching { client.close() }; socket = null
                    check(++failures < MAX_AUTH_FAILURES) { "Rejected $failures connections with the wrong key. Reconnect the USB cable so OBS re-pairs, then tap Start again." }
                }
                deadline = Long.MAX_VALUE
                connected()
                val output = DataOutputStream(client.outputStream)
                var configured = false
                while (!closed) {
                    if (!configured) {
                        configuration?.let { packet(output, 1, 0, 0, 0, it); configured = true }
                    }
                    val frame = if (configured) queue.poll() else null
                    if (frame != null) packet(output, 2, (if (frame.key) 1 else 0) or (frame.quarterTurns shl 1), frame.sequence, frame.ptsUs, frame.bytes)
                    else synchronized(signal) { if (!closed) signal.wait(10) }
                }
            } catch (e: Exception) {
                if (!closed) { close(); failed("USB live stopped: ${e.message}") }
            } finally { close() }
        }, "phonecam-usb-writer").start()
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

    fun configure(value: ByteArray) {
        require(value.size <= 65536) { "Encoder configuration too large" }
        check(configuration == null) { "Encoder changed format; restart the stream" }
        configuration = value
    }
    fun offer(frame: LiveQueue.Frame): Boolean = queue.offer(frame)

    private fun packet(output: DataOutputStream, type: Int, flags: Int, seq: Long, pts: Long, data: ByteArray) {
        require(data.isNotEmpty() && data.size <= MAX_PAYLOAD) { "Encoded packet exceeds the USB limit" }
        deadline = SystemClock.elapsedRealtime() + 750
        output.writeInt(0x5043414d); output.writeShort(VERSION); output.writeShort(type)
        output.writeInt(data.size); output.writeInt(flags); output.writeLong(seq); output.writeLong(pts)
        output.write(data); output.flush()
        deadline = Long.MAX_VALUE
    }
    fun close() {
        closed = true
        runCatching { socket?.close() }; runCatching { server?.close() }
        synchronized(signal) { signal.notifyAll() }
        watchdog.shutdownNow()
    }
}
