package dev.phonecam.obs

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Settings as short grouped rows. Switches save immediately; actions that need the home screen
 * (pairing, custom quality, camera check) return to it with [EXTRA_ACTION].
 */
class SettingsActivity : Activity() {
    companion object {
        const val EXTRA_ACTION = "action"
        const val SCAN = "scan"; const val TYPE_CODE = "type"; const val CUSTOM = "custom"; const val RESCAN = "rescan"
    }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val ui by lazy { Ui(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.page()
        build()
    }

    private fun build() {
        ui.list.removeAllViews()
        ui.title("Settings")

        ui.section("Connection")
        val wifi = WifiConnector(this)
        wifi.pairings.macs.forEach { mac ->
            ui.row(mac.name, "Paired over Wi-Fi", chevron = false) {
                AlertDialog.Builder(this).setTitle("Forget ${mac.name}?")
                    .setMessage("This phone stops connecting to it over Wi-Fi until you pair again.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ -> wifi.pairings.forget(mac.id); build() }.show()
            }
        }
        ui.row("Pair a computer", "Scan the QR code shown in OBS") { finishWith(SCAN) }
        ui.row("Type a pairing code", "If scanning doesn't work") { finishWith(TYPE_CODE) }

        ui.section("Video")
        toggle("Phone preview while live", null, "preview")
        toggle("Extra sharpening", "Clearer detail; may lower the frame rate", "hq")
        ui.row("Custom quality", "Pick an exact size and bitrate") { finishWith(CUSTOM) }
        ui.row("Check cameras again") { finishWith(RESCAN) }

        ui.section("Sounds")
        toggle("Sounds", "When going live, stopping and pairing. Silent mode always wins.", "sounds")

        ui.section("About")
        ui.row("Privacy") {
            AlertDialog.Builder(this).setTitle("Privacy").setMessage(
                "Video goes only to OBS: over the USB cable, or encrypted over your local Wi-Fi to a computer you paired. " +
                "Nothing is saved on the phone while live. No microphone, accounts, analytics or Internet servers.")
                .setPositiveButton("OK", null).show()
        }
        ui.row("Research tools", "Benchmark recordings for testing") { startActivity(Intent(this, BenchmarkActivity::class.java)) }
        ui.list.addView(TextView(this).apply {
            text = "PhoneCam ${packageManager.getPackageInfo(packageName, 0).versionName}"
            setTextColor(Ui.MUTED); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, ui.dp(28), 0, 0)
        })
    }

    private fun toggle(title: String, subtitle: String?, key: String) =
        ui.toggle(title, subtitle, prefs.getBoolean(key, true)) { prefs.edit().putBoolean(key, it).apply() }

    private fun finishWith(action: String) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action)); finish() }
}
