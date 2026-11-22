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

/**
 * PhoneCam's pop-up: a dark panel that rises from the bottom, with a title, an optional line of text, options as
 * clean rows (icon, name, a short detail, a blue tick on the current one) and text buttons. Replaces Android's
 * default dialogs so every choice looks the same and stays minimal (owner, 22 Nov).
 */
class Sheet(private val activity: Activity, title: String, message: String? = null) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val dialog = android.app.Dialog(activity)
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
    private val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(16))
        background = GradientDrawable().apply {
            setColor(0xFF171717.toInt())
            cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
        }
        // A small grab line, like the sheets in Android's own apps.
        addView(View(activity).apply { background = GradientDrawable().apply { setColor(0xFF4A4A4A.toInt()); cornerRadius = dp(2).toFloat() } },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
        addView(TextView(activity).apply { text = title; setTextColor(Color.WHITE); textSize = 19f; typeface = Typeface.DEFAULT_BOLD })
        if (message != null) addView(TextView(activity).apply {
            text = message; setTextColor(Ui.MUTED); textSize = 14f; setLineSpacing(0f, 1.15f); setPadding(0, dp(6), 0, 0)
        })
        addView(ScrollView(activity).apply { addView(body); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(8) })
        addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    /** One choice. [selected] shows the blue tick; tapping closes the sheet, then runs [action]. */
    fun option(label: String, detail: String? = null, icon: Int? = null, selected: Boolean = false, enabled: Boolean = true, action: () -> Unit): Sheet {
        body.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(54)
            setPadding(dp(4), dp(8), dp(4), dp(8)); alpha = if (enabled) 1f else 0.4f
            background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() })
            if (icon != null) addView(android.widget.ImageView(activity).apply { setImageResource(icon); setColorFilter(if (selected) BLUE else Color.WHITE) },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply { text = label; setTextColor(if (selected) BLUE else Color.WHITE); textSize = 16f })
                if (detail != null) addView(TextView(activity).apply { text = detail; setTextColor(Ui.MUTED); textSize = 13f; setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) addView(TextView(activity).apply { text = "✓"; setTextColor(BLUE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD })
            isEnabled = enabled
            setOnClickListener { dialog.dismiss(); action() }
        })
        return this
    }
    /** Any view inside the sheet (a text field, a list with icons). */
    fun view(view: View): Sheet { body.addView(view); return this }
    /** A text button at the bottom; [primary] is blue, [danger] red. Closes the sheet, then runs [action]. */
    fun button(label: String, primary: Boolean = false, danger: Boolean = false, action: () -> Unit = {}): Sheet {
        buttons.addView(TextView(activity).apply {
            text = label; textSize = 15f; typeface = if (primary || danger) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(when { danger -> 0xFFFF6B6B.toInt(); primary -> Color.WHITE; else -> 0xFFCFCFCF.toInt() })
            setPadding(dp(18), dp(10), dp(18), dp(10)); minHeight = dp(44); gravity = Gravity.CENTER
            background = if (primary) GradientDrawable().apply { setColor(BLUE); cornerRadius = dp(22).toFloat() }
                else RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(22).toFloat() })
            setOnClickListener { dialog.dismiss(); action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        return this
    }
    fun onDismiss(action: () -> Unit): Sheet { dialog.setOnDismissListener { action() }; return this }
    fun show(): Sheet {
        if (buttons.childCount == 0) button("Close")
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)  // rises from the bottom
            setDimAmount(0.55f)
        }
        dialog.show()
        return this
    }
    private companion object { const val BLUE = 0xFF1E88E5.toInt() }
}
