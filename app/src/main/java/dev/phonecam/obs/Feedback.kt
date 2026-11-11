package dev.phonecam.obs

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaActionSound
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Sounds and vibration for moments that matter: going live, stopping, pairing, errors.
 * Live start/stop use Android's own camera recording sounds; pairing uses a short generated chime.
 * Sounds play only when the ringer is on normal and the user hasn't turned them off in Settings;
 * vibration follows the system's touch-feedback setting.
 */
class Feedback(private val context: Context) {
    enum class Event { LIVE, STOPPED, SUCCESS, ERROR, TICK }
    private val camera by lazy { MediaActionSound().apply { load(MediaActionSound.START_VIDEO_RECORDING); load(MediaActionSound.STOP_VIDEO_RECORDING) } }

    fun play(event: Event, view: View?) {
        view?.performHapticFeedback(when (event) {
            Event.LIVE, Event.SUCCESS -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Event.ERROR -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            Event.STOPPED -> HapticFeedbackConstants.VIRTUAL_KEY
            Event.TICK -> HapticFeedbackConstants.CLOCK_TICK
        })
        if (event == Event.TICK || !soundAllowed()) return
        when (event) {
            Event.LIVE -> camera.play(MediaActionSound.START_VIDEO_RECORDING)
            Event.STOPPED -> camera.play(MediaActionSound.STOP_VIDEO_RECORDING)
            Event.SUCCESS -> chime(listOf(880.0, 1318.5))   // A5 then E6: rising, "done"
            Event.ERROR -> chime(listOf(440.0, 349.2))      // A4 then F4: falling, "not quite"
            Event.TICK -> Unit
        }
    }
    fun release() = runCatching { camera.release() }

    private fun soundAllowed(): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("sounds", true) &&
            context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_NORMAL

    /** Two soft sine notes (90 ms each, smooth fade in and out) on the notification sound channel. */
    private fun chime(notes: List<Double>) = Thread {
        val rate = 44_100; val noteSamples = rate * 90 / 1000; val fade = rate * 12 / 1000
        val pcm = ShortArray(noteSamples * notes.size)
        notes.forEachIndexed { n, frequency ->
            for (i in 0 until noteSamples) {
                val envelope = min(1.0, min(i, noteSamples - 1 - i).toDouble() / fade)
                pcm[n * noteSamples + i] = (sin(2 * PI * frequency * i / rate) * envelope * 0.25 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(notes.size * 90L + 60)
            track.release()
        }
    }.start()
}
