package dev.phonecam.obs

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
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
    private var options = emptyList<Profile>()
    private var recorder: BenchmarkRecorder? = null
    private var live: LiveStreamer? = null
    private val busy get() = recorder != null || live != null
    private var foreground = false
    private var destroyed = false
    private val executor = Executors.newSingleThreadExecutor()
    private val directory get() = File(filesDir, "benchmarks").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margin = (20 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin, margin, margin)
        }
        val scroll = ScrollView(this).apply { addView(root) }
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
        text("PhoneCam Probe", 26f)
        text("Research only · USB live + native OBS experiment. All profiles are unverified. Tap Record to save a benchmark, or Start USB live to stream without saving. No audio or Internet permission.")
        text("Encoder-only capture has no preview. Use the stock camera to frame a tripod-mounted baseline, then keep the phone in position. Keep this app visible while recording. FAST processing uses OFF when FAST is unavailable; reports show the actual request.")
        LiveTransport.token(filesDir)
        status = text("Scan capabilities, choose a profile, then Record or Start USB live. Keep this app visible. USB setup: docs/USB_LIVE.md")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        stop = button("Stop capture") { recorder?.stop(); live?.stop() }.apply { isEnabled = false }
        scan = button("Scan camera capabilities") { scanCapabilities() }
        profiles = spinner("Declared camera and encoding profile", listOf("Scan capabilities first"))
        processing = CheckBox(this).apply { text = "Test HIGH_QUALITY sharpening and noise reduction (may lower FPS)"; root.addView(this) }
        limited = CheckBox(this).apply { text = "I accept limited profiles (720p or variable frame-rate range)"; root.addView(this) }
        scene = spinner("Benchmark scene", listOf("well_lit_detail", "indoor_face", "low_light_motion", "backlit_subject"))
        duration = spinner("Recording duration", listOf("15 seconds", "30 seconds", "60 seconds"))
        liveStart = button("Start USB live (no recording)") { startLive() }.apply { isEnabled = false }
        start = button("Record benchmark") { record() }.apply { isEnabled = false }
        delete = button("Delete benchmark files") {
            if (busy) return@button
            AlertDialog.Builder(this).setTitle("Delete benchmark evidence?")
                .setMessage("Delete all reports and clips in ${directory.absolutePath}? Copies already collected on the Mac stay there.")
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
        val rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            status.text = "Rotate the phone to landscape before recording. Enable auto-rotate if rotation lock is active."; return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setRecording(true)
        recorder = BenchmarkRecorder(this, directory, profile, processing.isChecked,
            listOf(15, 30, 60)[duration.selectedItemPosition], scene.selectedItem.toString(), rotation) { text, done ->
            if (!destroyed) status.text = text
            if (done) {
                recorder = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!destroyed) setRecording(false)
            }
        }.also { it.start() }
    }

    @Suppress("DEPRECATION")
    private fun startLive() {
        if (!foreground || busy) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Grant camera permission and scan again."; return
        }
        val profile = options.getOrNull(profiles.selectedItemPosition) ?: return
        if (profile.limited && !limited.isChecked) { status.text = "Accept limited mode first, or choose a fixed 1080p profile."; return }
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            status.text = "Rotate to landscape, with auto-rotate enabled, before Start USB live."; return
        }
        val degrees = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        val rotation = if (profile.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT)
            (profile.sensorOrientation + degrees) % 360 else (profile.sensorOrientation - degrees + 360) % 360
        if (rotation != 0) {
            status.text = "For this initial live build, turn the phone to the opposite landscape orientation (sensor upright). Then tap Start again."; return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setRecording(true)
        live = LiveStreamer(this, profile, processing.isChecked, rotation) { text, done ->
            if (!destroyed) status.text = text
            if (done) {
                live = null; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!destroyed) setRecording(false)
            }
        }.also { it.start() }
    }

    private fun setRecording(recording: Boolean) {
        start.isEnabled = !recording && options.isNotEmpty(); liveStart.isEnabled = !recording && options.isNotEmpty(); stop.isEnabled = recording
        scan.isEnabled = !recording; delete.isEnabled = !recording
        profiles.isEnabled = !recording; scene.isEnabled = !recording; duration.isEnabled = !recording
        limited.isEnabled = !recording
        processing.isEnabled = !recording && options.getOrNull(profiles.selectedItemPosition)?.highQuality == true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanCapabilities()
        else status.text = "Camera permission denied. Grant it in Android Settings → Apps → PhoneCam Probe → Permissions, then scan again."
    }
    override fun onResume() { super.onResume(); foreground = true }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recorder?.stop("orientation_changed"); live?.stop("Orientation changed; restart live in landscape")
    }
    override fun onPause() {
        foreground = false; recorder?.stop("app_backgrounded"); live?.stop("App left foreground")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onDestroy() {
        destroyed = true; recorder?.stop("activity_destroyed"); live?.stop("Activity destroyed"); executor.shutdown()
        super.onDestroy()
    }
}
