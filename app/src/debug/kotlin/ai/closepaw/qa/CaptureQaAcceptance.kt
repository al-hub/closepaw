package ai.closepaw.qa

import ai.closepaw.BuildConfig
import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.McpExecutionSettings
import ai.closepaw.chatgpt.control.AndroidControlPort
import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.chatgpt.control.TapBounds
import ai.closepaw.platform.virtualdisplay.ProjectionImageTransport
import ai.closepaw.security.ImageRevocationAuthority
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** DEBUG source only. Both local UI and ADB use this exact guarded capture path.
 * No permissions, app policy, gestures or text input are granted/dispatched here. */
internal object CaptureQaAcceptance {
    const val CANDIDATE = "ai.closepaw.capturecandidate239"
    val fixtures = listOf("com.android.chrome", "com.samsung.android.app.notes")
    val scopes = listOf("screen_observe", "keyboard", "display", "revoked")
    private var positiveEpoch: Long? = null
    private var positiveService = WeakReference<AgentService>(null)
    private var positiveAt = 0L
    private var positiveFixture: String? = null
    private var positiveRunId: String? = null
    private var running = false

    @Synchronized private fun acquire(): Boolean = if (running) false else { running = true; true }
    @Synchronized private fun release() { running = false }
    @Synchronized fun isRunning() = running
    fun <T> withIdleEvidence(action: () -> T): T? {
        if (!acquire()) return null
        return try { action() } finally { release() }
    }
    fun deleteEvidence(context: Context, revokeExport: (File) -> Unit): Boolean? = withIdleEvidence {
        require(BuildConfig.DEBUG && context.packageName == CANDIDATE)
        File(context.cacheDir, "capture-qa/exports").listFiles().orEmpty().forEach(revokeExport)
        val deleted = File(context.cacheDir, "capture-qa/runs").deleteRecursively()
        clearBaseline()
        deleted
    }
    @Synchronized private fun clearBaseline() {
        positiveEpoch = null; positiveFixture = null; positiveRunId = null
        positiveService.clear(); positiveAt = 0L
    }
    fun directory(context: Context, runner: String, fixture: String, scope: String): File {
        require(runner in setOf("local", "adb") && fixture in fixtures && scope in scopes)
        return File(context.cacheDir, "capture-qa/runs/$runner-${fixtures.indexOf(fixture)}-$scope")
    }
    fun setup(context: Context): String = when {
        !BuildConfig.DEBUG || context.packageName != CANDIDATE -> "candidate_required"
        !BuildConfig.CAPTURE_QA_SOURCE_SHA.matches(Regex("[a-f0-9]{40}")) -> "verified_source_required"
        AgentService.instance == null -> "accessibility_required"
        !Settings.canDrawOverlays(context) -> "overlay_required"
        !McpExecutionSettings(context).genericControlEnabled -> "generic_control_required"
        else -> "ready"
    }

