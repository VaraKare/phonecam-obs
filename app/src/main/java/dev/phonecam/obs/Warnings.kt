package dev.phonecam.obs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.PowerManager

/**
 * Heat and battery warnings while live (owner's idea, 21 Nov 2026), and a note when live ends while PhoneCam is
 * away from the screen, so it never ends silently. Each warning shows once per session; ignoring one is the
 * user's choice. [check] runs on every status tick (about once a second), also in the background.
 */
class Warnings(private val context: Context) {
    companion object {
        private const val CHANNEL = "warnings"
        private const val HEAT = 2; private const val BATTERY = 3; private const val ENDED = 4
        val BATTERY_STEPS = listOf(20, 15, 10, 5)

        /** The battery step to warn about now, or null: the lowest step reached that hasn't been shown yet. */
        fun batteryStep(percent: Int, charging: Boolean, shown: Set<Int>): Int? =
            if (charging || percent < 0) null else BATTERY_STEPS.filter { percent <= it && it !in shown }.minOrNull()
    }
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var heatShown = 0
    private val batteryShown = mutableSetOf<Int>()
    /** Shown in OBS's Properties → Phone while it applies. */
    var current: String? = null; private set

    init {
        runCatching {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Heat and battery while live, and when live ends while PhoneCam is in the background."
            })
        }
    }

    /** A new session: every warning may show once again. */
    fun reset() { heatShown = 0; batteryShown.clear(); current = null; runCatching { manager.cancel(HEAT); manager.cancel(BATTERY) } }

    fun check(enabled: Boolean, heat: Int, percent: Int, charging: Boolean, canLower: Boolean) {
        val heatText = when {
            heat >= PowerManager.THERMAL_STATUS_SEVERE -> "The phone is hot. Lower the quality or take the case off; it may slow down or stop."
            heat >= PowerManager.THERMAL_STATUS_MODERATE -> "The phone is getting warm."
            else -> null
        }
        val low = !charging && percent in 0..20
        current = heatText ?: if (low) "Battery $percent %. Plug the phone in." else null
        if (!enabled) return
        if (heat >= PowerManager.THERMAL_STATUS_MODERATE && heat > heatShown) {
            heatShown = heat
            show(HEAT, if (heat >= PowerManager.THERMAL_STATUS_SEVERE) "Phone is hot" else "Phone is warm", heatText!!, canLower)
        }
        batteryStep(percent, charging, batteryShown)?.let { step ->
            BATTERY_STEPS.filter { it >= step }.forEach(batteryShown::add)
            show(BATTERY, "Battery $percent %", if (step <= 10) "Live may stop soon. Plug the phone in, or choose Balanced quality." else "Plug the phone in to keep going live.", canLower && step <= 10)
        }
    }

    /** Live ended while PhoneCam wasn't on screen: say why (with sound and vibration on this channel). */
    fun ended(reason: String) = show(ENDED, "Live ended", reason, false)

    private fun show(id: Int, title: String, text: String, lower: Boolean) {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_live).setColor(0xFFB26A00.toInt())
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
            .apply {
                if (open != null) setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE))
                if (lower) addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_live), "Lower quality",
                    PendingIntent.getService(context, 2, Intent(context, LiveService::class.java).setAction(LiveService.LOWER), PendingIntent.FLAG_IMMUTABLE)).build())
            }.build()
        runCatching { manager.notify(id, notification) }
    }
}
