package dev.phonecam.obs

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var banner: TextView
    private lateinit var status: TextView
    private lateinit var preview: SurfaceView
    private lateinit var showPreview: CheckBox
    private lateinit var profiles: Spinner
    private lateinit var processing: CheckBox
    private lateinit var limited: CheckBox
    private lateinit var scene: Spinner
    private lateinit var duration: Spinner
    private lateinit var start: Button
    private lateinit var liveStart: Button
    private lateinit var scan: Button
    private lateinit var stop: Button
    private lateinit var delete: Button
    private lateinit var scroll: ScrollView
    private var options = emptyList<Profile>()
    private var recorder: BenchmarkRecorder? = null
    private var live: LiveStreamer? = null
    private var liveProfile: Profile? = null
    private val busy get() = recorder != null || live != null
    private var foreground = false
    private var destroyed = false
    @Volatile private var previewReady = false
    private var device = Orientation.UNKNOWN
    private lateinit var orientation: OrientationEventListener
    private val executor = Executors.newSingleThreadExecutor()
    private val directory get() = File(filesDir, "benchmarks").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val margin = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin, margin, margin)
        }
        scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        fun text(value: String, size: Float = 16f): TextView = TextView(this).apply {
            text = value; textSize = size; setPadding(0, 12, 0, 12); root.addView(this)
        }
        fun button(value: String, click: () -> Unit): Button = Button(this).apply {
            text = value; setOnClickListener { click(); scroll.smoothScrollTo(0, 0) }; root.addView(this)
        }
        fun spinner(label: String, values: List<String>): Spinner {
            val heading = text(label)
            return Spinner(this).apply {
                id = View.generateViewId(); heading.labelFor = id; contentDescription = label
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
                root.addView(this)
            }
        }
        text("PhoneCam", 26f)
        banner = text("", 20f).apply {
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(16, 20, 16, 20)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        status = text("Scan the camera, choose a profile, then Start live. Any orientation works. Setup: docs/USB_LIVE.md")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        preview = SurfaceView(this).apply {
            visibility = View.GONE; contentDescription = "Camera preview"
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { previewReady = true }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) { previewReady = false }
            })
        }
        root.addView(FrameLayout(this).apply { addView(preview, FrameLayout.LayoutParams(0, 0, Gravity.CENTER_HORIZONTAL)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        liveStart = button("Start live to OBS") { if (live != null) live?.stop() else startLive() }.apply { isEnabled = false }
        showPreview = CheckBox(this).apply { text = "Show phone preview"; isChecked = true; root.addView(this) }
        scan = button("Scan camera capabilities") { scanCapabilities() }
        profiles = spinner("Camera and quality (none verified yet)", listOf("Scan capabilities first"))
        processing = CheckBox(this).apply { text = "HIGH_QUALITY sharpening and noise reduction (may lower FPS)"; root.addView(this) }
        limited = CheckBox(this).apply { text = "I accept limited profiles (720p or variable frame-rate range)"; root.addView(this) }
        text("Research tools", 20f).setPadding(0, 48, 0, 12)
        text("Benchmark recordings save test clips on this phone for quality comparisons. Live mode never saves anything. Benchmark capture has no preview and needs landscape.")
        scene = spinner("Benchmark scene", listOf("well_lit_detail", "indoor_face", "low_light_motion", "backlit_subject"))
        duration = spinner("Recording duration", listOf("15 seconds", "30 seconds", "60 seconds"))
        start = button("Record benchmark clip") { record() }.apply { isEnabled = false }
        stop = button("Stop benchmark recording") { recorder?.stop() }.apply { isEnabled = false }
        delete = button("Delete benchmark clips") {
            if (busy) return@button
            AlertDialog.Builder(this).setTitle("Delete benchmark clips?")
                .setMessage("Delete all benchmark reports and clips in ${directory.absolutePath}? Copies already collected on the Mac stay there. Live sessions never create files.")
                .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                    val entries = directory.listFiles().orEmpty()
                    val removed = entries.count { it.isFile && it.delete() }
                    status.text = "Deleted $removed of ${entries.size} files. Remaining: ${directory.listFiles()?.size ?: 0}."
                    options = emptyList(); start.isEnabled = false; liveStart.isEnabled = false
                }.show()
        }
        profiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                processing.isChecked = false
                processing.isEnabled = options.getOrNull(position)?.highQuality == true && !busy
            }
        }
        LiveTransport.token(filesDir)
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                val next = Orientation.quantize(degrees, device)
                if (next == device) return
                device = next
                val profile = liveProfile ?: return
                live?.setRotation(Orientation.encodedRotation(profile.sensorOrientation, profile.facing, device))
            }
        }
        showBanner(false)
    }

    private fun showBanner(on: Boolean) {
        banner.text = if (on) "● LIVE — camera on, sending to OBS" else "Camera off"
        banner.setBackgroundColor(if (on) Color.rgb(196, 30, 30) else Color.rgb(90, 90, 90))
        banner.setTextColor(Color.WHITE)
        liveStart.text = if (on) "Stop live" else "Start live to OBS"
    }

    private fun sizePreview(profile: Profile?) {
        val p = profile ?: return
        val metrics = resources.displayMetrics
        val available = metrics.widthPixels - scroll.paddingLeft - scroll.paddingRight - (40 * metrics.density).toInt()
        val maxHeight = (metrics.heightPixels * 0.55).toInt()
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val (aspectW, aspectH) = if (portrait) p.height to p.width else p.width to p.height
        var w = available; var h = w * aspectH / aspectW
        if (h > maxHeight) { h = maxHeight; w = h * aspectW / aspectH }
        preview.layoutParams = (preview.layoutParams as FrameLayout.LayoutParams).apply { width = w; height = h }
    }

    /** Largest SurfaceHolder size with the profile's aspect ratio, at most 1080p (guaranteed PRIV preview + record). */
    private fun previewSize(profile: Profile): Size? {
        val map = getSystemService(CameraManager::class.java).getCameraCharacteristics(profile.cameraId)[
            android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: return null
        return map.getOutputSizes(SurfaceHolder::class.java).orEmpty()
            .filter { it.width * profile.height == it.height * profile.width && it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width * it.height }
    }

    private fun scanCapabilities() {
        if (busy) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1); return
        }
        scan.isEnabled = false; delete.isEnabled = false; start.isEnabled = false; liveStart.isEnabled = false
        status.text = "Scanning advertised camera and hardware encoder capabilities…"
        executor.execute {
            val result = runCatching { Capabilities.scan(this).also { writeJson(File(directory, "capabilities.json"), it.report) } }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                scan.isEnabled = true; delete.isEnabled = true
                result.onSuccess {
                    options = it.profiles
                    profiles.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                        options.map(Profile::toString).ifEmpty { listOf("No supported declared profile") })
                    start.isEnabled = options.isNotEmpty(); liveStart.isEnabled = options.isNotEmpty()
                    status.text = "${options.size} declared profiles. None verified.\nCapabilities saved to ${File(directory, "capabilities.json").absolutePath}.\n${it.report.optJSONArray("errors")}"
                }.onFailure { status.text = "Scan failed: ${it.message}. Check camera permissions or close other camera apps." }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun displayDegrees() = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
    }

    private fun record() {
        if (!foreground || busy) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Camera permission was removed. Scan and grant permission again."; return
        }
        val profile = options.getOrNull(profiles.selectedItemPosition) ?: return
        if (profile.limited && !limited.isChecked) {
            status.text = "This profile is limited. Select the explicit limited-mode checkbox or choose a fixed 1080p/4K profile."; return
        }
        if (processing.isChecked && !profile.highQuality) {
            status.text = "Both high-quality modes are not advertised for this camera."; return
        }
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            status.text = "Rotate the phone to landscape before recording a benchmark. Enable auto-rotate if rotation lock is active."; return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setRecording(true)
        stop.isEnabled = true
        recorder = BenchmarkRecorder(this, directory, profile, processing.isChecked,
            listOf(15, 30, 60)[duration.selectedItemPosition], scene.selectedItem.toString(), displayDegrees()) { text, done ->
            if (!destroyed) status.text = text
            if (done) {
                recorder = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!destroyed) setRecording(false)
            }
        }.also { it.start() }
    }

    private fun startLive() {
        if (!foreground || busy) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Grant camera permission and scan again."; return
        }
        val profile = options.getOrNull(profiles.selectedItemPosition) ?: return
        if (profile.limited && !limited.isChecked) { status.text = "Accept limited mode first, or choose a fixed 1080p profile."; return }
        if (device == Orientation.UNKNOWN) device = Orientation.fromDisplay(displayDegrees())
        val rotation = Orientation.encodedRotation(profile.sensorOrientation, profile.facing, device)
        val wantPreview = showPreview.isChecked
        if (wantPreview) {
            val size = previewSize(profile)
            if (size == null) { status.text = "No preview size matches this profile. Turn off Show phone preview to stream without it."; return }
            preview.holder.setFixedSize(size.width, size.height)
            sizePreview(profile); preview.visibility = View.VISIBLE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        liveProfile = profile
        setRecording(true)
        showBanner(true)
        banner.text = "Waiting for OBS…"
        banner.setBackgroundColor(Color.rgb(200, 120, 0))
        live = LiveStreamer(this, profile, processing.isChecked, rotation,
            { if (wantPreview && previewReady) preview.holder.surface else null }) { text, done ->
            if (destroyed) return@LiveStreamer
            status.text = text
            if (text.startsWith("●")) showBanner(true)
            if (done) {
                live = null; liveProfile = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                preview.visibility = View.GONE
                showBanner(false); setRecording(false)
            }
        }.also { it.start() }
        liveStart.isEnabled = true  // the same button now stops the session
    }

    private fun setRecording(recording: Boolean) {
        start.isEnabled = !recording && options.isNotEmpty()
        liveStart.isEnabled = live != null || (!recording && options.isNotEmpty())
        stop.isEnabled = recorder != null
        scan.isEnabled = !recording; delete.isEnabled = !recording
        profiles.isEnabled = !recording; scene.isEnabled = !recording; duration.isEnabled = !recording
        limited.isEnabled = !recording; showPreview.isEnabled = !recording
        processing.isEnabled = !recording && options.getOrNull(profiles.selectedItemPosition)?.highQuality == true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanCapabilities()
        else status.text = "Camera permission denied. Grant it in Android Settings → Apps → PhoneCam Probe → Permissions, then scan again."
    }
    override fun onResume() {
        super.onResume(); foreground = true
        if (orientation.canDetectOrientation()) orientation.enable()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recorder?.stop("orientation_changed")
        // Live sessions keep running; the Mac re-orients from the per-frame rotation tag.
        if (live != null) sizePreview(liveProfile)
    }
    override fun onPause() {
        foreground = false; orientation.disable()
        recorder?.stop("app_backgrounded"); live?.stop("App left foreground")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onDestroy() {
        destroyed = true; orientation.disable()
        recorder?.stop("activity_destroyed"); live?.stop("Activity destroyed"); executor.shutdown()
        super.onDestroy()
    }
}
