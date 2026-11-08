package dev.phonecam.obs

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
        const val BLUE = 0xFF1565C0.toInt(); const val PANEL = 0xFF161616.toInt(); const val CHIP = 0xCC000000.toInt()
        const val MUTED = 0xFFB0B0B0.toInt()
    }
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var stage: Stage
    private lateinit var preview: SurfaceView
    private lateinit var placeholder: TextView
    private lateinit var badge: TextView
    private lateinit var thermal: TextView
    private lateinit var stats: TextView
    private lateinit var panelScroll: ScrollView
    private lateinit var cameraRow: LinearLayout
    private lateinit var quality: Spinner
    private lateinit var detail: TextView
    private lateinit var startButton: Button
    private lateinit var settingsButton: Button
    private lateinit var message: TextView
    private lateinit var stageControls: LinearLayout
    private lateinit var sliderRow: LinearLayout
    private lateinit var sliderLabel: TextView
    private lateinit var slider: SeekBar
    private lateinit var chipRow: LinearLayout
    private lateinit var focusMark: View
    private var sliding: String? = null
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
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

        stage = Stage(this).apply { setBackgroundColor(Color.BLACK) }
        preview = SurfaceView(this).apply {
            visibility = View.INVISIBLE; contentDescription = "Camera preview"
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { previewReady = true }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) { previewReady = false }
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

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        fun label(text: String) = TextView(this).apply { this.text = text; setTextColor(MUTED); textSize = 13f; setPadding(0, dp(8), 0, dp(4)); panel.addView(this) }
        label("Camera")
        cameraRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(cameraRow)
        label("Quality")
        quality = Spinner(this).apply { contentDescription = "Quality" }
        panel.addView(quality)
        detail = TextView(this).apply { setTextColor(MUTED); textSize = 13f }
        panel.addView(detail)
        startButton = Button(this).apply {
            textSize = 18f; setTextColor(Color.WHITE); isAllCaps = false; typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { onStartPressed() }
        }
        panel.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(16) })
        message = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(0, dp(10), 0, dp(4)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        panel.addView(message)
        settingsButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Settings"; isAllCaps = false; setTextColor(MUTED); setOnClickListener { openSettings() }
        }
        panel.addView(settingsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
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
        quality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                qualityChoices().getOrNull(position)?.let { if (it != choice) { choice = it; refresh() } }
            }
        }
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                val next = Orientation.quantize(degrees, device)
                if (next == device) return
                device = next
                val profile = liveProfile ?: return
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
        cameraRow.removeAllViews()
        for ((id, name) in cameras) {
            val on = id == cameraId
            cameraRow.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = name; isAllCaps = false; setTextColor(if (on) Color.WHITE else MUTED)
                background = rounded(if (on) BLUE else GREY, 8); isEnabled = live == null
                contentDescription = "$name camera${if (on) ", selected" else ""}"
                setOnClickListener { cameraId = id; refresh() }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) })
        }
        val choices = qualityChoices()
        quality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices.map { it.label })
        quality.setSelection(choices.indexOf(choice).coerceAtLeast(0))
        quality.isEnabled = live == null && options.isNotEmpty()
        val profile = selected()
        detail.text = when {
            options.isEmpty() -> ""
            profile == null -> "Not available on this camera. Choose another quality."
            else -> Quality.describe(profile) + if (highQuality && profile.highQuality) " · HQ processing" else ""
        }
        settingsButton.isEnabled = live == null
        when {
            live != null -> { startButton.text = "Stop live"; startButton.background = rounded(RED, 12); startButton.isEnabled = true }
            !permitted -> { startButton.text = "Allow camera access"; startButton.background = rounded(BLUE, 12); startButton.isEnabled = true }
            scanning || options.isEmpty() -> { startButton.text = if (scanning) "Checking camera…" else "Check camera"; startButton.background = rounded(GREY, 12); startButton.isEnabled = !scanning }
            else -> { startButton.text = "Start live to OBS"; startButton.background = rounded(BLUE, 12); startButton.isEnabled = profile != null }
        }
        if (live == null) arrange()
    }

    private fun showState(state: LiveState?) {
        val phase = state?.phase ?: LiveState.Phase.STOPPED
        badge.text = when (phase) { LiveState.Phase.LIVE -> "● LIVE"; LiveState.Phase.WAITING -> "Waiting for OBS"; else -> "Off" }
        badge.background = rounded(when (phase) { LiveState.Phase.LIVE -> RED; LiveState.Phase.WAITING -> AMBER; else -> GREY }, 6)
        val p = liveProfile
        stats.visibility = if (phase == LiveState.Phase.LIVE && p != null) View.VISIBLE else View.GONE
        if (state != null && p != null) stats.text = "${Quality.describe(p).substringBefore(" ·")} · ${"%.0f".format(state.fps)} fps · ${state.drops} drops"
        val heat = runCatching { getSystemService(PowerManager::class.java).currentThermalStatus }.getOrDefault(0)
        thermal.text = when { heat >= PowerManager.THERMAL_STATUS_SEVERE -> "Hot — consider stopping"; heat >= PowerManager.THERMAL_STATUS_MODERATE -> "Warm"; else -> "Cool" }
        thermal.background = rounded(if (heat >= PowerManager.THERMAL_STATUS_SEVERE) RED else if (heat >= PowerManager.THERMAL_STATUS_MODERATE) AMBER else CHIP, 6)
        stageControls.visibility = if (phase == LiveState.Phase.LIVE) View.VISIBLE else View.GONE
        val previewing = phase != LiveState.Phase.STOPPED && showPreview
        placeholder.visibility = if (phase == LiveState.Phase.LIVE && previewing) View.GONE else View.VISIBLE
        placeholder.text = when (phase) {
            LiveState.Phase.LIVE -> "Live. Preview is off."
            LiveState.Phase.WAITING -> "Waiting for OBS…\nOpen OBS with the PhoneCam source visible."
            else -> "Camera off\nNothing is recorded or saved on this phone."
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
                    message.text = if (it.isEmpty()) "No supported camera profile was found on this phone." else "Ready. Open OBS with the PhoneCam source, then tap Start."
                }.onFailure { message.text = "Camera check failed: ${it.message}. Close other camera apps and try again." }
                refresh()
            }
        }
    }

    private fun onStartPressed() {
        when {
            live != null -> live?.stop()
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED -> requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            options.isEmpty() -> scan()
            else -> startLive()
        }
    }

    @Suppress("DEPRECATION")
    private fun displayDegrees() = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
    }

    private fun startLive() {
        if (!foreground || live != null) return
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
        live = LiveStreamer(this, profile, hq, rotation, able, controls, { if (wantPreview && previewReady) preview.holder.surface else null }) { state ->
            if (destroyed) return@LiveStreamer
            showState(state)
            message.text = state.message
            if (state.phase == LiveState.Phase.STOPPED) {
                if (state.frames == 0L && choice == Quality.BEST && state.message != "Stopped by you")
                    message.text = "${state.message}\nIf this keeps happening, choose Quality: High."
                live = null; liveProfile = null; sliding = null; focusMark.visibility = View.GONE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                preview.visibility = View.INVISIBLE
                refresh()
            }
        }.also { it.start() }
        arrange(); refresh()
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
        add(if (controls.mirror) "Mirror in OBS: on" else "Mirror in OBS: off", "Mirror the picture in OBS, ${if (controls.mirror) "on" else "off"}", controls.mirror) {
            updateControls(controls.copy(mirror = !controls.mirror))
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

    private fun openSettings() {
        if (live != null) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val previewBox = CheckBox(this).apply { text = "Show phone preview while live"; isChecked = showPreview }
        val hqBox = CheckBox(this).apply { text = "Extra sharpening and noise reduction (HIGH_QUALITY; may lower frame rate)"; isChecked = highQuality }
        box.addView(previewBox); box.addView(hqBox)
        var dialog: AlertDialog? = null
        fun action(text: String, click: () -> Unit) = box.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { dialog?.dismiss(); click() }
        })
        action("Custom quality…") { chooseCustom() }
        action("Check cameras again") { options = emptyList(); refresh(); scan() }
        action("Research tools (benchmark recordings)") { startActivity(Intent(this, BenchmarkActivity::class.java)) }
        box.addView(TextView(this).apply {
            textSize = 13f; setPadding(0, dp(12), 0, dp(8))
            text = "PhoneCam ${packageManager.getPackageInfo(packageName, 0).versionName}. Live mode sends video to OBS over USB and never saves it on the phone. No microphone or Internet access."
        })
        dialog = AlertDialog.Builder(this).setTitle("Settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done") { _, _ -> showPreview = previewBox.isChecked; highQuality = hqBox.isChecked; refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseCustom() {
        val id = cameraId ?: return
        val list = options.filter { it.cameraId == id }.sortedWith(compareBy<Profile> { it.limited }.thenByDescending { it.width }.thenByDescending { it.bitrate })
        if (list.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Custom quality")
            .setItems(list.map { Quality.describe(it) }.toTypedArray()) { _, index ->
                val profile = list[index]
                fun apply() { customKey = Quality.key(profile); choice = Quality.CUSTOM; refresh() }
                if (!profile.limited) apply()
                else AlertDialog.Builder(this).setTitle("Limited profile")
                    .setMessage("This profile is lower resolution or uses a variable frame rate. Use it only if higher qualities fail on this phone.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Use it") { _, _ -> apply() }.show()
            }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scan()
        else message.text = "Camera access is needed to stream. Allow it in Android Settings → Apps → PhoneCam → Permissions."
        refresh()
    }
    override fun onResume() {
        super.onResume(); foreground = true
        if (orientation.canDetectOrientation()) orientation.enable()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { if (options.isEmpty()) scan() }
        else message.text = "PhoneCam turns this phone into a camera for OBS. Allow camera access to begin."
        refresh()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Live sessions keep running; the Mac re-orients from the per-frame rotation tag.
        arrange()
    }
    override fun onPause() {
        foreground = false; orientation.disable()
        live?.stop("App left the screen, so live stopped. Keep PhoneCam open while streaming.")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onDestroy() {
        destroyed = true; orientation.disable()
        live?.stop("Activity destroyed"); executor.shutdown()
        super.onDestroy()
    }
}
