package dev.phonecam.obs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Background live (owner's decision, 21 Nov 2026): keeps a session the user started running after they
 * leave PhoneCam. Android's camera-type foreground service shows a notification with Stop for the whole
 * session and its own camera indicator. It never starts capture: MainActivity owns the session (it gives [stop]), and the
 * service only keeps the process allowed to use the camera, the CPU awake and, over Wi-Fi, the radio fast.
 * Swiping PhoneCam away from recent apps stops live.
 */
class LiveService : Service() {
    companion object {
        private const val CHANNEL = "live"
        private const val ID = 1
        private const val STOP = "dev.phonecam.obs.STOP"
        /** Stops the user's session (with a reason); set at Start and cleared when it stops. */
        @Volatile var stop: ((String) -> Unit)? = null
        /** True once Android accepted the foreground service, so leaving the app may keep live running. */
        @Volatile var running = false; private set
        private var shown: Triple<LiveState.Phase, String, Long>? = null
        private var wake: PowerManager.WakeLock? = null
        private var radio: WifiManager.WifiLock? = null

        /** Starts the service for [live]. False when Android refuses; live then stops when the app is left. */
        fun start(context: Context, stopLive: (String) -> Unit): Boolean {
            stop = stopLive; shown = null
            return runCatching { context.startForegroundService(Intent(context, LiveService::class.java)) }
                .onFailure { Log.w("PhoneCam", "Background live unavailable: ${it.message}") }.isSuccess
        }

        /** Keeps the notification in step with the session; only phase or route changes are posted. */
        fun update(context: Context, state: LiveState, liveSince: Long) {
            if (!running || state.phase == LiveState.Phase.STOPPED) return
            val next = Triple(state.phase, state.route, if (state.phase == LiveState.Phase.LIVE) liveSince else 0L)
            if (next == shown) return
            shown = next
            holdRadio(context, state.phase == LiveState.Phase.LIVE && state.route == "Wi-Fi")
            runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, build(context, next)) }
        }

        /** The session stopped: remove the notification and let the process rest. */
        fun end(context: Context) {
            stop = null; shown = null
            context.stopService(Intent(context, LiveService::class.java))
        }

        private fun build(context: Context, state: Triple<LiveState.Phase, String, Long>?): Notification {
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val stopIntent = PendingIntent.getService(context, 0, Intent(context, LiveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val live = state?.first == LiveState.Phase.LIVE
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_live)
                .setColor(0xFFC62828.toInt())
                .setContentTitle(if (live) "● PhoneCam is live to OBS" + (state?.second?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") else "PhoneCam is waiting for OBS")
                .setContentText(if (live) "Nothing is saved on the phone. Tap to open PhoneCam." else "The camera turns on once OBS connects.")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .apply {
                    if (live && state != null) {
                        // The live timer, counted from the same moment as the badge on the phone screen.
                        setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - state.third)).setShowWhen(true).setUsesChronometer(true)
                    } else setShowWhen(false)
                    if (open != null) setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_live), "Stop", stopIntent).build())
                .build()
        }

        private fun holdRadio(context: Context, on: Boolean) {
            if (on && radio == null) radio = runCatching {
                @Suppress("DEPRECATION")  // still honoured for apps in the background; low latency mode is foreground-only
                context.applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhoneCam:live").apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            if (!on) { runCatching { radio?.release() }; radio = null }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stop?.invoke("Stopped by you") ?: stopSelf()
            return START_NOT_STICKY
        }
        // Android requires startForeground after startForegroundService, even when the session already ended.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Live to OBS", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while PhoneCam sends video to OBS, with Stop."
                setShowBadge(false)
            })
        try {
            // Android 11+ needs the camera type to keep using the camera in the background; Android 10 has no types.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startForeground(ID, build(this, shown), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(ID, build(this, shown))
        } catch (e: Exception) {
            Log.w("PhoneCam", "Background live refused: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }
        if (stop == null) { stopSelf(); return START_NOT_STICKY }
        running = true
        if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneCam:live")
            .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }  // released on Stop; bounded in case of a bug
        return START_NOT_STICKY  // never restarted by Android: live starts only when the user taps Start
    }

    /** Swiping PhoneCam away from recent apps, or "clear all", ends live. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stop?.invoke("PhoneCam was closed, so live stopped.")
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        runCatching { wake?.release() }; wake = null
        holdRadio(this, false)
        super.onDestroy()
    }
}