    suspend fun run(context: Context, runner: String, fixture: String, scope: String,
        waitMs: Long, saveImage: Boolean, status: (String) -> Unit = {}): JsonObject {
        require(BuildConfig.DEBUG && context.packageName == CANDIDATE)
        require(runner in setOf("local", "adb") && fixture in fixtures && scope in scopes)
        require(waitMs in 30_000L..600_000L)
        check(acquire()) { "qa_already_running" }
        if (scope == "screen_observe") clearBaseline()
        var owned: CaptureQaOwner? = null
        val output = directory(context, runner, fixture, scope)
        val runId = UUID.randomUUID().toString()
        var result: JsonObject? = null
        fun report(outcome: String, reason: String, visual: JsonObject? = null, bytes: ByteArray? = null) = buildJsonObject {
            put("schema_version", 1); put("run_id", runId); put("runner", runner)
            put("source_commit", BuildConfig.CAPTURE_QA_SOURCE_SHA)
            put("version_name", BuildConfig.VERSION_NAME); put("version_code", BuildConfig.VERSION_CODE)
            put("fixture_package", fixture); put("requested_scope", scope)
            put("outcome", outcome); put("reason", reason)
            put("capture_succeeded", outcome == "CAPTURE_PASS")
            put("visual_actions_dispatched", 0); put("manual_pixels_reviewed", false)
            put("evidence_kind", "historical_private_qa_not_live_control")
            put("image_saved", bytes != null && saveImage)
            if (scope == "revoked") positiveRunId?.let { put("baseline_run_id", it) }
            visual?.let { put("visual", it) }
            bytes?.let { put("image_sha256", sha256(it)); put("image_bytes", it.size) }
        }
        try {
            withContext(Dispatchers.IO) {
                output.mkdirs()
                File(output, "result.json").delete(); File(output, "preview.jpg").delete()
            }
            val setup = withContext(Dispatchers.Main) { setup(context) }
            if (setup != "ready") return report("SETUP_BLOCKED", setup).also { result = it }
            val service = requireNotNull(AgentService.instance)
            val settings = McpExecutionSettings(service)
            val initialLease = ProjectionImageTransport.active()
            val imageEpoch = ImageRevocationAuthority.currentEpoch
            if (scope == "revoked") {
                if (initialLease != null || ImageRevocationAuthority.isConsentGranted || positiveEpoch == null ||
                    positiveService.get() !== service || positiveFixture != fixture || imageEpoch <= positiveEpoch!! ||
                    SystemClock.elapsedRealtime() - positiveAt !in 0L..600_000L) {
                    return report("SETUP_BLOCKED", "capture_then_stop_same_process_first").also { result = it }
                }
            } else if (initialLease?.screenObservationAllowed() != true) {
                return report("SETUP_BLOCKED", "observation_approval_required").also { result = it }
            }
            val session = service.remoteControlSession
            val started = withContext(Dispatchers.Main) {
                if (AgentService.instance !== service || !settings.genericControlEnabled ||
                    session.state !in setOf(ControlSession.State.IDLE, ControlSession.State.ENDED)) false
                else { owned = CaptureQaOwner.start(session); owned != null }
            }
            if (!started) return report("SETUP_BLOCKED", "existing_session_preserved").also { result = it }
            fun guard(): String? = when {
                AgentService.instance !== service -> "accessibility_service_changed"
                owned?.current() != true -> "user_control_or_session_changed"
                !settings.genericControlEnabled -> "generic_control_revoked"
                context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
                    !context.getSystemService(PowerManager::class.java).isInteractive -> "device_locked"
                scope != "revoked" && (ProjectionImageTransport.active() !== initialLease ||
                    initialLease?.screenObservationAllowed() != true || ImageRevocationAuthority.currentEpoch != imageEpoch) -> "projection_changed_or_revoked"
                scope == "revoked" && (ProjectionImageTransport.active() != null || ImageRevocationAuthority.isConsentGranted ||
                    ImageRevocationAuthority.currentEpoch != imageEpoch) -> "new_projection_not_accepted"
                else -> null
            }
            val deadline = SystemClock.elapsedRealtime() + waitMs
            var sceneReady = false
            var stableAt = 0L
            var lastStatus: String? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                guard()?.let { return report("STOPPED", it).also { result = it } }
                val scene = withContext(Dispatchers.Main) {
                    val root = service.rootInActiveWindow
                    val windows = service.windows.orEmpty()
                    try {
                        (root?.packageName?.toString() == fixture) to
                            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    } finally { root?.recycle(); windows.forEach { it.recycle() } }
                }
                val next = "armed=true fixture=${scene.first} ime=${scene.second}"
                if (next != lastStatus) { status(next); lastStatus = next }
                if (scene.first && scene.second) {
                    if (stableAt == 0L) stableAt = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - stableAt >= 1_000L) { sceneReady = true; break }
                } else stableAt = 0L
                delay(250)
            }
            if (!sceneReady) return report("SETUP_BLOCKED", "blank_fixture_deadline").also { result = it }
            guard()?.let { return report("STOPPED", it).also { result = it } }
            val port = AndroidControlPort(service, runtime = settings.runtimeControl) { settings.genericControlEnabled }
            val frame = port.capture()
            guard()?.let { return report("STOPPED", it).also { result = it } }
            if (frame.reason != null) return report("CAPTURE_BLOCKED", safeReason(frame.reason)).also { result = it }
            val input = frame.inputTarget
            val fixtureBlocker = CaptureQaFixtureReadiness.blocker(
                fixtureMatches = frame.packageName == fixture,
                keyboardPresent = frame.keyboard != null,
                samsungKeyboard = frame.keyboard?.packageName == "com.samsung.android.honeyboard",
                editorPresent = input != null,
                editorEmpty = input?.empty == true,
                editorEditable = input?.editable == true,
                editorFocused = input?.focused == true,
                editorNotPassword = input?.let { !it.password } == true,
            )
            if (fixtureBlocker != null) {
                return report("SETUP_BLOCKED", fixtureBlocker).also { result = it }
            }
            val proof = when (scope) {
                "keyboard" -> port.visualKeyboard(frame)
                "display" -> port.visualDisplay(frame)
                else -> port.visualScreenObserve(frame)
            }
            guard()?.let { return report("STOPPED", it).also { result = it } }
            if (scope != "screen_observe") {
                val expected = when (scope) {
                    "keyboard" -> "screenshot_buffer_geometry_mismatch"
                    "display" -> "display_isolated_screenshot_buffer_geometry_mismatch"
                    else -> "screen_share_revoked"
                }
                val reason = if (proof == null) safeReason(port.visualFailure()) else "unexpected_image"
                return report(if (proof == null && reason == expected) "REFUSAL_PASS" else "REFUSAL_NOT_CONFIRMED", reason).also { result = it }
            }
            if (proof == null) return report("CAPTURE_BLOCKED", safeReason(port.visualFailure())).also { result = it }
            if (proof.scope != "screen_observe" || proof.sourceDisplayId != 0 || proof.image.bytes.isEmpty() ||
                proof.image.bytes.size > 1_500_000 || proof.disclosedRegions.isNotEmpty() ||
                proof.map(TapBounds(0, 0, 1, 1)) != null || !proof.sourceDisclosureAllowed()) {
                return report("CAPTURE_FAIL", "observation_contract_failed").also { result = it }
            }
            val completed = report("CAPTURE_PASS", "native_observation_contract_passed", proof.metadata(), proof.image.bytes)
            // Explicitly saved historical fixture evidence, never a live VisualImageCache entry.
            // Recheck owner/source immediately before the optional bounded private write.
            guard()?.let { return report("STOPPED", it).also { result = it } }
            if (!proof.sourceDisclosureAllowed()) return report("STOPPED", "source_revoked_before_save").also { result = it }
            if (saveImage) withContext(Dispatchers.IO) { File(output, "preview.jpg").writeBytes(proof.image.bytes) }
            if (guard() != null || !proof.sourceDisclosureAllowed()) {
                File(output, "preview.jpg").delete()
                return report("STOPPED", "source_or_owner_changed_during_save").also { result = it }
            }
            positiveEpoch = imageEpoch; positiveService = WeakReference(service); positiveAt = SystemClock.elapsedRealtime()
            positiveFixture = fixture; positiveRunId = runId
            return completed.also { result = it }
        } catch (cancel: CancellationException) {
            result = report("STOPPED", "cancelled")
            throw cancel
        } catch (_: Exception) {
            return report("CAPTURE_BLOCKED", "qa_exception").also { result = it }
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.Main) {
                    owned?.close()
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    if (result?.get("capture_succeeded")?.jsonPrimitive?.booleanOrNull != true) File(output, "preview.jpg").delete()
                    result?.let { report ->
                        val atomic = android.util.AtomicFile(File(output, "result.json"))
                        val stream = atomic.startWrite()
                        try { stream.write(report.toString().toByteArray()); atomic.finishWrite(stream) }
                        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
                    }
                }
            } finally { release() }
        }
    }
    private fun safeReason(value: String) = value.takeIf { it.matches(Regex("[a-z0-9_]{1,160}")) } ?: "native_refusal"
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Debug QA only. Return the first blocker as a fixed code, never actual package or editor data. */
internal object CaptureQaFixtureReadiness {
    fun blocker(
        fixtureMatches: Boolean,
        keyboardPresent: Boolean,
        samsungKeyboard: Boolean,
        editorPresent: Boolean,
        editorEmpty: Boolean,
        editorEditable: Boolean,
        editorFocused: Boolean,
        editorNotPassword: Boolean,
    ): String? = when {
        !fixtureMatches -> "fixture_app_mismatch"
        !keyboardPresent -> "fixture_keyboard_missing"
        !samsungKeyboard -> "fixture_keyboard_not_samsung"
        !editorPresent -> "fixture_editor_unverified"
        !editorNotPassword -> "fixture_editor_password"
        !editorEditable -> "fixture_editor_not_editable"
        !editorFocused -> "fixture_editor_not_focused"
        !editorEmpty -> "fixture_editor_not_empty"
        else -> null
    }
}

