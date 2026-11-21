package dev.phonecam.obs

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast

/**
 * Settings as short grouped rows. Switches save immediately; actions that need the home screen
 * (pairing, custom quality, camera check) return to it with [EXTRA_ACTION].
 */
class SettingsActivity : Activity() {
    companion object {
        const val EXTRA_ACTION = "action"
        /** Opened during live: options that need the camera, or would restart the session, wait until Stop. */
        const val EXTRA_LIVE = "live"
        const val SCAN = "scan"; const val TYPE_CODE = "type"; const val CUSTOM = "custom"; const val RESCAN = "rescan"
    }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val ui by lazy { Ui(this) }
    private val live by lazy { intent.getBooleanExtra(EXTRA_LIVE, false) }
    /** A row that needs the camera (or restarts live): during live it explains instead of acting. */
    private fun cameraRow(title: String, subtitle: String?, action: () -> Unit) =
        ui.row(title, if (live) "After you stop live" else subtitle) {
            if (live) Toast.makeText(this, "Stop live first: this uses the camera.", Toast.LENGTH_SHORT).show() else action()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.page()
        build()
    }

    // Back from Android's app or notification settings: show the current notification state.
    override fun onRestart() { super.onRestart(); build() }

    /** Leaving PhoneCam from here during live follows the same rule as the home screen. */
    override fun onStop() {
        if (live && !isFinishing && !isChangingConfigurations) {
            if (prefs.getBoolean("background", true)) prefs.edit().putBoolean("left_live", true).apply()
            else LiveService.stop?.invoke("PhoneCam left the screen, so live stopped. To keep live running, turn on Settings → Keep live when you leave the app.")
        }
        super.onStop()
    }

    private fun build() {
        ui.list.removeAllViews()
        ui.title("Settings", if (live) "Live continues while you're here." else null)

        ui.section("Connection")
        val count = WifiConnector(this).pairings.macs.size
        ui.row("Paired computers", if (count == 0) "None yet" else "For direct Wi-Fi", value = count.toString()) { showPaired() }
        cameraRow("Pair a computer", "Scan the QR code shown in OBS") { finishWith(SCAN) }
        ui.row("Type a pairing code", "If scanning doesn't work") { finishWith(TYPE_CODE) }

        ui.section("While live")
        toggle("Keep live when you leave the app", "A notification with Stop shows while live. Swiping PhoneCam away from recent apps stops it.", "background")
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            toggle("Picture-in-picture when you leave", "The phone preview stays in a small window. Closing the window keeps live running in the background (if the switch above is on).", "pip")
        toggle("Heat and battery warnings", "A notification when the phone gets hot (with Lower quality) and at 20, 15, 10 and 5 % battery.", "warnings")
        toggle("Dim the screen while live", "After 30 seconds without a touch. Saves battery and heat; the video isn't affected.", "dim")
        ui.row("Let PhoneCam run in the background", backgroundHint()) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ui.row("Notifications are off", "Stop won't show in the status bar while live. Tap to turn them on.") {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }

        ui.section("Video")
        toggle("Phone preview while live", if (live) "Changes from the next live" else null, "preview")
        toggle("Extra sharpening", if (live) "Changes from the next live" else "Clearer detail; may lower the frame rate", "hq")
        cameraRow("Custom quality", "Pick an exact size and bitrate") { finishWith(CUSTOM) }
        cameraRow("Check cameras again", null) { finishWith(RESCAN) }

        ui.section("Sounds")
        toggle("Sounds", "When going live, stopping and pairing. Silent mode always wins.", "sounds")

        ui.section("About")
        ui.row("Privacy") {
            AlertDialog.Builder(this).setTitle("Privacy").setMessage(
                "Video goes only to OBS: over the USB cable, or encrypted over your local Wi-Fi to a computer you paired. " +
                "Nothing is saved on the phone while live. No microphone, accounts, analytics or Internet servers.")
                .setPositiveButton("OK", null).show()
        }
        cameraRow("Research tools", "Benchmark recordings for testing") { startActivity(Intent(this, BenchmarkActivity::class.java)) }
        ui.list.addView(TextView(this).apply {
            text = "PhoneCam ${packageManager.getPackageInfo(packageName, 0).versionName}"
            setTextColor(Ui.MUTED); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, ui.dp(28), 0, 0)
        })
    }

    /** The paired computers, most recently used first; tapping one offers to forget it. */
    private fun showPaired() {
        val pairings = WifiConnector(this).pairings
        val macs = pairings.macs.reversed()
        if (macs.isEmpty()) {
            AlertDialog.Builder(this).setTitle("No paired computers")
                .setMessage("Pair once to use direct Wi-Fi. In OBS, open the PhoneCam source's Properties and click Pair a phone over Wi-Fi.")
                .setNegativeButton("Close", null).setPositiveButton("Pair a computer") { _, _ -> finishWith(SCAN) }.show()
            return
        }
        val names = macs.mapIndexed { i, mac -> mac.name + if (i == 0) "  ·  last used" else "" }
        AlertDialog.Builder(this).setTitle("Paired computers")
            .setItems(names.toTypedArray()) { _, index ->
                val mac = macs[index]
                AlertDialog.Builder(this).setTitle("Forget ${mac.name}?")
                    .setMessage("This phone stops connecting to it over Wi-Fi until you pair again. USB is not affected.")
                    .setNegativeButton("Cancel") { _, _ -> showPaired() }
                    .setPositiveButton("Forget") { _, _ -> pairings.forget(mac.id); build(); showPaired() }.show()
            }
            .setNegativeButton("Close", null).show()
    }

    /** vivo (and some other brands) close background apps to save battery unless the app is allowed. */
    private fun backgroundHint() = when (Build.MANUFACTURER.lowercase()) {
        "vivo", "iqoo" -> "vivo can close background apps. Tap, then Battery, and allow background power use."
        "xiaomi", "redmi", "poco" -> "Xiaomi can close background apps. Tap, then Battery saver, and choose No restrictions."
        "oppo", "realme", "oneplus" -> "This phone can close background apps. Tap, then Battery, and allow background activity."
        else -> "If live stops when you leave PhoneCam, tap, then Battery, and allow background use."
    }

    private fun toggle(title: String, subtitle: String?, key: String) =
        ui.toggle(title, subtitle, prefs.getBoolean(key, true)) { prefs.edit().putBoolean(key, it).apply() }

    private fun finishWith(action: String) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action)); finish() }
}
