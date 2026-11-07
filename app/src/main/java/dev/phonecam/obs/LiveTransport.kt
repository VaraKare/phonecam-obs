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
                close(); failed("USB connection timed out. Check the cable/ADB forward, then tap Start USB live again.")
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
        Thread({
            try {
                val listener = LocalServerSocket(NAME)
                server = listener
                if (closed) { listener.close(); return@Thread }
                deadline = SystemClock.elapsedRealtime() + 30_000
                val client = listener.accept()
                socket = client
                if (closed) { client.close(); return@Thread }
                client.soTimeout = 2_000
                deadline = SystemClock.elapsedRealtime() + 2_000
                val auth = ByteArray(65)
                val input = client.inputStream
                var offset = 0
                while (offset < auth.size) {
                    val count = input.read(auth, offset, auth.size - offset)
                    check(count > 0) { "USB authentication interrupted" }; offset += count
                }
                check(MessageDigest.isEqual(auth, "$token\n".toByteArray(Charsets.US_ASCII))) { "USB authentication failed; run tools/live.py prepare again" }
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
