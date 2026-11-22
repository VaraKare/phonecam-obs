package dev.phonecam.obs

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sound for OBS (0.6+; each source off unless the user turns it on): the phone microphone, and while screen
 * sharing the phone's own sound (0.7, Android's playback capture: apps that allow it, which most games and media
 * apps do). 48 kHz, 16-bit, mono, in 10 ms chunks; with both on they are mixed into one stream. Each chunk is
 * stamped on the same clock as the video frames ([boottime] when the camera stamps frames with elapsedRealtime,
 * else the monotonic clock), so OBS can line sound and picture up. Nothing is stored.
 */
class AudioSender(private val boottime: Boolean, private val microphone: Boolean, private val phoneSound: MediaProjection?,
                  private val send: (body: ByteArray, ptsUs: Long) -> Unit, private val failed: (String) -> Unit) {
    companion object {
        const val RATE = 48_000
        const val CHUNK_FRAMES = RATE / 100
        /** u32 rate, u16 channels, u16 format (1 = signed 16-bit little-endian), then the samples (docs/USB_LIVE.md). */
        fun body(pcm: ByteArray, length: Int): ByteArray = ByteBuffer.allocate(8 + length).order(ByteOrder.BIG_ENDIAN)
            .putInt(RATE).putShort(1).putShort(1).put(pcm, 0, length).array()
        /** Start time of the frame at [position], from one AudioTimestamp pair. */
        fun ptsNs(position: Long, stampPosition: Long, stampNs: Long): Long = stampNs + (position - stampPosition) * 1_000_000_000L / RATE
        /** Adds [other] into [into] (16-bit little-endian samples), clipping instead of wrapping around. */
        fun mix(into: ByteArray, other: ByteArray, length: Int) {
            var i = 0
            while (i + 1 < length) {
                val a = (into[i].toInt() and 0xFF) or (into[i + 1].toInt() shl 8)
                val b = (other[i].toInt() and 0xFF) or (other[i + 1].toInt() shl 8)
                val sum = (a + b).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                into[i] = sum.toByte(); into[i + 1] = (sum shr 8).toByte()
                i += 2
            }
        }
    }
    @Volatile private var running = false
    private var thread: Thread? = null
    private val format = AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
    private val bufferBytes get() = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 5 * 2)

    // RECORD_AUDIO is checked by MainActivity before either sound is turned on (both need it on Android).
    @SuppressLint("MissingPermission")
    private fun open(): List<AudioRecord> {
        val records = mutableListOf<AudioRecord>()
        if (microphone) records += AudioRecord(MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        if (phoneSound != null) records += AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(phoneSound)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build())
            .build()
        return records
    }

    fun start() {
        if (running || (!microphone && phoneSound == null)) return
        val records = runCatching { open() }.getOrElse { failed("The phone's sound couldn't start: ${it.message}"); return }
        if (records.any { it.state != AudioRecord.STATE_INITIALIZED }) {
            records.forEach { it.release() }
            failed(if (microphone && phoneSound == null) "The microphone couldn't start. Another app may be using it." else "The phone's sound couldn't start. Another app may be using the microphone.")
            return
        }
        running = true
        thread = Thread({
            try {
                records.forEach { it.startRecording() }
                val pcm = ByteArray(CHUNK_FRAMES * 2)
                val second = ByteArray(CHUNK_FRAMES * 2)
                val stamp = AudioTimestamp()
                val base = if (boottime) AudioTimestamp.TIMEBASE_BOOTTIME else AudioTimestamp.TIMEBASE_MONOTONIC
                var position = 0L
                fun fill(record: AudioRecord, into: ByteArray): Boolean {
                    var filled = 0
                    while (filled < into.size && running) {
                        val n = record.read(into, filled, into.size - filled, AudioRecord.READ_BLOCKING)
                        if (n < 0) throw IllegalStateException("sound read error $n")
                        filled += n
                    }
                    return filled == into.size
                }
                while (running) {
                    if (!fill(records[0], pcm)) break
                    // Both run at 48 kHz, so one chunk of each is the same 10 ms; the first one's clock is used.
                    if (records.size > 1) { if (!fill(records[1], second)) break; mix(pcm, second, pcm.size) }
                    val now = if (boottime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()
                    val pts = if (records[0].getTimestamp(stamp, base) == AudioRecord.SUCCESS) ptsNs(position, stamp.framePosition, stamp.nanoTime)
                        else now - CHUNK_FRAMES * 1_000_000_000L / RATE
                    send(body(pcm, pcm.size), pts / 1000)
                    position += CHUNK_FRAMES
                }
            } catch (e: Exception) {
                if (running) failed("The phone's sound stopped: ${e.message}")
            } finally {
                records.forEach { runCatching { it.stop() }; it.release() }
            }
        }, "phonecam-sound").apply { start() }
    }

    fun stop() { running = false; thread?.join(500); thread = null }
}