/** Fixed at arm time. A resumed/new session is never substituted for this run. */
internal class CaptureQaOwner private constructor(private val session: ControlSession) {
    private val id = session.id
    private val epoch = session.epoch
    fun current() = synchronized(session) {
        session.id == id && session.epoch == epoch && session.state == ControlSession.State.ACTIVE
    }
    fun close() = synchronized(session) { if (session.id == id) session.end() }
    companion object {
        fun start(session: ControlSession): CaptureQaOwner? = synchronized(session) {
            if (session.state in setOf(ControlSession.State.IDLE, ControlSession.State.ENDED) && session.start()) CaptureQaOwner(session) else null
        }
    }
}

/** Comparison is numeric contract equivalence, never JPEG identity or pixel QA. */
internal object CaptureQaComparison {
    fun compare(first: JsonObject, second: JsonObject): String {
        if (setOf(first["runner"]?.jsonPrimitive?.content, second["runner"]?.jsonPrimitive?.content) != setOf("local", "adb")) return "runner_pair_required"
        for (key in listOf("schema_version", "source_commit", "version_name", "version_code", "fixture_package", "requested_scope", "outcome", "reason")) {
            if (first[key] == null || first[key] != second[key]) return "mismatch_$key"
        }
        if (first["source_commit"]?.jsonPrimitive?.content?.matches(Regex("[a-f0-9]{40}")) != true) return "verified_source_required"
        if (first["outcome"]?.jsonPrimitive?.content !in setOf("CAPTURE_PASS", "REFUSAL_PASS")) return "passing_pair_required"
        if (first["outcome"]?.jsonPrimitive?.content == "CAPTURE_PASS") {
            val a = first["visual"] as? JsonObject ?: return "visual_required"
            val b = second["visual"] as? JsonObject ?: return "visual_required"
            for (value in listOf(a, b)) {
                if (value["scope"]?.jsonPrimitive?.content != "screen_observe" ||
                    value["observation_only"]?.jsonPrimitive?.booleanOrNull != true ||
                    value["visual_actions_allowed"]?.jsonPrimitive?.booleanOrNull != false ||
                    value["outside_requested_regions_masked"]?.jsonPrimitive?.booleanOrNull != true ||
                    value["owned_pixels_verified_against_isolated_windows"]?.jsonPrimitive?.booleanOrNull != false ||
                    value["verified_region_count"]?.jsonPrimitive?.intOrNull != 0) return "invalid_observation_contract"
            }
            for (key in listOf("scope", "observation_only", "visual_actions_allowed", "source_display_id", "rotation",
                "image_width", "image_height", "source_width", "source_height", "application_bounds", "ime_bounds",
                "outside_requested_regions_masked", "owned_pixels_verified_against_isolated_windows", "verified_region_count")) {
                if (a[key] == null || a[key] != b[key]) return "mismatch_visual_$key"
            }
        }
        return "numeric_contract_match_pixels_require_separate_review"
    }
}
