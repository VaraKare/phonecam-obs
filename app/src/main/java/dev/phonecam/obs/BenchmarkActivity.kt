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
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

/**
 * Research tools: timed benchmark recordings for quality comparisons. This is the only part of
 * the app that saves video, and only after the explicit Record tap. Opened from Settings.
 */
class BenchmarkActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var profiles: Spinner
    private lateinit var processing: CheckBox
    private lateinit var limited: CheckBox
    private lateinit var scene: Spinner
    private lateinit var duration: Spinner
    private lateinit var start: Button
    private lateinit var scan: Button
    private lateinit var stop: Button
    private lateinit var delete: Button
    private var options = emptyList<Profile>()
    private var recorder: BenchmarkRecorder? = null
    private var foreground = false
    private var destroyed = false
    private val executor = Executors.newSingleThreadExecutor()
    private val directory get() = File(filesDir, "benchmarks").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val margin = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(margin, margin, margin, margin) }
        val scroll = ScrollView(this).apply { addView(root); fitsSystemWindows = true }
        setContentView(scroll)
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
                adapter = ArrayAdapter(this@BenchmarkActivity, android.R.layout.simple_spinner_dropdown_item, values)
                root.addView(this)
            }
        }
        text("Research tools", 26f)
        text("Benchmark clips are saved on this phone for quality comparisons, only when you tap Record. Live mode never saves video. Benchmark capture has no preview and needs landscape; frame the shot in the stock camera on a tripod first.")
        status = text("Scan capabilities, choose a profile, then Record.")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        scan = button("Scan and save capability report") { scanCapabilities() }
        profiles = spinner("Declared camera and encoding profile", listOf("Scan capabilities first"))
        processing = CheckBox(this).apply { text = "HIGH_QUALITY sharpening and noise reduction (may lower FPS)"; root.addView(this) }
        limited = CheckBox(this).apply { text = "I accept limited profiles (720p or variable frame-rate range)"; root.addView(this) }
        scene = spinner("Benchmark scene", listOf("well_lit_detail", "indoor_face", "low_light_motion", "backlit_subject"))
        duration = spinner("Recording duration", listOf("15 seconds", "30 seconds", "60 seconds"))
        start = button("Record benchmark clip") { record() }.apply { isEnabled = false }
        stop = button("Stop recording") { recorder?.stop() }.apply { isEnabled = false }
        delete = button("Delete benchmark clips") {
            if (recorder != null) return@button
            AlertDialog.Builder(this).setTitle("Delete benchmark clips?")
                .setMessage("Delete all benchmark reports and clips in ${directory.absolutePath}? Copies already collected on the Mac stay there.")
                .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                    val entries = directory.listFiles().orEmpty()
                    val removed = entries.count { it.isFile && it.delete() }
                    status.text = "Deleted $removed of ${entries.size} files. Remaining: ${directory.listFiles()?.size ?: 0}."
                }.show()
        }
        profiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                processing.isChecked = false
                processing.isEnabled = options.getOrNull(position)?.highQuality == true && recorder == null
            }
        }
    }

    private fun scanCapabilities() {
        if (recorder != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1); return
        }
        scan.isEnabled = false; start.isEnabled = false
        status.text = "Scanning advertised camera and hardware encoder capabilities…"
        executor.execute {
            val result = runCatching { Capabilities.scan(this).also { writeJson(File(directory, "capabilities.json"), it.report) } }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                scan.isEnabled = true
                result.onSuccess {
                    options = it.profiles
                    profiles.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                        options.map(Profile::toString).ifEmpty { listOf("No supported declared profile") })
                    start.isEnabled = options.isNotEmpty()
                    status.text = "${options.size} declared profiles. None verified.\nCapabilities saved to ${File(directory, "capabilities.json").absolutePath}.\n${it.report.optJSONArray("errors")}"
                }.onFailure { status.text = "Scan failed: ${it.message}. Check camera permissions or close other camera apps." }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun record() {
        if (!foreground || recorder != null) return
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
            status.text = "Rotate the phone to landscape before recording. Enable auto-rotate if rotation lock is active."; return
        }
        val rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
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

    private fun setRecording(recording: Boolean) {
        start.isEnabled = !recording && options.isNotEmpty(); stop.isEnabled = recording
        scan.isEnabled = !recording; delete.isEnabled = !recording
        profiles.isEnabled = !recording; scene.isEnabled = !recording; duration.isEnabled = !recording
        limited.isEnabled = !recording
        processing.isEnabled = !recording && options.getOrNull(profiles.selectedItemPosition)?.highQuality == true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanCapabilities()
        else status.text = "Camera permission denied. Grant it in Android Settings → Apps → PhoneCam → Permissions."
    }
    override fun onResume() { super.onResume(); foreground = true }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); recorder?.stop("orientation_changed") }
    override fun onPause() {
        foreground = false; recorder?.stop("app_backgrounded")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onDestroy() { destroyed = true; recorder?.stop("activity_destroyed"); executor.shutdown(); super.onDestroy() }
}
