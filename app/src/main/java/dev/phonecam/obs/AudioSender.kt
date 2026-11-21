package dev.phonecam.obs

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone microphone for OBS (0.6; off unless the user turns it on). 48 kHz, 16-bit, mono, in 10 ms chunks.
 * Each chunk is stamped on the same clock as the video frames ([boottime] when the camera stamps frames with
 * elapsedRealtime, else the monotonic clock), so OBS can line sound and picture up. Nothing is stored.
 */
class AudioSender(private val boottime: Boolean, private val send: (body: ByteArray, ptsUs: Long) -> Unit,
                  private val failed: (String) -> Unit) {
    companion object {
        const val RATE = 48_000
        const val CHUNK_FRAMES = RATE / 100
        /** u32 rate, u16 channels, u16 format (1 = signed 16-bit little-endian), then the samples (docs/USB_LIVE.md). */
        fun body(pcm: ByteArray, length: Int): ByteArray = ByteBuffer.allocate(8 + length).order(ByteOrder.BIG_ENDIAN)
            .putInt(RATE).putShort(1).putShort(1).put(pcm, 0, length).array()
        /** Start time of the frame at [position], from one AudioTimestamp pair. */
        fun ptsNs(position: Long, stampPosition: Long, stampNs: Long): Long = stampNs + (position - stampPosition) * 1_000_000_000L / RATE
    }
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")  // RECORD_AUDIO is checked by MainActivity before the microphone is turned on
    fun start() {
        if (running) return
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, RATE / 5 * 2))
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); failed("The microphone couldn't start. Another app may be using it."); return }
        running = true
        thread = Thread({
            try {
                record.startRecording()
                val pcm = ByteArray(CHUNK_FRAMES * 2)
                val stamp = AudioTimestamp()
                val base = if (boottime) AudioTimestamp.TIMEBASE_BOOTTIME else AudioTimestamp.TIMEBASE_MONOTONIC
                var position = 0L
                while (running) {
                    var filled = 0
                    while (filled < pcm.size && running) {
                        val n = record.read(pcm, filled, pcm.size - filled, AudioRecord.READ_BLOCKING)
                        if (n < 0) throw IllegalStateException("microphone read error $n")
                        filled += n
                    }
                    if (!running) break
                    val now = if (boottime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()
                    val pts = if (record.getTimestamp(stamp, base) == AudioRecord.SUCCESS) ptsNs(position, stamp.framePosition, stamp.nanoTime)
                        else now - CHUNK_FRAMES * 1_000_000_000L / RATE
                    send(body(pcm, filled), pts / 1000)
                    position += filled / 2
                }
            } catch (e: Exception) {
                if (running) failed("The microphone stopped: ${e.message}")
            } finally {
                runCatching { record.stop() }; record.release()
            }
        }, "phonecam-microphone").apply { start() }
    }

    fun stop() { running = false; thread?.join(500); thread = null }
}
