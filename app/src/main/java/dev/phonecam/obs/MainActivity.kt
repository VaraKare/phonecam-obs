package dev.phonecam.obs

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import android.os.Build
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.graphics.drawable.Icon
import android.util.Rational
import java.util.concurrent.Executors

/** Centers one child at a fixed aspect ratio (letterboxed), like a camera viewfinder. */
class Stage(context: Context) : FrameLayout(context) {
    var aspect = 16f / 9f
        set(value) { field = value; requestLayout() }
    var fitted: View? = null
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        val child = fitted ?: return
        if (measuredWidth == 0 || measuredHeight == 0) return
        var w = measuredWidth; var h = (w / aspect).toInt()
        if (h > measuredHeight) { h = measuredHeight; w = (h * aspect).toInt() }
        child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}

class MainActivity : Activity() {
    private companion object {
        const val RED = 0xFFC62828.toInt(); const val AMBER = 0xFFB26A00.toInt(); const val GREY = 0xFF4A4A4A.toInt()
        const val BLUE = 0xFF1565C0.toInt(); const val PANEL = 0xFF0E0E0E.toInt(); const val CHIP = 0xCC000000.toInt()
        const val MUTED = 0xFFB0B0B0.toInt()
        const val SCAN_REQUEST = 7
        /** Why the last session ended, if its screen was gone by then (AUDIT-014); shown once on the next screen. */
        var lastStop: String? = null
        const val SETTINGS_REQUEST = 8
        const val SCREEN_REQUEST = 9
    }
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var stage: Stage
    private lateinit var preview: SurfaceView
    private lateinit var placeholder: TextView
    private lateinit var badge: TextView
    private lateinit var thermal: TextView
    private var shownPhase = LiveState.Phase.STOPPED
    /** Live timer on the badge, like a recording: counts up from the moment OBS starts receiving. */
    private var liveSince = 0L
    private var liveRoute = ""
    /** A camera or quality change while live: the session stops and starts again; the timer keeps counting. */
    private var switching = false
    private var keepTimer = false
    // The small picture-in-picture window gets a compact badge: the red dot and the timer only.
    private fun liveBadge() = if (inPip) "● ${elapsed()}" else "● Live  ${elapsed()}" + if (liveRoute.isNotEmpty()) " · $liveRoute" else ""
    private fun elapsed(): String {
        val seconds = ((SystemClock.elapsedRealtime() - liveSince) / 1000).coerceAtLeast(0)
        val h = seconds / 3600; val m = seconds / 60 % 60; val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
    private val clock: Runnable = object : Runnable {
        override fun run() {
            if (shownPhase != LiveState.Phase.LIVE || destroyed) return
            badge.text = liveBadge()
            badge.postDelayed(this, 1000 - (SystemClock.elapsedRealtime() - liveSince) % 1000)
        }
    }
    /**
     * Ready (not live): a grey dot and how the phone can reach OBS: a cable icon when a USB cable to a computer is
     * plugged in, a Wi-Fi icon with the paired computer when the phone is on Wi-Fi. Checked every 2 s while shown.
     */
    private val readyBadge: Runnable = object : Runnable {
        override fun run() {
            if (destroyed || shownPhase != LiveState.Phase.STOPPED) return
            val text = android.text.SpannableStringBuilder()
            text.append("●", android.text.style.ForegroundColorSpan(0xFF9E9E9E.toInt()), 0)
            fun icon(res: Int) {
                val d = getDrawable(res)?.mutate()?.apply { setTint(Color.WHITE); setBounds(0, 0, dp(14), dp(14)) } ?: return
                text.append("  ").append(" ", android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_CENTER), 0).append(" ")
            }
            val cable = usbToComputer(this@MainActivity)
            val onWifi = runCatching {
                val net = getSystemService(android.net.ConnectivityManager::class.java)
                net.getNetworkCapabilities(net.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            }.getOrDefault(false)
            val macs = WifiConnector(this@MainActivity).pairings.macs
            val computer = macs.lastOrNull()?.let { it.name + if (macs.size > 1) " +${macs.size - 1}" else "" }
            if (cable) { icon(R.drawable.ic_usb); text.append("USB") }
            if (onWifi && computer != null) { icon(R.drawable.ic_wifi); text.append(computer) }
            if (!cable && !(onWifi && computer != null))
                text.append("  ").append(if (computer == null) "Plug in a USB cable, or pair over Wi-Fi" else "Plug in a USB cable or turn on Wi-Fi")
            badge.text = text
            badge.contentDescription = "Ready." + (if (cable) " USB cable connected." else "") + (if (onWifi && computer != null) " Wi-Fi to $computer." else "")
            badge.postDelayed(this, 2000)
        }
    }
    private val heatListener = PowerManager.OnThermalStatusChangedListener { heat -> if (!destroyed) showHeat(heat) }
    private lateinit var stats: TextView
    private lateinit var panelScroll: ScrollView
    private lateinit var switchButton: ImageButton
    private lateinit var qualityChip: TextView
    private lateinit var mirrorChip: TextView
    private lateinit var micChip: TextView
    private lateinit var soundChip: TextView
    /** Screen sharing (0.7): the phone's own sound too; off unless turned on (it needs Android's audio permission). */
    private var soundOn: Boolean get() = prefs.getBoolean("screen_sound", false); set(v) { prefs.edit().putBoolean("screen_sound", v).apply() }
    /** Phone microphone to OBS (0.6): off unless turned on; RECORD_AUDIO is asked only then. */
    private var micOn: Boolean get() = prefs.getBoolean("mic", false); set(v) { prefs.edit().putBoolean("mic", v).apply() }
    private var serviceStop: ((String) -> Unit)? = null
    private lateinit var detail: TextView
    private lateinit var startButton: Button
    private lateinit var recordButton: RecordButton
    private lateinit var settingsButton: ImageButton
    private lateinit var message: TextView
    private lateinit var stageControls: LinearLayout
    private lateinit var sliderRow: LinearLayout
    private lateinit var sliderLabel: TextView
    private lateinit var slider: SeekBar
    private lateinit var chipRow: LinearLayout
    private lateinit var focusMark: View
    private var sliding: String? = null
    private val feedback by lazy { Feedback(this) }
    private var lastPhase: LiveState.Phase? = null
    private var abilities: Abilities? = null
    private var controls = Controls()
    private var options = emptyList<Profile>()
    private var scanning = false
    private var live: LiveStreamer? = null
    private var liveProfile: Profile? = null
    private var foreground = false
    private var destroyed = false
    @Volatile private var previewReady = false
    private var device = Orientation.UNKNOWN
    private lateinit var orientation: OrientationEventListener
    private val executor = Executors.newSingleThreadExecutor()

    private var cameraId: String? get() = prefs.getString("camera", null); set(v) { prefs.edit().putString("camera", v).apply() }
    private var choice: Quality
        get() = runCatching { Quality.valueOf(prefs.getString("quality", "BEST")!!) }.getOrDefault(Quality.BEST)
        set(v) { prefs.edit().putString("quality", v.name).apply() }
    private var customKey: String? get() = prefs.getString("custom", null); set(v) { prefs.edit().putString("custom", v).apply() }
    private var highQuality: Boolean get() = prefs.getBoolean("hq", true); set(v) { prefs.edit().putBoolean("hq", v).apply() }
    private var showPreview: Boolean get() = prefs.getBoolean("preview", true); set(v) { prefs.edit().putBoolean("preview", v).apply() }
    /** Background live (v0.5, on by default): leaving the app keeps a session running, with a notification. */
    private val backgroundLive get() = prefs.getBoolean("background", true)
    /** Set while live runs with PhoneCam off screen; if the process dies there, the next launch explains why. */
    private var leftLive: Boolean get() = prefs.getBoolean("left_live", false); @Suppress("ApplySharedPref") set(v) { prefs.edit().putBoolean("left_live", v).commit() }  // must reach disk before the phone can close the app
    /** Picture-in-picture (v0.5, on by default): leaving the app while live shows the preview in a small window. */
    /** Screen sharing (0.6): the source the record button starts; [screenLive] while a screen session runs. */
    private val screenMode get() = prefs.getString("source", "camera") == "screen"
    private var screenLive = false
    private lateinit var cameraModeChip: TextView
    private lateinit var screenModeChip: TextView
    private val pipWanted get() = !screenLive && prefs.getBoolean("pip", true) && showPreview &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private var inPip = false
    /** PhoneCam's own Settings is on top: live keeps running even with background live off. */
    private var openingSettings = false
    /** Start after the notification permission prompt closes (the prompt pauses this screen). */
    private var startOnResume = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    /** A small white icon before a chip's words (icons drawn for PhoneCam, like the switch-camera one). */
    private fun TextView.icon(res: Int) {
        val drawable = getDrawable(res)?.mutate()?.apply { setTint(Color.WHITE); setBounds(0, 0, dp(20), dp(20)) }
        setCompoundDrawablesRelative(drawable, null, null, null); compoundDrawablePadding = if (text.isNullOrEmpty()) 0 else dp(6)
    }
    /** An icon-only chip: what it does is in its description (screen readers) and shows on a long press. */
    private fun TextView.iconOnly(res: Int, label: String) {
        text = ""; icon(res); contentDescription = label; tooltipText = label
        setPadding(dp(13), dp(9), dp(13), dp(9))
    }
    private fun rounded(color: Int, radius: Int = 10) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun chip(gravity: Int) = TextView(this).apply {
        setTextColor(Color.WHITE); textSize = 13f; background = rounded(CHIP, 6); setPadding(dp(8), dp(4), dp(8), dp(4))
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity)
            .apply { setMargins(dp(12), dp(12), dp(12), dp(12)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        LiveTransport.token(filesDir)
        if (leftLive && LiveService.stop == null) {
            leftLive = false
            lastStop = "Live ended while PhoneCam was in the background. If you didn't close it, this phone closed it to save battery: " +
                "in Settings → While live, open Let PhoneCam run in the background."
        }

        stage = Stage(this).apply { setBackgroundColor(Color.BLACK) }
        preview = SurfaceView(this).apply {
            visibility = View.INVISIBLE; contentDescription = "Camera preview"
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { previewReady = true; live?.attachPreview() }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                // Leaving the app destroys the preview; background live keeps the camera feeding OBS only.
                override fun surfaceDestroyed(holder: SurfaceHolder) { previewReady = false; live?.detachPreview() }
            })
        }
        stage.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        stage.fitted = preview
        placeholder = TextView(this).apply {
            setTextColor(MUTED); textSize = 16f; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        stage.addView(placeholder)
        badge = chip(Gravity.TOP or Gravity.START).apply { typeface = Typeface.DEFAULT_BOLD; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        thermal = chip(Gravity.TOP or Gravity.END)
        stats = chip(Gravity.TOP or Gravity.START).apply { (layoutParams as FrameLayout.LayoutParams).topMargin = dp(48) }
        stage.addView(badge); stage.addView(thermal); stage.addView(stats)
        focusMark = View(this).apply {
            background = GradientDrawable().apply { setStroke(dp(2), Color.WHITE); cornerRadius = dp(4).toFloat() }
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.START); visibility = View.GONE
        }
        stage.addView(focusMark)
        buildStageControls()
        attachGestures()

        // Below (or beside) the preview: the quality and mirror chips, then switch camera, Start/Stop and
        // Settings in one row, then one line of messages. Camera and quality also work while live (restart).
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        fun pill(click: () -> Unit) = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8)); minHeight = dp(40)
            isClickable = true; isFocusable = true; setOnClickListener { click() }
        }
        qualityChip = pill { chooseQuality() }
        mirrorChip = pill { toggleMirror() }
        micChip = pill { toggleMic() }
        soundChip = pill { togglePhoneSound() }
        cameraModeChip = pill { chooseSource(false) }.apply { iconOnly(R.drawable.ic_videocam, "Camera: send the phone camera") }
        screenModeChip = pill { chooseSource(true) }.apply { iconOnly(R.drawable.ic_screen_share, "Screen: share the phone screen") }
        qualityChip.iconOnly(R.drawable.ic_quality, "Quality")
        mirrorChip.iconOnly(R.drawable.ic_mirror, "Mirror in OBS")
        // One row: the source (camera, screen), a thin divider, then quality, mirror and microphone.
        // Scrolls sideways on very narrow phones rather than squeezing the icons.
        panel.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                fun gap(view: View, start: Int) = addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(start) })
                gap(cameraModeChip, 0); gap(screenModeChip, 6)
                addView(View(this@MainActivity).apply { setBackgroundColor(0xFF3A3A3A.toInt()) },
                    LinearLayout.LayoutParams(dp(1), dp(28)).apply { marginStart = dp(10); marginEnd = dp(10) })
                gap(qualityChip, 0); gap(mirrorChip, 6); gap(soundChip, 0); gap(micChip, 6)
            })
        })
        detail = TextView(this).apply { setTextColor(0xFF7A7A7A.toInt()); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        panel.addView(detail)
        fun round(icon: Int, description: String, click: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon); contentDescription = description; setColorFilter(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1), 0xFF3A3A3A.toInt()) }
            setOnClickListener { click() }
        }
        switchButton = round(R.drawable.ic_switch_camera, "Switch camera") { switchCamera() }
        settingsButton = round(R.drawable.ic_tune, "Settings") { openSettings() }
        switchButton.tooltipText = "Switch camera"; settingsButton.tooltipText = "Settings"
        startButton = Button(this).apply {
            textSize = 17f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD; stateListAnimator = null
            setOnClickListener { onStartPressed() }
        }
        // Ready and live: the round record button. Setup steps (camera access, camera check) keep a worded button.
        recordButton = RecordButton(this).apply { setOnClickListener { onStartPressed() } }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0)
            addView(switchButton, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(FrameLayout(this@MainActivity).apply {
                addView(startButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), Gravity.CENTER))
                addView(recordButton, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
            addView(settingsButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        })
        message = TextView(this).apply { setTextColor(MUTED); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        panel.addView(message)
        panelScroll = ScrollView(this).apply { setBackgroundColor(PANEL); addView(panel) }

        root = LinearLayout(this).apply { setBackgroundColor(Color.BLACK); addView(stage); addView(panelScroll) }
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                val next = Orientation.quantize(degrees, device)
                if (next == device) return
                device = next
                val profile = liveProfile ?: return
                if (screenLive) return  // the screen turns itself; Android reports the new shape
                live?.setRotation(Orientation.encodedRotation(profile.sensorOrientation, profile.facing, device))
            }
        }
        arrange()
        showState(null)
        refresh()
    }

    /** Portrait: viewfinder above the controls. Landscape: viewfinder left, controls right. */
    private fun arrange() {
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        root.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        stage.layoutParams = LinearLayout.LayoutParams(if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else 0,
            if (portrait) 0 else ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        panelScroll.layoutParams = if (portrait) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.MATCH_PARENT)
        val p = liveProfile ?: selected()
        stage.aspect = if (p == null) (if (portrait) 9f / 16f else 16f / 9f)
            else if (portrait) p.height.toFloat() / p.width else p.width.toFloat() / p.height
        updatePip()
    }

    /**
     * Picture-in-picture follows the session: it opens by itself on Home only while live (Android 12+;
     * older versions use [onUserLeaveHint]), with the preview's shape and a Stop button.
     */
    private fun pipParams(): PictureInPictureParams {
        val aspect = stage.aspect.coerceIn(0.43f, 2.3f)  // Android allows 1:2.39 to 2.39:1
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveService::class.java).setAction(LiveService.STOP), PendingIntent.FLAG_IMMUTABLE)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((aspect * 1000).toInt(), 1000))
            .setActions(listOf(RemoteAction(Icon.createWithResource(this, R.drawable.ic_stop), "Stop", "Stop live", stop)))
            .apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(pipWanted && shownPhase == LiveState.Phase.LIVE && live != null) }
            .build()
    }
    private fun updatePip() {
        if (!::stage.isInitialized || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && pipWanted && live != null && shownPhase == LiveState.Phase.LIVE)
            runCatching { enterPictureInPictureMode(pipParams()) }
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // Only the picture and the live badge in the small window; everything returns when it opens full screen.
        panelScroll.visibility = if (inPip) View.GONE else View.VISIBLE
        badge.textSize = if (inPip) 10f else 13f
        badge.setPadding(dp(if (inPip) 5 else 8), dp(if (inPip) 2 else 4), dp(if (inPip) 5 else 8), dp(if (inPip) 2 else 4))
        (badge.layoutParams as FrameLayout.LayoutParams).setMargins(dp(if (inPip) 6 else 12), dp(if (inPip) 6 else 12), dp(12), dp(12))
        if (shownPhase == LiveState.Phase.LIVE) badge.text = liveBadge()
        if (inPip) { stageControls.visibility = View.GONE; stats.visibility = View.GONE; thermal.visibility = View.GONE; focusMark.visibility = View.GONE }
        else refresh()
    }

    private fun qualityChoices() = listOfNotNull(Quality.BEST, Quality.HIGH, Quality.BALANCED,
        Quality.SMOOTH.takeIf { cameraId?.let { id -> Quality.pick(options, id, Quality.SMOOTH) } != null || choice == Quality.SMOOTH },
        Quality.CUSTOM.takeIf { customKey != null })
    private fun selected(): Profile? {
        val id = cameraId ?: return null
        return Quality.pick(options, id, choice, customKey)
    }

    /** Rebuilds the camera chips, quality list and button from the scan and saved settings. */
    private fun refresh() {
        val permitted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val cameras = Quality.cameras(options)
        if (cameras.isNotEmpty() && cameras.none { it.first == cameraId }) cameraId = cameras.first().first
        val name = cameras.firstOrNull { it.first == cameraId }?.second
        val next = cameras.indexOfFirst { it.first == cameraId }.let { if (cameras.size > 1) cameras[(it + 1) % cameras.size].second else null }
        switchButton.isEnabled = next != null && !switching
        switchButton.alpha = if (switchButton.isEnabled) 1f else 0.4f
        switchButton.contentDescription = if (next != null) "Switch to the $next camera" else "Only one camera"
        val profile = selected()
        qualityChip.tooltipText = "Quality: ${choice.label}"
        qualityChip.background = rounded(0xFF1E1E1E.toInt(), 20)
        qualityChip.contentDescription = "Quality: ${choice.label}. Tap to change"
        val mirror = cameraId?.let { prefs.getBoolean("ctl.$it.mirror", false) } ?: false

        mirrorChip.background = rounded(if (mirror) BLUE else 0xFF1E1E1E.toInt(), 20)
        mirrorChip.contentDescription = "Mirror the picture in OBS, ${if (mirror) "on" else "off"}"
        val screen = screenMode
        cameraModeChip.background = rounded(if (!screen) BLUE else 0xFF1E1E1E.toInt(), 20)
        screenModeChip.background = rounded(if (screen) BLUE else 0xFF1E1E1E.toInt(), 20)
        cameraModeChip.isEnabled = live == null; screenModeChip.isEnabled = live == null
        qualityChip.visibility = if (screen) View.GONE else View.VISIBLE
        mirrorChip.visibility = if (screen) View.GONE else View.VISIBLE
        soundChip.visibility = if (screen) View.VISIBLE else View.GONE
        soundChip.iconOnly(if (soundOn) R.drawable.ic_volume else R.drawable.ic_volume_off, "Phone sound ${if (soundOn) "on" else "off"} (screen sharing)")
        soundChip.background = rounded(if (soundOn) BLUE else 0xFF1E1E1E.toInt(), 20)
        if (screen) { switchButton.isEnabled = false; switchButton.alpha = 0.4f }
        micChip.iconOnly(if (micOn) R.drawable.ic_mic else R.drawable.ic_mic_off, "Phone microphone ${if (micOn) "on" else "off"}")
        micChip.background = rounded(if (micOn) BLUE else 0xFF1E1E1E.toInt(), 20)
        micChip.contentDescription = "Phone microphone to OBS, ${if (micOn) "on" else "off"}"
        detail.text = when {
            screenMode -> "Phone screen to OBS · up to 1080p · Android asks before sharing"
            options.isEmpty() -> ""
            profile == null -> "$name camera: this quality isn't available. Choose another."
            else -> "${choice.label.substringBefore(" (")} · $name camera · ${Quality.describe(profile).substringAfter(" · ")}" + if (highQuality && profile.highQuality) " · sharpening" else ""
        }
        settingsButton.isEnabled = !switching; settingsButton.alpha = if (switching) 0.4f else 1f
        fun button(label: String, fill: Int, text: Int, enabled: Boolean) {
            startButton.text = label; startButton.background = rounded(fill, 27); startButton.setTextColor(text); startButton.isEnabled = enabled
            startButton.visibility = View.VISIBLE; recordButton.visibility = View.GONE
        }
        fun record(isLive: Boolean, enabled: Boolean) {
            recordButton.live = isLive; recordButton.isEnabled = enabled
            recordButton.visibility = View.VISIBLE; startButton.visibility = View.GONE
        }
        when {
            switching -> record(true, false)
            live != null -> record(true, true)
            !permitted -> button("Allow camera access", Color.WHITE, Color.BLACK, true)
            scanning || options.isEmpty() -> button(if (scanning) "Checking camera…" else "Check camera", GREY, Color.WHITE, !scanning)
            else -> record(false, profile != null)
        }
        if (live == null) arrange()
    }

    /** The heat chip follows Android's thermal status, also after Stop (it used to freeze on "Hot" once live ended). */
    private fun showHeat(heat: Int) {
        thermal.text = when { heat >= PowerManager.THERMAL_STATUS_SEVERE -> "Hot — consider stopping"; heat >= PowerManager.THERMAL_STATUS_MODERATE -> "Warm"; else -> "Cool" }
        thermal.background = rounded(if (heat >= PowerManager.THERMAL_STATUS_SEVERE) RED else if (heat >= PowerManager.THERMAL_STATUS_MODERATE) AMBER else CHIP, 6)
        thermal.visibility = if (!inPip && (shownPhase == LiveState.Phase.LIVE || heat >= PowerManager.THERMAL_STATUS_MODERATE)) View.VISIBLE else View.GONE
    }

    private fun showState(state: LiveState?) {
        val phase = state?.phase ?: LiveState.Phase.STOPPED
        if (phase != lastPhase) {
            // Sound and vibration only for real transitions, so a reopened screen stays quiet.
            if (phase == LiveState.Phase.LIVE) {
                if (keepTimer) keepTimer = false else { feedback.play(Feedback.Event.LIVE, badge); liveSince = SystemClock.elapsedRealtime() }
            }
            else if (phase == LiveState.Phase.STOPPED && !switching && foreground && lastPhase != null && lastPhase != LiveState.Phase.STOPPED)
                feedback.play(if (state?.message?.startsWith("Stopped") == true) Feedback.Event.STOPPED else Feedback.Event.ERROR, badge)
            lastPhase = phase
        }
        // The computer used most recently (pairings are kept in order of use), plus how many others are paired.
        badge.removeCallbacks(clock)
        if (phase == LiveState.Phase.LIVE) badge.post(clock)
        if (phase == LiveState.Phase.LIVE && state != null && state.route.isNotEmpty()) liveRoute = state.route
        badge.removeCallbacks(readyBadge)
        if (phase == LiveState.Phase.STOPPED) badge.post(readyBadge)  // after shownPhase below is updated
        else badge.text = when (phase) { LiveState.Phase.LIVE -> liveBadge(); else -> if (keepTimer) "Switching…" else "● Waiting for OBS…" }
        badge.background = rounded(when (phase) { LiveState.Phase.LIVE -> RED; LiveState.Phase.WAITING -> AMBER; else -> CHIP }, 6)
        badge.visibility = View.VISIBLE
        val p = liveProfile
        stats.visibility = if (phase == LiveState.Phase.LIVE && p != null) View.VISIBLE else View.GONE
        if (state != null && p != null) stats.text = "${Quality.short(p).substringBefore(" ")} · ${"%.0f".format(state.fps)} fps · ${state.drops} dropped" +
            if (state.bitrate in 1 until state.requestedBitrate) "\nLink busy: ${state.bitrate / 1_000_000} of ${state.requestedBitrate / 1_000_000} Mb/s" else ""
        val changed = shownPhase != phase
        shownPhase = phase
        if (changed) updatePip()  // after shownPhase, which decides whether Home opens the small window
        showHeat(runCatching { getSystemService(PowerManager::class.java).currentThermalStatus }.getOrDefault(0))
        stageControls.visibility = if (phase == LiveState.Phase.LIVE && !inPip) View.VISIBLE else View.GONE
        if (inPip) stats.visibility = View.GONE
        val previewing = phase != LiveState.Phase.STOPPED && showPreview && !screenLive
        placeholder.visibility = if (phase == LiveState.Phase.LIVE && previewing) View.GONE else View.VISIBLE
        placeholder.text = when (phase) {
            LiveState.Phase.LIVE -> if (screenLive) "Sharing your screen with OBS.\nGo to any app; Stop is in the notification." else "Live. Preview is off."
            LiveState.Phase.WAITING -> "Waiting for OBS…\nOpen OBS with the PhoneCam source visible."
            else -> "Camera off"
        }
    }

    /** Largest SurfaceHolder size with the profile's aspect ratio, at most 1080p (guaranteed PRIV preview + record). */
    private fun previewSize(profile: Profile): Size? {
        val map = getSystemService(CameraManager::class.java).getCameraCharacteristics(profile.cameraId)[
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: return null
        return map.getOutputSizes(SurfaceHolder::class.java).orEmpty()
            .filter { it.width * profile.height == it.height * profile.width && it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width * it.height }
    }

    private fun scan() {
        if (scanning || live != null) return
        scanning = true; message.text = "Checking what this phone's cameras support…"; refresh()
        executor.execute {
            val result = runCatching { Capabilities.scan(this).profiles }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                scanning = false
                result.onSuccess {
                    options = it
                    message.text = if (it.isEmpty()) "No supported camera profile was found on this phone."
                        else lastStop?.also { lastStop = null } ?: "Ready. Open OBS with the PhoneCam source, then tap the red record button."
                }.onFailure { message.text = "Camera check failed: ${it.message}. Close other camera apps and try again." }
                refresh()
            }
        }
    }

    private fun onStartPressed() {
        when {
            live != null -> live?.stop()
            screenMode && !(Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notifications", false) &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) -> askScreen()
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED -> requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            options.isEmpty() -> scan()
            // Asked once, at the first Start: the background-live notification carries Stop.
            Build.VERSION.SDK_INT >= 33 && backgroundLive && !prefs.getBoolean("asked_notifications", false) &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                prefs.edit().putBoolean("asked_notifications", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            else -> startLive()
        }
    }

    @Suppress("DEPRECATION")
    private fun displayDegrees() = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
    }

    private fun startLive() {
        if (!foreground || live != null) return
        if (micOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message.text = "The microphone is on, but PhoneCam isn't allowed to use it. Tap Mic to allow it, or turn it off."
            micOn = false; refresh(); return
        }
        val profile = selected() ?: run { message.text = "This quality isn't available on this camera."; return }
        if (device == Orientation.UNKNOWN) device = Orientation.fromDisplay(displayDegrees())
        val rotation = Orientation.encodedRotation(profile.sensorOrientation, profile.facing, device)
        val wantPreview = showPreview
        if (wantPreview) {
            val size = previewSize(profile)
            if (size == null) { message.text = "No preview size matches this quality. Turn off the phone preview in Settings, or pick another quality."; return }
            preview.holder.setFixedSize(size.width, size.height)
            preview.visibility = View.VISIBLE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        liveProfile = profile
        val hq = highQuality && profile.highQuality
        val able = Abilities.from(getSystemService(CameraManager::class.java).getCameraCharacteristics(profile.cameraId))
        abilities = able
        controls = loadControls(profile.cameraId); sliding = null
        renderControls()
        val app = applicationContext
        warnings.reset(); lastStatus = ""
        live = LiveStreamer(this, profile, hq, rotation, able, controls, { if (wantPreview && previewReady) preview.holder.surface else null },
            onCommand = { command -> if (!destroyed) obeyObs(command) }, microphone = micOn, notify = ::onLiveState).also { it.start() }
        // The notification's and the picture-in-picture window's Stop reach the session through LiveService.
        live?.let { session -> val stop = { reason: String -> session.stop(reason) }; serviceStop = stop
            if (backgroundLive) LiveService.start(app, stop, LiveService.Uses(screen = false, microphone = micOn)) else LiveService.stop = stop }
        LiveService.lower = { if (!destroyed) lowerQuality() }
        scheduleDim()
        arrange(); refresh()
    }

    /** Every state change of the session (camera or screen), on the main thread. */
    private fun onLiveState(state: LiveState) {
        if (state.phase == LiveState.Phase.STOPPED) { LiveService.end(applicationContext); if (leftLive) leftLive = false }
        if (destroyed) { if (state.phase == LiveState.Phase.STOPPED) lastStop = state.message; return }
        if (switching && state.phase == LiveState.Phase.LIVE && !state.switching && state.frames > 0) { switching = false; refresh() }
        showState(state)
        LiveService.update(applicationContext, state, liveSince)
        if (state.phase == LiveState.Phase.LIVE) tickLive(state)
        message.text = state.message
        if (state.phase == LiveState.Phase.STOPPED) {
            if (!foreground) orientation.disable()
            brighten()
            // Never end silently while away: OBS closed, cable out, phone too hot…
            if (!foreground && !inPip && !switching && !state.message.startsWith("Stopped by you")) warnings.ended(state.message)
            if (!switching && !screenLive && state.frames == 0L && choice == Quality.BEST && state.message != "Stopped by you")
                message.text = "${state.message}\nIf this keeps happening, choose Quality: High."
            live = null; liveProfile = null; sliding = null; focusMark.visibility = View.GONE; screenLive = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            preview.visibility = View.INVISIBLE
            if (switching) {
                // Restart with the new camera or quality; OBS keeps its source and reconnects.
                switching = false; keepTimer = true
                message.text = "Switching…"
                badge.postDelayed({ if (!destroyed) { startLive(); if (live == null) keepTimer = false } }, 300)
            }
            refresh()
        }
    }

    // ---- Screen sharing (v0.6) ----

    private fun chooseSource(screen: Boolean) {
        if (live != null) return
        prefs.edit().putString("source", if (screen) "screen" else "camera").apply()
        message.text = if (screen) "Screen: tap the record button, then allow screen sharing. Android asks every time."
            else "Ready. Open OBS with the PhoneCam source, then tap the red record button."
        refresh()
    }
    /** Android's own consent, every time (AGENTS.md). */
    private fun askScreen() {
        if (live != null) return
        if (micOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message.text = "The microphone is on, but PhoneCam isn't allowed to use it. Tap Mic to allow it, or turn it off."
            micOn = false; refresh(); return
        }
        if (soundOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message.text = "Phone sound is on, but PhoneCam isn't allowed to record audio. Tap the speaker to allow it, or turn it off."
            soundOn = false; refresh(); return
        }
        // Android 14+ can share one app or the entire screen; PhoneCam asks first (or uses the Settings choice).
        if (Build.VERSION.SDK_INT >= 34) when (prefs.getString("screen_scope", "ask")) {
            "entire" -> requestScreen(entire = true)
            "app" -> requestScreen(entire = false)
            else -> Sheet(this, "Share what?")
                .option("Entire screen", "Everything on the phone, as you move between apps", R.drawable.ic_screen_share) { requestScreen(entire = true) }
                .option("One app", "Only the app you pick next", R.drawable.ic_videocam) { requestScreen(entire = false) }
                .button("Cancel").show()
        } else requestScreen(entire = true)  // Android 10–13 always share the entire screen
    }
    private fun requestScreen(entire: Boolean) {
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(
            if (entire) android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            else android.media.projection.MediaProjectionConfig.createConfigForUserChoice())
            else manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION") startActivityForResult(intent, SCREEN_REQUEST)
    }
    /** Phone sound while screen sharing. Android files it under audio recording, so that permission is asked first. */
    private fun togglePhoneSound() {
        if (!soundOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message.text = "Android asks for audio permission because it counts the phone's own sound as recording. PhoneCam sends it only to OBS."
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 5); return
        }
        soundOn = !soundOn; live?.setPhoneSound(soundOn)
        if (soundOn && live != null && live?.talks == false) message.text = "The PhoneCam plugin in OBS is older than this app and can't take sound from the phone."
        refresh()
    }
    /** Android 14 wants the screen-sharing service running before the projection is made, so it starts first. */
    private fun beginScreen(code: Int, data: Intent) {
        val (w, h) = ScreenSource.displaySize(this)
        val profile = ScreenSource.profile(w, h) ?: run { message.text = "This phone has no hardware video encoder for its screen size."; return }
        message.text = "Starting screen sharing…"
        LiveService.onForeground = { ok -> runOnUiThread { if (ok) createScreenSession(code, data, profile) else message.text = "Android didn't allow screen sharing to start. Try again." } }
        val stop = { reason: String -> live?.stop(reason) ?: LiveService.end(applicationContext) }
        serviceStop = stop
        if (!LiveService.start(applicationContext, stop, LiveService.Uses(screen = true, microphone = micOn))) {
            LiveService.onForeground = null
            message.text = "Android didn't allow screen sharing to start. Try again."
        }
    }
    private fun createScreenSession(code: Int, data: Intent, profile: Profile) {
        if (destroyed || live != null) return
        val projection = runCatching {
            getSystemService(android.media.projection.MediaProjectionManager::class.java).getMediaProjection(code, data)
        }.getOrNull() ?: run { message.text = "Screen sharing couldn't start. Try again."; LiveService.end(applicationContext); return }
        screenLive = true; liveProfile = profile
        abilities = ScreenSource.noAbilities; controls = Controls(); sliding = null
        warnings.reset(); lastStatus = ""
        live = LiveStreamer(this, profile, false, 0, ScreenSource.noAbilities, Controls(), { null },
            onCommand = { command -> if (!destroyed) obeyObs(command) }, microphone = micOn, screen = projection, phoneSound = soundOn,
            notify = ::onLiveState)
            .also { it.start() }
        LiveService.lower = null
        scheduleDim()
        arrange(); refresh()
    }

    // ---- OBS controls, status and warnings (v0.6) ----

    private val warnings by lazy { Warnings(this) }
    private var lastStatus = ""
    private fun shortQuality(q: Quality) = q.label.substringBefore(" ")

    /** Every status tick while live (about once a second, also in the background): warnings and OBS's Phone section. */
    private fun tickLive(state: LiveState) {
        val battery = getSystemService(android.os.BatteryManager::class.java)
        val percent = runCatching { battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1)
        val charging = runCatching { battery.isCharging }.getOrDefault(false)
        val heat = runCatching { getSystemService(PowerManager::class.java).currentThermalStatus }.getOrDefault(0)
        warnings.check(prefs.getBoolean("warnings", true), heat, percent, charging, choice != Quality.BALANCED)
        val session = live ?: return
        if (!session.talks) return
        val p = liveProfile
        val text = buildString {
            if (p != null && !screenLive) {
                append("camera=").append(Quality.cameras(options).firstOrNull { it.first == p.cameraId }?.second.orEmpty()).append('\n')
                append("cameras=").append(Quality.cameras(options).joinToString(",") { it.second }).append('\n')
                append("torch=").append(if (abilities?.torch != true) "none" else if (controls.torch) "on" else "off").append('\n')
                append("mirror=").append(if (controls.mirror) "on" else "off").append('\n')
            }
            if (screenLive) append("mode=screen\n") else {
                append("quality=").append(shortQuality(choice)).append('\n')
                append("qualities=").append(qualityChoices().joinToString(",") { shortQuality(it) }).append('\n')
            }
            append("route=").append(state.route).append('\n')
            if (percent >= 0) append("battery=").append(percent).append('\n')
            append("charging=").append(if (charging) "yes" else "no").append('\n')
            warnings.current?.let { append("warning=").append(it).append('\n') }
            append("mic=").append(if (micOn) "on" else "off").append('\n')
            if (screenLive) append("sound=").append(if (soundOn) "on" else "off").append('\n')
        }
        if (text != lastStatus) { lastStatus = text; session.sendStatus(text) }
    }

    /** A command from OBS's Properties → Phone or an OBS hotkey; the same paths as the phone's own buttons. */
    private fun obeyObs(command: String) {
        val verb = command.substringBefore(' ').trim(); val arg = command.substringAfter(' ', "").trim()
        if (screenLive && verb != "stop") return  // camera controls don't apply to the screen
        when (verb) {
            "camera" -> if (live != null && !switching) {
                val cameras = Quality.cameras(options)
                // "camera next", or a camera by its name ("camera Front") from OBS's list.
                val target = if (arg == "next") null else cameras.firstOrNull { it.second.equals(arg, ignoreCase = true) } ?: return
                if (target == null) switchCamera()
                else if (target.first != cameraId) { cameraId = target.first; feedback.play(Feedback.Event.TICK, switchButton); refresh(); restartLive() }
            }
            "quality" -> qualityChoices().firstOrNull { shortQuality(it).equals(arg, ignoreCase = true) }?.let { q ->
                if (q == choice || switching) return
                choice = q; refresh()
                if (live != null && selected() != liveProfile) restartLive()
            }
            "torch" -> if (live != null && abilities?.torch == true) { updateControls(controls.copy(torch = arg == "on")); renderControls() }
            "mirror" -> if (live != null) { updateControls(controls.copy(mirror = arg == "on")); refresh() }
            "stop" -> live?.stop("Stopped from OBS")
        }
    }

    /** The warning's Lower quality button: Best or Smooth → High → Balanced. */
    private fun lowerQuality() {
        if (screenLive) return
        val next = when (choice) { Quality.BEST, Quality.SMOOTH, Quality.CUSTOM -> Quality.HIGH; Quality.HIGH -> Quality.BALANCED; Quality.BALANCED -> return }
        val id = cameraId ?: return
        if (Quality.pick(options, id, next, customKey) == null || switching) return
        choice = next; refresh()
        if (live != null && selected() != liveProfile) restartLive()
        message.text = "Quality lowered to ${shortQuality(next)} to cool the phone and save battery."
    }

    // Dims the screen after 30 s of live without a touch (on screen, not picture-in-picture); a touch brightens it.
    private val dimmer = Runnable {
        if (live != null && foreground && !inPip && prefs.getBoolean("dim", true))
            window.attributes = window.attributes.apply { screenBrightness = 0.05f }
    }
    private fun scheduleDim() { badge.removeCallbacks(dimmer); if (live != null) badge.postDelayed(dimmer, 30_000) }
    private fun brighten() {
        badge.removeCallbacks(dimmer)
        if (window.attributes.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && live != null) {
            val dimmed = window.attributes.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            brighten(); scheduleDim()
            if (dimmed) return true  // the first touch only wakes the screen, like a dimmed camera app
        }
        return super.dispatchTouchEvent(event)
    }

    // ---- Live camera controls (v0.2) ----

    /** Zoom, exposure and white balance and the OBS mirror are remembered per camera; torch and focus reset each session. */
    private fun loadControls(id: String) = Controls(
        zoom = prefs.getFloat("ctl.$id.zoom", 1f), exposure = prefs.getInt("ctl.$id.ev", 0),
        whiteBalance = prefs.getInt("ctl.$id.wb", CaptureRequest.CONTROL_AWB_MODE_AUTO), mirror = prefs.getBoolean("ctl.$id.mirror", false))

    private fun updateControls(next: Controls, refocus: Boolean = false) {
        controls = next
        liveProfile?.cameraId?.let { id ->
            prefs.edit().putFloat("ctl.$id.zoom", next.zoom).putInt("ctl.$id.ev", next.exposure)
                .putInt("ctl.$id.wb", next.whiteBalance).putBoolean("ctl.$id.mirror", next.mirror).apply()
        }
        live?.setControls(next, refocus)
        renderControls()
    }

    private fun buildStageControls() {
        sliderLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; minWidth = dp(64) }
        slider = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) sliderMoved(progress) }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rounded(CHIP, 8)
            setPadding(dp(12), dp(4), dp(8), dp(4)); visibility = View.GONE
            addView(sliderLabel); addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stageControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply { setMargins(dp(8), 0, dp(8), dp(8)) }
            addView(sliderRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
            addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
        }
        stage.addView(stageControls)
    }

    private fun zoomProgress(zoom: Float, max: Float) = if (max <= 1f) 0 else (100 * kotlin.math.ln(zoom) / kotlin.math.ln(max)).toInt().coerceIn(0, 100)
    private fun exposureText(a: Abilities, ev: Int) = "%+.1f EV".format(ev * a.exposureStep)

    private fun sliderMoved(progress: Int) {
        val a = abilities ?: return
        when (sliding) {
            "zoom" -> updateControls(controls.copy(zoom = Math.pow(a.maxZoom.toDouble(), progress / 100.0).toFloat().coerceIn(1f, a.maxZoom)))
            "exposure" -> updateControls(controls.copy(exposure = a.exposureMin + progress))
        }
    }

    /** Rebuilds the chips under the preview from the camera's abilities and current values. */
    private fun renderControls() {
        val a = abilities ?: return
        chipRow.removeAllViews()
        fun add(text: String, description: String, on: Boolean = false, click: () -> Unit) = chipRow.addView(TextView(this).apply {
            this.text = text; contentDescription = description; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            background = rounded(if (on) BLUE else CHIP, 16); setPadding(dp(12), dp(8), dp(12), dp(8)); minHeight = dp(40)
            isClickable = true; isFocusable = true; setOnClickListener { click() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        fun toggleSlider(name: String) { sliding = if (sliding == name) null else name; renderControls() }
        if (a.maxZoom > 1f) add("%.1f×".format(controls.zoom), "Zoom ${"%.1f".format(controls.zoom)} times", sliding == "zoom") { toggleSlider("zoom") }
        if (a.exposureStep > 0f && a.exposureMax > a.exposureMin)
            add("☀ " + exposureText(a, controls.exposure), "Brightness ${exposureText(a, controls.exposure)}", sliding == "exposure") { toggleSlider("exposure") }
        if (a.whiteBalances.size > 1) add("WB " + Abilities.whiteBalanceName(controls.whiteBalance), "White balance ${Abilities.whiteBalanceName(controls.whiteBalance)}, tap to change") {
            val list = a.whiteBalances
            updateControls(controls.copy(whiteBalance = list[(list.indexOf(controls.whiteBalance) + 1) % list.size]))
        }
        if (a.torch) add(if (controls.torch) "Torch on" else "Torch off", "Torch ${if (controls.torch) "on" else "off"}", controls.torch) {
            updateControls(controls.copy(torch = !controls.torch))
        }
        if (a.tapFocus || a.tapExposure) {
            val locked = controls.focusX != null
            add(if (locked) "Focus: tapped ✕" else "Focus: auto", if (locked) "Focus on tapped point, tap to return to auto" else "Auto focus. Tap the preview to focus on a point", locked) {
                if (locked) { focusMark.visibility = View.GONE; updateControls(controls.copy(focusX = null, focusY = null)) }
                else message.text = "Tap the preview to focus and expose for that point. Pinch to zoom."
            }
        }
        sliderRow.visibility = if (sliding != null) View.VISIBLE else View.GONE
        when (sliding) {
            "zoom" -> { slider.max = 100; slider.progress = zoomProgress(controls.zoom, a.maxZoom); sliderLabel.text = "Zoom"; slider.contentDescription = "Zoom" }
            "exposure" -> { slider.max = a.exposureMax - a.exposureMin; slider.progress = controls.exposure - a.exposureMin; sliderLabel.text = "Brightness"; slider.contentDescription = "Brightness" }
        }
    }

    /** Pinch to zoom; tap to focus and meter on a point. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachGestures() {
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val a = abilities ?: return false
                if (live == null || a.maxZoom <= 1f) return false
                updateControls(controls.copy(zoom = (controls.zoom * detector.scaleFactor).coerceIn(1f, a.maxZoom)))
                return true
            }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusAt(e.x, e.y); return true }
        })
        preview.setOnTouchListener { view, event ->
            pinch.onTouchEvent(event)
            if (!pinch.isInProgress) taps.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val a = abilities ?: return; val p = liveProfile ?: return
        if (live == null || (!a.tapFocus && !a.tapExposure) || preview.width == 0 || preview.height == 0) return
        val (sx, sy) = CameraGeometry.viewToSensor(x / preview.width, y / preview.height, p.sensorOrientation,
            p.facing == CameraCharacteristics.LENS_FACING_FRONT, displayDegrees())
        focusMark.translationX = preview.left + x - dp(32); focusMark.translationY = preview.top + y - dp(32)
        focusMark.visibility = View.VISIBLE
        updateControls(controls.copy(focusX = sx, focusY = sy), refocus = true)
    }

    /** Settings open during live too; options that need the camera or restart the session wait until Stop. */
    private fun openSettings() {
        if (switching) return
        if (live != null) {
            openingSettings = true
            if (Build.VERSION.SDK_INT >= 31) runCatching { setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
        }
        @Suppress("DEPRECATION") startActivityForResult(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_LIVE, live != null), SETTINGS_REQUEST)
    }

    private fun typePairingCode() {
        val input = EditText(this).apply {
            hint = "e.g. 000G-40R4-0M30-E209"; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        input.setTextColor(Color.WHITE); input.setHintTextColor(0xFF6A6A6A.toInt())
        Sheet(this, "Wi-Fi pairing code", "In OBS: PhoneCam source → Properties → Pair a phone over Wi-Fi. Type the 16-character code it shows. Same Wi-Fi on both.")
            .view(FrameLayout(this).apply { setPadding(0, dp(8), 0, dp(4)); addView(input) })
            .button("Cancel")
            .button("Pair", primary = true) {
                val code = WifiCrypto.decodeCode(input.text.toString())
                if (code == null) { message.text = "The code has 16 letters and numbers, like 000G-40R4-0M30-E209. Check it and try again."; return@button }
                message.text = "Looking for OBS on this Wi-Fi…"
                Thread {
                    val result = runCatching { WifiConnector(this).pair(code) }
                    runOnUiThread {
                        message.text = result.fold({ "Paired with $it. Tap the record button with the phone and computer on the same Wi-Fi." }, { Errors.friendly(it) })
                        feedback.play(if (result.isSuccess) Feedback.Event.SUCCESS else Feedback.Event.ERROR, message)
                    }
                }.start()
            }.show()
    }
    @Deprecated("Activity result API is not used in this app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_REQUEST) {
            if (resultCode == RESULT_OK && data != null) beginScreen(resultCode, data)
            else message.text = "Screen sharing wasn't allowed. Tap the record button to ask again."
            return
        }
        if (requestCode == SCAN_REQUEST && resultCode == RESULT_OK) message.text = data?.getStringExtra("message") ?: "Paired."
        if (requestCode == SCAN_REQUEST && resultCode == ScanActivity.RESULT_TYPE_CODE) typePairingCode()
        if (requestCode == SETTINGS_REQUEST) {
            when (data?.getStringExtra(SettingsActivity.EXTRA_ACTION)) {
                SettingsActivity.SCAN -> @Suppress("DEPRECATION") startActivityForResult(Intent(this, ScanActivity::class.java), SCAN_REQUEST)
                SettingsActivity.TYPE_CODE -> typePairingCode()
                SettingsActivity.CUSTOM -> chooseCustom()
                SettingsActivity.RESCAN -> { options = emptyList(); refresh(); scan() }
            }
            refresh()
        }
    }

    private fun chooseQuality() {
        if (options.isEmpty() || switching) return
        val id = cameraId ?: return
        val choices = qualityChoices()
        val hints = mapOf(Quality.BEST to "Sharpest; needs a fast computer", Quality.HIGH to "Best for most streams",
            Quality.BALANCED to "For busy Wi-Fi", Quality.SMOOTH to "60 fps for motion (experimental)", Quality.CUSTOM to "Your own size and bitrate")
        val sheet = Sheet(this, "Quality", if (live != null) "Changing it restarts the picture for about a second." else null)
        for (q in choices) {
            val profile = Quality.pick(options, id, q, customKey)
            sheet.option(q.label.substringBefore(" ("), (profile?.let { Quality.short(it) + " · " } ?: "Not on this camera · ") + hints[q],
                selected = q == choice, enabled = profile != null) {
                if (q == choice) return@option
                choice = q; refresh()
                // Restart only when the picture would change: on some cameras two qualities give the same settings.
                if (live != null && selected() != liveProfile) restartLive()
            }
        }
        sheet.button("Cancel").show()
    }

    /** Next camera (rear, front, …). While live, the session restarts with it. */
    private fun switchCamera() {
        if (switching) return
        val cameras = Quality.cameras(options)
        if (cameras.size < 2) return
        val index = cameras.indexOfFirst { it.first == cameraId }
        cameraId = cameras[(index + 1) % cameras.size].first
        feedback.play(Feedback.Event.TICK, switchButton)
        refresh()
        if (live != null) restartLive()
    }

    /** Phone microphone on or off, also while live. Turning it on asks for the permission the first time. */
    private fun toggleMic() {
        if (!micOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3); return
        }
        setMic(!micOn)
    }
    private fun setMic(on: Boolean) {
        micOn = on; live?.setMicrophone(on)
        // Background live needs Android's microphone type to keep hearing when PhoneCam is off screen.
        val stop = serviceStop
        if (live != null && backgroundLive && stop != null) LiveService.start(applicationContext, stop, LiveService.Uses(screen = false, microphone = on))
        if (on && live != null && live?.talks == false) message.text = "The PhoneCam plugin in OBS is older than this app and can't take the microphone."
        refresh()
    }

    /** Mirror in OBS, remembered per camera; applied at once while live. */
    private fun toggleMirror() {
        val id = cameraId ?: return
        val on = !prefs.getBoolean("ctl.$id.mirror", false)
        if (live != null && liveProfile?.cameraId == id) updateControls(controls.copy(mirror = on))
        else prefs.edit().putBoolean("ctl.$id.mirror", on).apply()
        refresh()
    }

    /**
     * Switches on the open connection (about a second). If OBS runs an older plugin it ends the connection;
     * the session then stops while [switching] is set, and the STOPPED handler restarts it the old way.
     */
    private fun restartLive() {
        val session = live ?: return
        val profile = selected() ?: run { message.text = "This quality isn't available on this camera."; return }
        switching = true; message.text = "Switching…"
        if (showPreview) {
            val size = previewSize(profile)
            if (size == null) { refresh(); session.stop("Switching camera or quality"); return }  // the full restart explains it
            preview.holder.setFixedSize(size.width, size.height)
        }
        liveProfile = profile
        val able = Abilities.from(getSystemService(CameraManager::class.java).getCameraCharacteristics(profile.cameraId))
        abilities = able
        controls = loadControls(profile.cameraId); sliding = null
        renderControls(); refresh()
        session.switchTo(profile, highQuality && profile.highQuality, able, controls)
    }

    private fun chooseCustom() {
        val id = cameraId ?: return
        val list = options.filter { it.cameraId == id }.sortedWith(compareBy<Profile> { it.limited }.thenByDescending { it.width }.thenByDescending { it.bitrate })
        if (list.isEmpty()) return
        val sheet = Sheet(this, "Custom quality")
        for (profile in list) {
            fun apply() { customKey = Quality.key(profile); choice = Quality.CUSTOM; refresh() }
            sheet.option(Quality.describe(profile), if (profile.limited) "Limited" else null, selected = customKey == Quality.key(profile)) {
                if (!profile.limited) apply()
                else Sheet(this, "Limited profile", "Lower resolution or a variable frame rate. Use it only if the usual qualities fail on this phone.")
                    .button("Cancel").button("Use it", primary = true) { apply() }.show()
            }
        }
        sheet.button("Cancel").show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { soundOn = true; live?.setPhoneSound(true); refresh() }
            else message.text = "Audio permission wasn't allowed, so the phone's sound stays off. The screen is shared as before."
            return
        }
        if (requestCode == 3) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) setMic(true)
            else message.text = "Microphone access wasn't allowed, so the microphone stays off. Video works as before."
            return
        }
        if (requestCode == 2) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "Notifications are off: live still continues when you leave PhoneCam, but Stop won't show in the status bar.", Toast.LENGTH_LONG).show()
            startOnResume = true; return
        }
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scan()
        else message.text = "Camera access is needed to stream. Allow it in Android Settings → Apps → PhoneCam → Permissions."
        refresh()
    }
    override fun onResume() {
        super.onResume(); foreground = true
        if (openingSettings) { openingSettings = false; updatePip() }
        if (leftLive) leftLive = false
        if (live != null) { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); scheduleDim() }
        if (startOnResume) { startOnResume = false; badge.post { if (foreground) { if (screenMode) askScreen() else startLive() } } }
        runCatching { getSystemService(PowerManager::class.java).addThermalStatusListener(heatListener) }  // also reports the current status
        if (orientation.canDetectOrientation()) orientation.enable()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { if (options.isEmpty()) scan() }
        else message.text = "PhoneCam turns this phone into a camera for OBS. Allow camera access to begin."
        refresh()
    }
    /** Back while live leaves the app like Home, so background live continues. Android 12+ already does this. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (Build.VERSION.SDK_INT < 31 && keyCode == android.view.KeyEvent.KEYCODE_BACK && live != null && backgroundLive) { moveTaskToBack(true); return true }
        return super.onKeyDown(keyCode, event)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Live sessions keep running; the Mac re-orients from the per-frame rotation tag.
        arrange()
        if (screenLive) ScreenSource.displaySize(this).let { (w, h) -> ScreenSource.scaled(w, h, 1920).let { (sw, sh) -> live?.resizeScreen(sw, sh) } }
    }
    override fun onPause() {
        foreground = false
        runCatching { getSystemService(PowerManager::class.java).removeThermalStatusListener(heatListener) }
        if (!isInPictureInPictureMode) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        brighten()
        super.onPause()
    }
    /** Not visible any more (picture-in-picture is still visible): background live keeps going, otherwise live stops. */
    override fun onStop() {
        if (openingSettings) { super.onStop(); return }
        val session = live
        // Screen sharing is for other apps, so it always keeps going (its service is required anyway).
        if (session != null && (backgroundLive || screenLive) && LiveService.running) leftLive = true  // keeps running; rotation still followed
        else {
            orientation.disable()
            session?.stop(if (backgroundLive) "Android didn't allow live in the background, so it stopped when PhoneCam left the screen."
                else "PhoneCam left the screen, so live stopped. To keep live running, turn on Settings → Keep live when you leave the app.")
        }
        super.onStop()
    }
    override fun onDestroy() {
        destroyed = true; orientation.disable()
        if (live != null) leftLive = false  // closed on purpose (swiped away), not by the phone
        live?.stop("PhoneCam was closed, so live stopped."); executor.shutdown(); feedback.release()
        super.onDestroy()
    }
}
