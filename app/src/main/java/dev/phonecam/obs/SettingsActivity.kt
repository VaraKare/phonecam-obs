package dev.phonecam.obs

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Settings as short grouped rows. Switches save immediately; actions that need the home screen
 * (pairing, custom quality, camera check) return to it with [EXTRA_ACTION].
 */
class SettingsActivity : Activity() {
    companion object {
        const val EXTRA_ACTION = "action"
        const val SCAN = "scan"; const val TYPE_CODE = "type"; const val CUSTOM = "custom"; const val RESCAN = "rescan"
        private const val BACKGROUND = 0xFF0E0E0E.toInt(); private const val MUTED = 0xFF9A9A9A.toInt(); private const val DIVIDER = 0xFF232323.toInt()
    }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(32)) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BACKGROUND); addView(list)
            setOnApplyWindowInsetsListener { v, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).let { v.setPadding(it.left, it.top, it.right, it.bottom) }
                insets
            }
        }
        setContentView(scroll)
        build()
    }

    private fun build() {
        list.removeAllViews()
        list.addView(TextView(this).apply { text = "Settings"; setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(16), 0, dp(8)) })

        section("Connection")
        val wifi = WifiConnector(this)
        wifi.pairings.macs.forEach { mac ->
            row(mac.name, "Paired over Wi-Fi") {
                AlertDialog.Builder(this).setTitle("Forget ${mac.name}?")
                    .setMessage("This phone stops connecting to it over Wi-Fi until you pair again.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ -> wifi.pairings.forget(mac.id); build() }.show()
            }
        }
        row("Pair a computer", "Scan the QR code shown in OBS", chevron = true) { finishWith(SCAN) }
        row("Type a pairing code", "If scanning doesn't work", chevron = true) { finishWith(TYPE_CODE) }

        section("Video")
        toggle("Phone preview while live", null, "preview", true)
        toggle("Extra sharpening", "Clearer detail; may lower the frame rate", "hq", true)
        row("Custom quality", "Pick an exact size and bitrate", chevron = true) { finishWith(CUSTOM) }
        row("Check cameras again", null, chevron = true) { finishWith(RESCAN) }

        section("Sounds")
        toggle("Sounds", "When going live, stopping and pairing. Silent mode always wins.", "sounds", true)

        section("About")
        row("Privacy", null, chevron = true) {
            AlertDialog.Builder(this).setTitle("Privacy").setMessage(
                "Video goes only to OBS: over the USB cable, or encrypted over your local Wi-Fi to a computer you paired. " +
                "Nothing is saved on the phone while live. No microphone, accounts, analytics or Internet servers.")
                .setPositiveButton("OK", null).show()
        }
        row("Research tools", "Benchmark recordings for testing", chevron = true) { startActivity(Intent(this, BenchmarkActivity::class.java)) }
        list.addView(TextView(this).apply {
            text = "PhoneCam ${packageManager.getPackageInfo(packageName, 0).versionName}"
            setTextColor(MUTED); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(28), 0, 0)
        })
    }

    private fun finishWith(action: String) { setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action)); finish() }

    private fun section(title: String) = list.addView(TextView(this).apply {
        text = title; setTextColor(MUTED); textSize = 13f; setPadding(0, dp(24), 0, dp(4))
    })

    private fun rowView(title: String, subtitle: String?, trailing: View?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56)
        setPadding(0, dp(10), 0, dp(10))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; setTextColor(Color.WHITE); textSize = 16f })
            if (subtitle != null) addView(TextView(context).apply { text = subtitle; setTextColor(MUTED); textSize = 13f; setPadding(0, dp(2), 0, 0) })
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { addView(it) }
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, null)
    }.also { view ->
        list.addView(view)
        list.addView(View(this).apply { setBackgroundColor(DIVIDER) }, LinearLayout.LayoutParams(-1, 1))
    }

    private fun row(title: String, subtitle: String?, chevron: Boolean = false, click: () -> Unit) {
        val arrow = if (chevron) TextView(this).apply { text = "›"; setTextColor(MUTED); textSize = 22f; setPadding(dp(12), 0, 0, 0) } else null
        rowView(title, subtitle, arrow).setOnClickListener { click() }
    }

    @Suppress("DEPRECATION")
    private fun toggle(title: String, subtitle: String?, key: String, default: Boolean) {
        val switch = Switch(this).apply { isChecked = prefs.getBoolean(key, default); isClickable = false }
        rowView(title, subtitle, switch).setOnClickListener {
            switch.isChecked = !switch.isChecked
            prefs.edit().putBoolean(key, switch.isChecked).apply()
        }
    }
}
