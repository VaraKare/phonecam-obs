package dev.phonecam.obs

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * The app's screen style in one place: a dark page with a large title, muted section labels, and
 * 56dp rows (title, optional subtitle, and a value, arrow or switch). Used by Settings and Research tools.
 */
class Ui(private val activity: Activity) {
    companion object {
        const val BACKGROUND = 0xFF0E0E0E.toInt(); const val MUTED = 0xFF9A9A9A.toInt(); const val DIVIDER = 0xFF232323.toInt()
        const val RED = 0xFFC62828.toInt(); const val GREY = 0xFF3A3A3A.toInt()
    }
    private val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(32)) }

    /** Sets the activity's content to a scrolling page that respects the system bars. */
    fun page(): ScrollView = ScrollView(activity).apply {
        setBackgroundColor(BACKGROUND); addView(list)
        setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).let { v.setPadding(it.left, it.top, it.right, it.bottom) }
            insets
        }
        activity.setContentView(this)
    }
    fun title(text: String, subtitle: String? = null) {
        list.addView(TextView(activity).apply { this.text = text; setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(16), 0, dp(4)) })
        if (subtitle != null) list.addView(TextView(activity).apply { this.text = subtitle; setTextColor(MUTED); textSize = 14f; setPadding(0, 0, 0, dp(4)) })
    }
    fun section(title: String) = list.addView(TextView(activity).apply { text = title; setTextColor(MUTED); textSize = 13f; setPadding(0, dp(24), 0, dp(4)) })

    private fun rowView(title: String, subtitle: String?, trailing: View?): Pair<LinearLayout, TextView?> {
        var subtitleView: TextView? = null
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56)
            setPadding(0, dp(10), 0, dp(10))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply { text = title; setTextColor(Color.WHITE); textSize = 16f })
                if (subtitle != null) addView(TextView(activity).apply { text = subtitle; setTextColor(MUTED); textSize = 13f; setPadding(0, dp(2), 0, 0) }.also { subtitleView = it })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            trailing?.let { addView(it) }
            background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null)
        }
        list.addView(row)
        list.addView(View(activity).apply { setBackgroundColor(DIVIDER) }, LinearLayout.LayoutParams(-1, 1))
        return row to subtitleView
    }

    /** A tappable row; [value] shows on the right before the arrow. Returns the row and its value label. */
    fun row(title: String, subtitle: String? = null, value: String? = null, chevron: Boolean = true, click: () -> Unit): Pair<View, TextView> {
        val label = TextView(activity).apply { text = listOfNotNull(value, if (chevron) "›" else null).joinToString("  "); setTextColor(MUTED); textSize = 15f; setPadding(dp(12), 0, 0, 0) }
        val (row, _) = rowView(title, subtitle, label)
        row.setOnClickListener { click() }
        return row to label
    }

    /** A row with a switch; the whole row toggles it. */
    @Suppress("DEPRECATION")
    fun toggle(title: String, subtitle: String?, checked: Boolean, changed: (Boolean) -> Unit): Pair<View, Switch> {
        val switch = Switch(activity).apply { isChecked = checked; isClickable = false; isFocusable = false }
        val (row, _) = rowView(title, subtitle, switch)
        row.setOnClickListener { switch.isChecked = !switch.isChecked; changed(switch.isChecked) }
        return row to switch
    }

    /** Full-width primary button: white with black text, or red when it stops something. */
    fun button(text: String, click: () -> Unit): Button = Button(activity).apply {
        this.text = text; textSize = 17f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD; stateListAnimator = null
        setOnClickListener { click() }
        list.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(20) })
    }
    fun style(button: Button, label: String, fill: Int, text: Int, enabled: Boolean = true) {
        button.text = label; button.setTextColor(text); button.isEnabled = enabled
        button.background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(12).toFloat() }
    }
    /** A quiet status card for progress and results. */
    fun note(text: String): TextView = TextView(activity).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(0xFF1A1A1A.toInt()); cornerRadius = dp(10).toFloat() }
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        list.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
    }
}
