package dev.phonecam.obs

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
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
    private companion object {
        val SCENES = listOf("well_lit_detail", "indoor_face", "low_light_motion", "backlit_subject")
        val SCENE_NAMES = listOf("Well-lit detail", "Indoor face", "Low light, motion", "Backlit subject")
        val DURATIONS = listOf(15, 30, 60)
    }
    private val ui by lazy { Ui(this) }
    private lateinit var status: TextView
    private lateinit var scanValue: TextView
    private lateinit var profileValue: TextView
    private lateinit var sceneValue: TextView
    private lateinit var durationValue: TextView
    private lateinit var processing: Switch
    private lateinit var limited: Switch
    private lateinit var recordButton: Button
    private lateinit var clipsRow: View
    private var options = emptyList<Profile>()
    private var profileIndex = 0
    private var sceneIndex = 0
    private var durationIndex = 0
    private var recorder: BenchmarkRecorder? = null
    private var foreground = false
    private var destroyed = false
    private val executor = Executors.newSingleThreadExecutor()
    private val directory get() = File(filesDir, "benchmarks").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.page()
        ui.title("Research tools", "Test clips for quality comparisons. They're saved on this phone only when you tap Record; live mode never saves video.")
        ui.section("Camera check")
        scanValue = ui.row("Scan cameras", "Saves a capability report") { scanCapabilities() }.second
        ui.section("Clip")
        profileValue = ui.row("Profile", value = "Scan first") { choose("Profile", options.map(Profile::toString), profileIndex) { profileIndex = it; refresh() } }.second
        sceneValue = ui.row("Scene") { choose("Scene", SCENE_NAMES, sceneIndex) { sceneIndex = it; refresh() } }.second
        durationValue = ui.row("Length") { choose("Length", DURATIONS.map { "$it seconds" }, durationIndex) { durationIndex = it; refresh() } }.second
        processing = ui.toggle("Extra sharpening", "HIGH_QUALITY processing; may lower the frame rate", false) { on ->
            if (on && options.getOrNull(profileIndex)?.highQuality != true) { processing.isChecked = false; status.text = "This camera doesn't advertise HIGH_QUALITY processing for this profile." }
        }.second
        limited = ui.toggle("Allow limited profiles", "720p or a variable frame rate", false) {}.second
        recordButton = ui.button("Record") { if (recorder != null) recorder?.stop() else record() }
        status = ui.note("Scan the cameras, pick a profile, then hold the phone in landscape on a tripod and tap Record. There's no preview: frame the shot in the stock camera first.")
        ui.section("Clips on this phone")
        clipsRow = ui.row("Delete clips", "Copies already on the Mac stay there") { deleteClips() }.first
        refresh()
    }

    private fun choose(title: String, items: List<String>, selected: Int, picked: (Int) -> Unit) {
        if (recorder != null || items.isEmpty()) { if (items.isEmpty()) status.text = "Scan the cameras first."; return }
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), selected) { dialog, index -> picked(index); dialog.dismiss() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun refresh() {
        val recording = recorder != null
        val profile = options.getOrNull(profileIndex)
        profileValue.text = (profile?.let { "${it.width}×${it.height} · ${if (it.mime == "video/avc") "H.264" else "H.265"} · ${it.bitrate / 1_000_000} Mb/s" } ?: "Scan first") + "  ›"
        sceneValue.text = SCENE_NAMES[sceneIndex] + "  ›"
        durationValue.text = "${DURATIONS[durationIndex]} s  ›"
        if (profile?.highQuality != true) processing.isChecked = false
        val clips = directory.listFiles()?.count { it.isFile } ?: 0
        scanValue.text = (if (options.isEmpty()) "" else "${options.size} profiles") + "  ›"
        clipsRow.alpha = if (recording) 0.4f else 1f
        when {
            recording -> ui.style(recordButton, "Stop recording", Ui.RED, Color.WHITE)
            profile == null -> ui.style(recordButton, "Record", Ui.GREY, Ui.MUTED, enabled = false)
            else -> ui.style(recordButton, "Record", Color.WHITE, Color.BLACK)
        }
        recordButton.contentDescription = "${recordButton.text}. $clips files saved."
    }

    private fun deleteClips() {
        if (recorder != null) return
        val count = directory.listFiles()?.count { it.isFile } ?: 0
        AlertDialog.Builder(this).setTitle("Delete $count files?")
            .setMessage("Deletes the benchmark clips and reports saved on this phone. Copies already collected on the Mac stay there.")
            .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                val entries = directory.listFiles().orEmpty()
                val removed = entries.count { it.isFile && it.delete() }
                status.text = "Deleted $removed of ${entries.size} files."
                refresh()
            }.show()
    }

    private fun scanCapabilities() {
        if (recorder != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1); return
        }
        status.text = "Checking what the cameras and hardware encoders advertise…"
        executor.execute {
            val result = runCatching { Capabilities.scan(this).also { writeJson(File(directory, "capabilities.json"), it.report) } }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result.onSuccess {
                    options = it.profiles.filter { p -> !p.highSpeed }  // benchmark recorder uses normal 30 fps sessions
                    profileIndex = 0
                    val errors = it.report.optJSONArray("errors")?.takeIf { e -> e.length() > 0 }?.let { e -> "\nProblems: $e" }.orEmpty()
                    status.text = "${options.size} advertised profiles (none verified until a clip records). Report saved on this phone.$errors"
                }.onFailure { status.text = "Couldn't scan: ${it.message}. Allow camera access or close other camera apps." }
                refresh()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun record() {
        if (!foreground || recorder != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Camera permission was removed. Scan and grant permission again."; return
        }
        val profile = options.getOrNull(profileIndex) ?: return
        if (profile.limited && !limited.isChecked) {
            status.text = "This profile is limited. Turn on Allow limited profiles, or choose a fixed 1080p or 4K profile."; return
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
        recorder = BenchmarkRecorder(this, directory, profile, processing.isChecked,
            DURATIONS[durationIndex], SCENES[sceneIndex], rotation) { text, done ->
            if (!destroyed) status.text = text
            if (done) {
                recorder = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!destroyed) refresh()
            }
        }.also { it.start() }
        refresh()
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
