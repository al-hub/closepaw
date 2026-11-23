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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
    // Only an explicit local 'Arm guarded six-key' action may set this latch.
    // After OS consent, a separate foreground service owns the approved run.
    private var armedAfterConsent = false
    private var pendingDiagnosticOnly = false
    private var bitmap: Bitmap? = null

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val gate = MediaProjectionCaptureService.consentGate
        val id = UUID.randomUUID().toString()
        val granted = result.resultCode == Activity.RESULT_OK && result.data != null
        when (gate.onDecision(granted, id.takeIf { granted })) {
            is ProjectionConsentState.Granted -> {
                MediaProjectionCaptureService.startFromConsent(this, result.resultCode, result.data!!, id)
                if (armedAfterConsent) {
                    armedAfterConsent = false
                    val diagnosticOnly = pendingDiagnosticOnly
                    pendingDiagnosticOnly = false
                    runGuardedSixKeyNow(diagnosticOnly)
                }
            }
            is ProjectionConsentState.Denied -> {
                armedAfterConsent = false
                pendingDiagnosticOnly = false
                status.text = "Observation approval denied; no run started."
            }
            else -> {
                armedAfterConsent = false
                pendingDiagnosticOnly = false
                gate.onCancelled()
            }
        }
        refreshSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ai.closepaw.chatgpt.control.ControlDiagnostics.record("debug_qa_activity", "created")
        if (!BuildConfig.DEBUG || packageName != CaptureQaAcceptance.CANDIDATE) { finish(); return }
        // Preserve the user's *pending* local arm across an OS consent Activity
        // recreation; never invent an approval or automatically re-arm after death.
        armedAfterConsent = savedInstanceState?.getBoolean("qa_pending_user_arm", false) == true
        pendingDiagnosticOnly = savedInstanceState?.getBoolean("qa_pending_diagnostic_only", false) == true &&
            armedAfterConsent
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
        button("Main settings / app access / screen control") {
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra("closepaw_debug_qa_show_main", true))
        }
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
                listOf("Screen observation", "Strict keyboard refusal", "Strict display refusal", "After sharing stopped (same-process baseline required)"))
            column.addView(this)
        }
        save = CheckBox(this).apply {
            text = "Save one private HISTORICAL masked test image (overlays may be visible)"
            isChecked = false; column.addView(this)
        }
        text("Approve setup first. Read-only capture remains one run. The separate guarded INPUT QA uses its own foreground runner. Stop/pause, lock, service loss or projection revocation stop it; no unverified key is replayed.")
        button("Arm one run · wait up to 10 minutes") { arm() }
        text("DEBUG QA: first choose one no-key structural diagnosis to capture Samsung key counts and image-source state. It may safely open and focus a NEW blank Chrome tab, but never types. Review stored evidence before separately choosing six-key input. Both jobs survive voice/activity exit; consent and native guards remain required.")
        button("Arm ONE no-key structural diagnosis · auto-open Chrome") { armOneStop(diagnosticOnly = true) }
        button("Arm guarded six-key first-pass · auto-open Chrome") { armOneStop(diagnosticOnly = false) }
        button("Revoke Samsung keyboard interaction · Debug only", guarded = false) {
            if (DebugImeInteractConsent.updateFromLocalConsent(this, grant = false)) {
                refreshSetup()
                status.text = "Debug-only Samsung IME interaction revoked. Native permission checks remain enforced."
            } else {
                status.text = "No matching Debug-only Samsung IME grant to revoke."
            }
        }
        button("View saved Debug QA evidence", guarded = false) { showOneStopResult() }
        button("Share redacted Debug QA JSON · no screenshots", guarded = false) { shareOneStopJson() }
        button("Cancel my waiting run", guarded = false) {
            armedAfterConsent = false; pendingDiagnosticOnly = false
            job?.cancel(); DebugQaRunnerService.cancel(this)
        }
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
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("qa_pending_user_arm", armedAfterConsent)
        outState.putBoolean("qa_pending_diagnostic_only", pendingDiagnosticOnly)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        ai.closepaw.chatgpt.control.ControlDiagnostics.record("debug_qa_activity", "resumed")
        if (::status.isInitialized && job?.isActive != true) refreshSetup()
        if (::report.isInitialized && !DebugQaRunnerService.isRunning()) {
            DebugOneStopQa.lastBatchResult(this)?.let {
                val state = it["state"]?.jsonPrimitive?.content.orEmpty()
                report.text = "Saved QA batch: " +
                    (if (state == "IN_PROGRESS") "INTERRUPTED (not auto-resumed)" else state) +
                    "\n" + it.toString()
            }
        }
    }
    override fun onStop() {
        // Do not stop the independent OS-approved projection when Chrome opens.
        ai.closepaw.chatgpt.control.ControlDiagnostics.record("debug_qa_activity", "stopped")
        super.onStop()
    }
    override fun onDestroy() {
        ai.closepaw.chatgpt.control.ControlDiagnostics.record(
            "debug_qa_activity", if (job?.isActive == true) "destroyed_with_run" else "destroyed_idle")
        armedAfterConsent = false
        // The read-only capture remains Activity-owned. Do not cancel the
        // independently approved foreground INPUT QA service here.
        job?.cancel(); previewIfReadyClear(); super.onDestroy()
    }
    private fun refreshSetup() {
        if (!::status.isInitialized) return
        status.text = "Setup: ${CaptureQaAcceptance.setup(this)}; observation=${ProjectionImageTransport.active()?.screenObservationAllowed() == true}; qa_busy=${CaptureQaAcceptance.isRunning()}; independent_input_qa=${DebugQaRunnerService.isRunning()}; samsung_ime_selected=${DebugImeInteractConsent.selectedSamsungIme(this)}; ime_interact_explicit=${DebugImeInteractConsent.explicitlyGranted(this)}"
    }
    private fun requestObservation(): Boolean {
        if (Build.VERSION.SDK_INT < 34) { status.text = "Android 14+ required"; return false }
        val gate = MediaProjectionCaptureService.consentGate
        if (gate.state == ProjectionConsentState.Consumed) {
            status.text = "Stop existing sharing using the SYSTEM sharing indicator first; this screen never upgrades a live share."
            return false
        }
        if (!gate.onRequestStarted(screenObservation = true)) {
            status.text = "Existing approval round preserved"; return false
        }
        return try {
            consent.launch(getSystemService(MediaProjectionManager::class.java)
                .createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()))
            true
        } catch (_: Exception) {
            gate.onCancelled(); status.text = "Android approval request unavailable"; false
        }
    }
    private fun selectedFixture() = CaptureQaAcceptance.fixtures[fixture.selectedItemPosition]
    private fun selectedScope() = CaptureQaAcceptance.scopes[scope.selectedItemPosition]
    private fun arm() {
        if (job?.isActive == true) return
        val pkg = selectedFixture(); val mode = selectedScope(); val saveImage = save.isChecked
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
    private fun showOneStopResult() {
        val checkpoint = DebugOneStopQa.lastResult(this)
        val batch = DebugOneStopQa.lastBatchResult(this)
        report.text = if (checkpoint == null && batch == null) "No matching-source saved Debug six-key checkpoint."
            else "PRIVATE DEBUG QA HISTORY, not a live input result.\n" +
                "Batch: " + (batch?.toString() ?: "none") + "\nLast run: " +
                (checkpoint?.toString() ?: "none")
    }

    /** Explicit local export only: no pixels, user text, URLs or native node data.
     * Debug private files remain separate from the production package. */
    private fun shareOneStopJson() {
        if (DebugQaRunnerService.isRunning()) {
            status.text = "QA still running; sharing is available after the batch finishes."
            return
        }
        val run = DebugOneStopQa.lastResult(this)
        val batch = DebugOneStopQa.lastBatchResult(this)
        if (run == null && batch == null) {
            report.text = "No saved same-source Debug QA evidence to share."
            return
        }
        try {
            val exports = File(cacheDir, "capture-qa/exports").apply { mkdirs() }
            val file = File(exports, "debug-qa-${UUID.randomUUID()}.json")
            file.writeText(buildJsonObject {
                put("format", "debug_qa_redacted_v1")
                run?.let { put("last_run", it) }
                batch?.let { put("last_batch", it) }
            }.toString(), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.captureqa", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Redacted Debug QA evidence", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share Debug QA diagnostic JSON"))
            status.text = "Redacted JSON prepared for user-selected sharing."
        } catch (_: Exception) {
            status.text = "Debug QA JSON sharing unavailable; no export claimed."
        }
    }

    private fun armOneStop(diagnosticOnly: Boolean) {
        if (job?.isActive == true || armedAfterConsent || DebugQaRunnerService.isRunning()) return
        if (!DebugImeInteractConsent.selectedSamsungIme(this)) {
            status.text = "Samsung keyboard is not the OS-selected enabled IME; no QA action started."
            return
        }
        if (!DebugImeInteractConsent.explicitlyGranted(this)) {
            // Local, physical user action only. Never silently grant this permission
            // or use ADB/shared-preferences modification as a substitute for consent.
            android.app.AlertDialog.Builder(this)
                .setTitle("Samsung keyboard interaction · Debug only")
                .setMessage("This candidate needs narrowly scoped Samsung keyboard interaction permission. Structural diagnosis does not type keys but still verifies the same native safety policy. Only the separate guarded six-key run attempts typing. Android accessibility and screen-sharing approvals remain separate. Existing Chrome content is not overwritten. You may revoke this Debug-only grant here.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Allow for Debug QA") { _, _ ->
                    if (DebugImeInteractConsent.updateFromLocalConsent(this, grant = true)) {
                        refreshSetup()
                        armOneStop(diagnosticOnly) // Continuation of the same local user choice.
                    } else {
                        status.text = "Debug IME consent refused: OS keyboard or existing policy does not permit a narrow grant."
                    }
                }.show()
            return
        }
        if (ProjectionImageTransport.active()?.screenObservationAllowed() != true) {
            armedAfterConsent = true
            pendingDiagnosticOnly = diagnosticOnly
            if (requestObservation()) {
                status.text = "Approve Android screen sharing; selected Debug QA mode will continue automatically."
            } else {
                armedAfterConsent = false
                pendingDiagnosticOnly = false
            }
            return
        }
        runGuardedSixKeyNow(diagnosticOnly)
    }

    private fun runGuardedSixKeyNow(diagnosticOnly: Boolean) {
        if (isDestroyed || isFinishing || DebugQaRunnerService.isRunning()) return
        // Launching an Activity from a background FGS is restricted on Android
        // 14+. The physically visible QA screen performs ONLY this launch.
        val chrome = packageManager.getLaunchIntentForPackage("com.android.chrome")
        if (chrome == null) {
            status.text = "Chrome launch unavailable; no guarded QA started."
            return
        }
        previewIfReadyClear()
        report.text = "Foreground Debug QA requested; saved results will survive this screen."
        if (!DebugQaRunnerService.start(this, diagnosticOnly)) {
            status.text = "Foreground Debug runner unavailable or already active."
            return
        }
        try {
            startActivity(chrome)
            status.text = if (diagnosticOnly)
                "No-key diagnostic armed; return here for saved evidence."
            else "Six-key QA armed; return here for saved evidence."
        } catch (_: Exception) {
            DebugQaRunnerService.cancel(this)
            status.text = "Chrome launch failed. No key input should be attempted."
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
        if (DebugQaRunnerService.isRunning()) {
            status.text = "Guarded Debug QA active; stop the local run before deleting stored results."
            return
        }
        try {
            when (CaptureQaAcceptance.deleteEvidence(this, ::revokeAndDelete)) {
                null -> status.text = "QA engine busy; delete refused. Stop that run first."
                false -> status.text = "Evidence deletion incomplete; no cleanup success claimed."
                true -> {
                    val privateResultsDeleted = File(filesDir, "debug-qa").let {
                        !it.exists() || it.deleteRecursively()
                    }
                    previewIfReadyClear()
                    report.text = if (privateResultsDeleted)
                        "Capture and persistent Debug QA evidence deleted while both runners were idle."
                    else "Capture evidence deleted; persistent Debug QA evidence deletion incomplete."
                }
            }
        } catch (_: Exception) { status.text = "Evidence deletion failed; no cleanup success claimed." }
    }
}
