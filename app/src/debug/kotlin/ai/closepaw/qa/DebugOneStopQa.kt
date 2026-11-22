package ai.closepaw.qa

import ai.closepaw.BuildConfig
import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.McpExecutionSettings
import ai.closepaw.chatgpt.control.AndroidControlPort
import ai.closepaw.chatgpt.control.ControlScreenLease
import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.GenericControl
import ai.closepaw.chatgpt.control.HangulKeyComposer
import ai.closepaw.platform.virtualdisplay.ProjectionImageTransport
import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Candidate-only locally armed diagnostic. Every actual key goes through
 * production GenericControl + AndroidControlPort native safety checks.
 * No raw ADB, coordinates, text injection, clipboard or submission. */
internal object DebugOneStopQa {
    internal object SixKey {
        val labels = listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space")
        fun expected(completed: Int): String {
            require(completed in 0..labels.size)
            return labels.take(completed).fold("") { current, label ->
                if (label == "Space") current + " " else HangulKeyComposer.append(current, label)
            }
        }
        fun matches(text: String, start: Int, end: Int, completed: Int): Boolean {
            val expected = expected(completed)
            return text == expected && start == expected.length && end == expected.length
        }
        fun mayContinue(attempted: Boolean, unknown: Boolean, verified: Boolean) =
            verified && attempted && !unknown
    }

    // Canonical #147 benchmark table (not the conflicting older candidate
    // sentence in the issue's historical progress section).
    internal const val MIXED_29 = "오늘 회의는 zoom으로 3시에 시작, 자료는 2개."
    private const val CHROME = "com.android.chrome"
    private const val SAMSUNG_IME = "com.samsung.android.honeyboard"

    private fun sameEditor(first: ControlFrame, later: ControlFrame): Boolean {
        val a = first.inputTarget ?: return false
        val b = later.inputTarget ?: return false
        val identity = when {
            !a.nativeEditorId.isNullOrBlank() -> a.nativeEditorId == b.nativeEditorId
            a.resourceId.isNotBlank() -> a.resourceId == b.resourceId
            else -> a.bounds.isNotBlank() && a.bounds == b.bounds && a.parent == b.parent
        }
        return identity && first.windowId == later.windowId &&
            a.className == b.className && a.sourcePackage == b.sourcePackage &&
            a.sourceWindowId == b.sourceWindowId && first.packageName == later.packageName
    }

