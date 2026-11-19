package ai.closepaw.qa

import ai.closepaw.BuildConfig
import ai.closepaw.app.MainActivity
import ai.closepaw.platform.virtualdisplay.MediaProjectionCaptureService
import ai.closepaw.platform.virtualdisplay.ProjectionConsentState
import ai.closepaw.platform.virtualdisplay.ProjectionImageTransport
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Non-exported, candidate-enabled DEBUG Activity. No intent-driven run/export.
 * onStop is expected while the user opens a fixture; onDestroy cancels ownership. */
class LocalCaptureQaActivity : ComponentActivity() {
    private lateinit var fixture: Spinner
    private lateinit var scope: Spinner
    private lateinit var save: CheckBox
    private lateinit var status: TextView
    private lateinit var report: TextView
    private lateinit var preview: ImageView
    private val guardedButtons = mutableListOf<Button>()
    private var job: Job? = null
    private var bitmap: Bitmap? = null

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val gate = MediaProjectionCaptureService.consentGate
        val id = UUID.randomUUID().toString()
        val granted = result.resultCode == Activity.RESULT_OK && result.data != null
        when (gate.onDecision(granted, id.takeIf { granted })) {
            is ProjectionConsentState.Granted -> MediaProjectionCaptureService.startFromConsent(this, result.resultCode, result.data!!, id)
            is ProjectionConsentState.Denied -> status.text = "Observation approval denied; no run started."
            else -> gate.onCancelled()
        }
        refreshSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || packageName != CaptureQaAcceptance.CANDIDATE) { finish(); return }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 36, 24, 24) }
        column.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
            insets
        }
        setContentView(ScrollView(this).apply { addView(column) })
        fun text(value: String) = TextView(this).apply { text = value; textSize = 16f; column.addView(this) }
        fun button(label: String, guarded: Boolean = true, action: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { action() }; column.addView(this)
            if (guarded) guardedButtons += this
        }
        text("Local capture QA · ${BuildConfig.GIT_SHA}")
        text("Candidate only. No ADB, LLM key or network is needed. Use only a NEW blank Chrome tab or NEW empty Notes editor with Samsung keyboard. Notifications/other overlays may be visible. No typing, taps or submissions are dispatched to fixtures.")
        status = text("Setup not checked")
        button("Refresh setup") { refreshSetup() }
        button("Main settings / app access / screen control") { startActivity(Intent(this, MainActivity::class.java)) }
        button("Accessibility settings (local approval)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("Overlay settings (local approval)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("Request observation sharing (local Android approval)") { requestObservation() }
        fixture = Spinner(this).apply {
            adapter = ArrayAdapter(this@LocalCaptureQaActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Chrome", "Samsung Notes"))
            column.addView(this)
        }
        scope = Spinner(this).apply {
            adapter = ArrayAdapter(this@LocalCaptureQaActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Screen observation", "Strict keyboard refusal", "Strict display refusal",
                    "After sharing stopped (same-process baseline required)", "Samsung IME geometry · Chrome only · no saved image"))
            column.addView(this)
        }
        save = CheckBox(this).apply {
            text = "Save one private HISTORICAL masked test image (overlays may be visible)"
            isChecked = false; column.addView(this)
        }
        text("Approve setup first. Arm ONE run, then open the selected blank fixture and show Samsung keyboard for at least 10 seconds. Pause/X/end, lock, service loss or projection changes stop the run. No automatic retry. Returning to this page shows the historical result.")
        button("Arm one run · wait up to 10 minutes") { arm() }
        button("Cancel my waiting run", guarded = false) { job?.cancel() }
        button("View historical local result") { showResult("local") }
        button("Compare same-source local / ADB contract") { compare() }
        button("Export selected historical result ZIP (may contain saved pixels)") { export() }
        button("Delete all QA evidence and export grants") { clear() }
        report = text("No result loaded. CAPTURE_PASS is native-contract success; actual pixel position/masking still needs image review. REFUSAL_PASS only confirms its named refusal.")
        preview = ImageView(this).apply {
            adjustViewBounds = true; contentDescription = "Historical private QA image; never a live gesture target"
            column.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        refreshSetup()
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized && job?.isActive != true) refreshSetup() }
    override fun onDestroy() {
        job?.cancel(); previewIfReadyClear(); super.onDestroy()
    }
    private fun refreshSetup() {
        if (!::status.isInitialized) return
        status.text = "Setup: ${CaptureQaAcceptance.setup(this)}; observation=${ProjectionImageTransport.active()?.screenObservationAllowed() == true}; qa_busy=${CaptureQaAcceptance.isRunning()}"
    }
    private fun requestObservation() {
        if (Build.VERSION.SDK_INT < 34) { status.text = "Android 14+ required"; return }
        val gate = MediaProjectionCaptureService.consentGate
        if (gate.state == ProjectionConsentState.Consumed) {
            status.text = "Stop existing sharing using the SYSTEM sharing indicator first; this screen never upgrades a live share."
            return
        }
        if (!gate.onRequestStarted(screenObservation = true)) { status.text = "Existing approval round preserved"; return }
        try {
            consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()))
        } catch (_: Exception) { gate.onCancelled(); status.text = "Android approval request unavailable" }
    }
    private fun selectedFixture() = CaptureQaAcceptance.fixtures[fixture.selectedItemPosition]
    private fun selectedScope() = CaptureQaAcceptance.scopes[scope.selectedItemPosition]
    private fun arm() {
        if (job?.isActive == true) return
        val pkg = selectedFixture(); val mode = selectedScope(); val saveImage = save.isChecked
        if (mode == "ime_geometry" && pkg != "com.android.chrome") {
            status.text = "Select Chrome for Samsung IME geometry."
            return
        }
        previewIfReadyClear(); report.text = "New run requested; previous evidence is not this run."
        guardedButtons.forEach { it.isEnabled = false }; fixture.isEnabled = false; scope.isEnabled = false; save.isEnabled = false
        job = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    CaptureQaAcceptance.run(this@LocalCaptureQaActivity, "local", pkg, mode, 600_000L, saveImage) { state ->
                        runOnUiThread { if (!isDestroyed) status.text = state }
                    }
                }
                report.text = result.toString()
                status.text = "${result.getValue("outcome").jsonPrimitive.content}: ${result.getValue("reason").jsonPrimitive.content}. No automatic repeat."
            } catch (_: CancellationException) {
                if (!isDestroyed) status.text = "Cancelled; only this run's session is cleaned up."
            } catch (_: Exception) {
                if (!isDestroyed) status.text = "QA storage/setup failed; no PASS is claimed."
            } finally {
                if (!isDestroyed) {
                    guardedButtons.forEach { it.isEnabled = true }; fixture.isEnabled = true; scope.isEnabled = true; save.isEnabled = true
                }
            }
        }
    }
    private fun readResult(runner: String): Pair<File, JsonObject>? {
        val dir = CaptureQaAcceptance.directory(this, runner, selectedFixture(), selectedScope())
        val file = File(dir, "result.json")
        if (!file.isFile || file.length() !in 1L..64_000L) return null
        val value = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        if (value["source_commit"]?.jsonPrimitive?.content != BuildConfig.CAPTURE_QA_SOURCE_SHA ||
            value["fixture_package"]?.jsonPrimitive?.content != selectedFixture() ||
            value["requested_scope"]?.jsonPrimitive?.content != selectedScope() ||
            value["runner"]?.jsonPrimitive?.content != runner) return null
        return dir to value
    }
    private fun image(result: Pair<File, JsonObject>): ByteArray? {
        val file = File(result.first, "preview.jpg")
        if (!file.isFile || file.length() !in 1L..1_500_000L) return null
        val bytes = file.readBytes()
        return bytes.takeIf { CaptureQaAcceptance.sha256(it) == result.second["image_sha256"]?.jsonPrimitive?.content }
    }
    private fun showResult(runner: String) {
        previewIfReadyClear()
        val result = readResult(runner) ?: run { report.text = "No valid same-source selected result"; return }
        report.text = "HISTORICAL TEST EVIDENCE, not live control. Pixel inspection is separate.\n${result.second}"
        val bytes = image(result) ?: return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..960 || bounds.outHeight !in 1..960) { status.text = "Evidence geometry invalid"; return }
        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        preview.setImageBitmap(bitmap)
    }
    private fun previewIfReadyClear() {
        if (::preview.isInitialized) preview.setImageDrawable(null)
        bitmap?.recycle(); bitmap = null
    }
    private fun compare() {
        val local = readResult("local")?.second
        val adb = readResult("adb")?.second
        report.text = if (local == null || adb == null) "Same-source local AND ADB reports required; no comparison PASS"
            else CaptureQaComparison.compare(local, adb)
    }
    private fun revokeAndDelete(file: File) {
        runCatching { revokeUriPermission(FileProvider.getUriForFile(this, "$packageName.captureqa", file), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        file.delete()
    }
    private fun export() {
        try {
            if (CaptureQaAcceptance.withIdleEvidence { exportIdle(); true } == null)
                status.text = "QA engine busy; export refused without changing evidence."
        } catch (_: Exception) { status.text = "QA export unavailable; no export success claimed."
        }
    }
    private fun exportIdle() {
        val result = readResult("local") ?: run { report.text = "No valid selected historical result"; return }
        val exports = File(cacheDir, "capture-qa/exports").apply { mkdirs() }
        exports.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(1).forEach(::revokeAndDelete)
        val zip = File(exports, "qa-${UUID.randomUUID()}.zip")
        ZipOutputStream(zip.outputStream()).use { stream ->
            stream.putNextEntry(ZipEntry("result.json")); stream.write(result.second.toString().toByteArray()); stream.closeEntry()
            image(result)?.let { bytes ->
                stream.putNextEntry(ZipEntry("historical-preview.jpg")); stream.write(bytes); stream.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.captureqa", zip)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Historical private QA evidence", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share selected historical QA evidence"))
    }
    private fun clear() {
        try {
            when (CaptureQaAcceptance.deleteEvidence(this, ::revokeAndDelete)) {
                null -> status.text = "QA engine busy; delete refused. Stop that run first."
                false -> status.text = "Evidence deletion incomplete; no cleanup success claimed."
                true -> { previewIfReadyClear(); report.text = "QA evidence deleted while the shared engine was idle." }
            }
        } catch (_: Exception) { status.text = "Evidence deletion failed; no cleanup success claimed." }
    }
}