    private fun write(context: Context, result: JsonObject) {
        val file = File(context.cacheDir, "capture-qa/one-stop/result.json")
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(result.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    suspend fun run(context: Context, status: (String) -> Unit): JsonObject {
        require(BuildConfig.DEBUG && context.packageName == CaptureQaAcceptance.CANDIDATE)
        val runId = UUID.randomUUID().toString()
        val settings = McpExecutionSettings(context)
        var verifiedKeys = 0
        var attempts = 0
        var setupClickAttempts = 0
        var uncertain = false
        var stage = "preflight"
        val started = SystemClock.elapsedRealtime()
        fun result(outcome: String, reason: String) = buildJsonObject {
            put("schema_version", 1)
            put("run_id", runId)
            put("source_commit", BuildConfig.CAPTURE_QA_SOURCE_SHA)
            put("version_name", BuildConfig.VERSION_NAME)
            put("fixture_package", CHROME)
            put("input_method_package", SAMSUNG_IME)
            put("suite", "six_key_first_pass")
            put("stage", stage)
            put("outcome", outcome)
            put("reason", reason)
            put("completed_keys", verifiedKeys)
            put("touch_attempts", attempts)
            put("editor_focus_click_attempts", setupClickAttempts)
            put("outcome_unknown", uncertain)
            put("goal_verified", outcome == "PASS" && verifiedKeys == SixKey.labels.size)
            put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            put("repetitions_completed", if (outcome == "PASS") 1 else 0)
        }
        fun checkpoint(why: String) {
            write(context, result("IN_PROGRESS", why))
            status("Debug six-key: " + stage + ", verified=" + verifiedKeys + "/6, attempts=" + attempts)
        }
        fun stop(why: String) = result(if (uncertain) "UNKNOWN" else "BLOCKED", why)
            .also { write(context, it) }

        if (CaptureQaAcceptance.setup(context) != "ready") return stop("debug_qa_setup_required")
        if (!settings.enabled) return stop("debug_connection_not_enabled")
        val service = AgentService.instance ?: return stop("accessibility_permission_missing")
        val session = service.remoteControlSession
        if (session.state !in setOf(ControlSession.State.IDLE, ControlSession.State.ENDED))
            return stop("another_control_session_active")
        if (ProjectionImageTransport.active() == null) return stop("android_observation_approval_required")

        // The user has explicitly armed this local candidate QA screen.
        // Launch only the already-known Chrome package. This is app launch,
        // NOT text input, ADB injection, inferred URL navigation or a grant.
        // Never modify an existing nonempty Chrome editor.
        stage = "launch_chrome"
        checkpoint("launching_known_chrome_package")
        val launched = withContext(Dispatchers.Main) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(CHROME)
                    ?: return@withContext false
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (_: Exception) { false }
        }
        if (!launched) return stop("chrome_launch_unavailable")
        stage = "await_chrome"
        checkpoint("waiting_for_chrome_foreground")
        val deadline = SystemClock.elapsedRealtime() + 600_000L
        var foreground = false
        while (SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            if (AgentService.instance !== service || !settings.genericControlEnabled ||
                ProjectionImageTransport.active() == null)
                return stop("authority_changed_during_preflight")
            if (session.state !in setOf(ControlSession.State.IDLE, ControlSession.State.ENDED))
                return stop("session_changed_during_preflight")
            foreground = withContext(Dispatchers.Main) {
                val root = service.rootInActiveWindow ?: return@withContext false
                try { root.packageName?.toString() == CHROME } finally { root.recycle() }
            }
            if (foreground) break
            delay(250)
        }
        if (!foreground) return stop("blank_chrome_deadline")
        if (!ControlScreenLease.mutex.tryLock()) return stop("ui_busy")
        var active = false
        val allowed: () -> Boolean = {
            AgentService.instance === service && settings.enabled && settings.genericControlEnabled &&
                ProjectionImageTransport.active() != null &&
                session.explicit && session.state == ControlSession.State.ACTIVE
        }
        val port = AndroidControlPort(context, runtime = settings.runtimeControl, allowed = allowed)
        val control = GenericControl(port, allowed)
        return try {
            if (!withContext(Dispatchers.Main) { session.start() })
                return stop("explicit_session_start_refused")
            active = true
            port.activity(true)
            stage = "editor_preflight"
            checkpoint("native_preflight")
            var first = port.capture()
            // An unselected Chrome omnibox can be blank without an active IME.
            // Focus at most one real, uniquely exposed *empty* Chrome editor
            // through the same GenericControl accessibility action route.
            if (first.reason == null && first.packageName == CHROME &&
                first.keyboard?.packageName != SAMSUNG_IME) {
                // Password/empty status are intentionally not disclosed in
                // public node JSON. Obtain those restrictions from the native
                // complete frame before selecting a single disclosed node ID.
                val nativeCandidates = first.nodes.filter { node ->
                    node.editable && node.clickable && node.enabled && !node.password &&
                        node.empty && node.text.isEmpty() &&
                        node.sourcePackage == CHROME && node.resourceId.endsWith(":id/url_bar")
                }
                if (nativeCandidates.size != 1) return stop("unique_blank_chrome_omnibox_unavailable")
                val permitted = nativeCandidates.single()
                val initial = control.observe()
                if (!initial.success) return stop("chrome_editor_observation_refused")
                val candidate = (initial.data["nodes"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.filter {
                        it["editable"]?.jsonPrimitive?.booleanOrNull == true &&
                        it["clickable"]?.jsonPrimitive?.booleanOrNull == true &&
                        it["enabled"]?.jsonPrimitive?.booleanOrNull == true &&
                        it["text"]?.jsonPrimitive?.content == "" &&
                        it["resource_id"]?.jsonPrimitive?.content == permitted.resourceId &&
                        it["class_name"]?.jsonPrimitive?.content == permitted.className &&
                        it["bounds"]?.jsonPrimitive?.content == permitted.bounds
                    }.orEmpty()
                if (candidate.size != 1) return stop("unique_blank_chrome_omnibox_changed")
                val observationId = initial.data["observation_id"]?.jsonPrimitive?.content
                    ?: return stop("chrome_editor_observation_id_missing")
                val nodeId = candidate.single()["node_id"]?.jsonPrimitive?.intOrNull
                    ?: return stop("chrome_editor_node_id_missing")
                stage = "chrome_editor_focus_dispatch"
                checkpoint("one_native_accessibility_focus_click")
                val focused = control.action(buildJsonObject {
                    put("action", "click")
                    put("session_id", session.id)
                    put("observation_id", observationId)
                    put("node_id", nodeId)
                })
                val attempted = focused.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true
                if (attempted) setupClickAttempts++
                if (!focused.success) {
                    uncertain = attempted || focused.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true
                    return stop("chrome_editor_focus_unconfirmed")
                }
                first = port.capture()
                stage = "editor_preflight"
                checkpoint("chrome_editor_focus_observed")
            }
            val target = first.inputTarget
            if (first.reason != null || first.truncated || first.navigationOnly ||
                first.packageName != CHROME || first.keyboard?.packageName != SAMSUNG_IME ||
                first.keyboard?.readAllowed != true || first.keyboard?.interactAllowed != true ||
                target == null || !target.focused || !target.editable || target.password ||
                !SixKey.matches(target.text, target.selectionStart, target.selectionEnd, 0))
                return stop("empty_chrome_samsung_editor_unverified")

            for ((index, label) in SixKey.labels.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!allowed()) return stop("control_authority_revoked")
                stage = "key_" + (index + 1) + "_preflight"
                checkpoint("fresh_keyboard_observation")
                val before = port.capture()
                if (!sameEditor(first, before)) return stop("editor_identity_changed")
                val field = before.inputTarget
                if (before.reason != null || before.packageName != CHROME ||
                    before.keyboard?.packageName != SAMSUNG_IME || field == null ||
                    !SixKey.matches(field.text, field.selectionStart, field.selectionEnd, index))
                    return stop("editor_checkpoint_changed")
                val observed = control.observe(includeImage = true, imageScope = "keyboard",
                    keyboardKeyExact = label)
                if (!observed.success) return stop("keyboard_observation_refused")
                val observationId = observed.data["observation_id"]?.jsonPrimitive?.content
                    ?: return stop("observation_id_missing")
                val matches = (observed.data["nodes"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?.filter { it["keyboard_key"]?.jsonPrimitive?.content == label &&
                        it["enabled"]?.jsonPrimitive?.booleanOrNull == true }
                    .orEmpty()
                if (matches.size != 1) return stop("unique_physical_key_not_proven")
                val nodeId = matches.single()["node_id"]?.jsonPrimitive?.intOrNull
                    ?: return stop("physical_key_id_missing")
                stage = "key_" + (index + 1) + "_dispatch"
                checkpoint("one_guarded_key")
                val action = control.action(buildJsonObject {
                    put("action", "key")
                    put("session_id", session.id)
                    put("observation_id", observationId)
                    put("node_id", nodeId)
                })
                val attempted = action.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true
                val unknown = action.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true
                if (attempted) attempts++
                uncertain = uncertain || unknown
                if (!SixKey.mayContinue(attempted, unknown, action.success))
                    return stop(action.data["reason_code"]?.jsonPrimitive?.content ?: "key_effect_unconfirmed")
                val after = port.capture()
                if (!sameEditor(first, after)) {
                    uncertain = true
                    return stop("editor_identity_after_touch_unverified")
                }
                val post = after.inputTarget
                if (after.reason != null || after.packageName != CHROME || post == null ||
                    !SixKey.matches(post.text, post.selectionStart, post.selectionEnd, index + 1)) {
                    uncertain = true
                    return stop("independent_key_postcondition_failed")
                }
                verifiedKeys++
                stage = "key_" + (index + 1) + "_confirmed"
                checkpoint("independent_editor_confirmed")
            }
            stage = "six_key_final"
            val final = port.capture()
            val field = final.inputTarget
            if (!sameEditor(first, final)) {
                uncertain = attempts > 0
                return stop("final_editor_identity_unverified")
            }
            if (final.reason != null || final.packageName != CHROME || field == null ||
                !SixKey.matches(field.text, field.selectionStart, field.selectionEnd, 6)) {
                uncertain = attempts > 0
                stop("six_key_final_read_unconfirmed")
            } else result("PASS", "six_physical_keys_editor_and_caret_confirmed").also { write(context, it) }
        } catch (cancel: CancellationException) {
            uncertain = uncertain || stage.endsWith("_dispatch")
            stop("run_cancelled")
        } catch (_: Exception) {
            uncertain = uncertain || stage.endsWith("_dispatch")
            stop("guarded_qa_failed")
        } finally {
            control.invalidate()
            try { port.activity(false) } finally {
                if (active) withContext(Dispatchers.Main) { session.end() }
                ControlScreenLease.mutex.unlock()
            }
        }
    }
}
