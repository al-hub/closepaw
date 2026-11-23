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
import kotlinx.coroutines.NonCancellable
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

    /** Content-free native readiness facts; these do not relax native action guards. */
    internal data class EditorPreflightFacts(
        val frameReady: Boolean, val complete: Boolean, val navigationSafe: Boolean,
        val chromeForeground: Boolean, val keyboardPresent: Boolean, val samsungIme: Boolean,
        val keyboardReadAllowed: Boolean, val keyboardInteractAllowed: Boolean,
        val editorPresent: Boolean, val chromeEditorOwned: Boolean,
        val editorFocused: Boolean, val editorEditable: Boolean,
        val nonPassword: Boolean, val editorEmpty: Boolean, val selectionAtOrigin: Boolean,
    ) {
        fun redactedJson() = buildJsonObject {
            put("frame_ready", frameReady); put("frame_complete", complete)
            put("navigation_safe", navigationSafe); put("chrome_foreground", chromeForeground)
            put("keyboard_present", keyboardPresent); put("samsung_ime", samsungIme)
            put("keyboard_read_allowed", keyboardReadAllowed)
            put("keyboard_interact_allowed", keyboardInteractAllowed)
            put("editor_present", editorPresent); put("chrome_editor_owned", chromeEditorOwned)
            put("editor_focused", editorFocused); put("editor_editable", editorEditable)
            put("non_password", nonPassword); put("editor_empty", editorEmpty)
            put("selection_at_origin", selectionAtOrigin)
        }
    }

    internal fun editorPreflightFacts(frame: ControlFrame): EditorPreflightFacts {
        val keyboard = frame.keyboard
        val editor = frame.inputTarget
        return EditorPreflightFacts(
            frameReady = frame.reason == null, complete = !frame.truncated,
            navigationSafe = !frame.navigationOnly, chromeForeground = frame.packageName == CHROME,
            keyboardPresent = keyboard != null, samsungIme = keyboard?.packageName == SAMSUNG_IME,
            keyboardReadAllowed = keyboard?.readAllowed == true,
            keyboardInteractAllowed = keyboard?.interactAllowed == true,
            editorPresent = editor != null, chromeEditorOwned = editor?.sourcePackage == CHROME,
            editorFocused = editor?.focused == true, editorEditable = editor?.editable == true,
            nonPassword = editor != null && !editor.password,
            editorEmpty = editor?.text?.isEmpty() == true,
            selectionAtOrigin = editor?.selectionStart == 0 && editor.selectionEnd == 0,
        )
    }

    internal fun editorPreflightReason(f: EditorPreflightFacts): String? = when {
        !f.frameReady -> "preflight_frame_unavailable"
        !f.complete -> "preflight_frame_truncated"
        !f.navigationSafe -> "preflight_navigation_only"
        !f.chromeForeground -> "preflight_chrome_not_foreground"
        !f.keyboardPresent -> "preflight_keyboard_missing"
        !f.samsungIme -> "preflight_samsung_ime_missing"
        !f.keyboardReadAllowed -> "preflight_keyboard_read_denied"
        !f.keyboardInteractAllowed -> "preflight_keyboard_interact_denied"
        !f.editorPresent -> "preflight_editor_missing"
        !f.chromeEditorOwned -> "preflight_editor_owner_mismatch"
        !f.editorFocused -> "preflight_editor_not_focused"
        !f.editorEditable -> "preflight_editor_not_editable"
        !f.nonPassword -> "preflight_password_editor"
        !f.editorEmpty -> "preflight_editor_not_empty"
        !f.selectionAtOrigin -> "preflight_selection_not_origin"
        else -> null
    }

    /** A fresh full native security scan is allowed once for a transient invalid window.
     * Never retry a proven secure window, an unknown failure, or any dispatched action. */
    internal fun readOnlyResampleReason(reason: String?): Boolean =
        // A root/window change during traversal is a read-only race, not an
        // authorization to replay any action. Only one fresh capture is allowed.
        reason == "active_root_missing" || reason == "secure_window_changed" ||
            reason == "screen_changed"


    /** Native complete Chrome frame only; no content or URL is exported. */
    private fun safeChromeFrame(frame: ControlFrame): Boolean =
        frame.reason == null && !frame.truncated && !frame.navigationOnly && frame.packageName == CHROME

    private fun ownedChromeNodes(frame: ControlFrame) = frame.nodes.filter {
        it.sourcePackage == CHROME && it.sourceWindowId == frame.windowId
    }

    internal data class ChromeSelectorFacts(
        val fullFrame: Boolean, val chromeNodeCount: Int, val urlBarCount: Int,
        val searchBoxCount: Int, val clickableCount: Int, val editableCount: Int,
        val enabledCount: Int, val passwordCount: Int, val blankCount: Int,
    ) {
        fun redactedJson() = buildJsonObject {
            put("full_frame", fullFrame); put("chrome_owned_nodes", chromeNodeCount)
            put("url_bar_matches", urlBarCount); put("search_box_matches", searchBoxCount)
            put("clickable_matches", clickableCount); put("editable_matches", editableCount)
            put("enabled_matches", enabledCount); put("password_matches", passwordCount)
            put("blank_matches", blankCount)
            put("duplicate_candidates", urlBarCount + searchBoxCount > 1)
        }
    }

    /** Structural counters only: no text, URL, screenshots or pixel logging. */
    internal fun chromeSelectorFacts(frame: ControlFrame): ChromeSelectorFacts {
        val owned = ownedChromeNodes(frame)
        val bars = owned.filter { it.resourceId == "$CHROME:id/url_bar" }
        val search = owned.filter { it.resourceId == "$CHROME:id/search_box_text" }
        val candidates = bars + search
        return ChromeSelectorFacts(safeChromeFrame(frame), owned.size, bars.size, search.size,
            candidates.count { it.clickable }, candidates.count { it.editable },
            candidates.count { it.enabled }, candidates.count { it.password },
            candidates.count { it.empty && it.text.isEmpty() })
    }

    /** Verified Chrome tab-switcher accessibility role; read-only counter. */
    internal fun chromeTabCount(frame: ControlFrame): Int? {
        if (!safeChromeFrame(frame)) return null
        val button = ownedChromeNodes(frame).singleOrNull {
            it.resourceId == "$CHROME:id/tab_switcher_button" &&
                it.enabled && it.clickable && !it.password
        } ?: return null
        val label = button.description
        if (!label.contains("탭") && !label.contains("tab", ignoreCase = true)) return null
        return Regex("\\d+").findAll(label).mapNotNull { it.value.toIntOrNull() }
            .singleOrNull()?.takeIf { it in 0..9998 }
    }

    internal fun chromeMenuButton(frame: ControlFrame): ai.closepaw.chatgpt.control.ControlNode? {
        if (!safeChromeFrame(frame)) return null
        return ownedChromeNodes(frame).singleOrNull {
            it.resourceId == "$CHROME:id/menu_button" && it.clickable && it.enabled && !it.password
        }
    }

    /** Never select incognito, tab group, history or delete command. */
    internal fun chromeNewTabMenuItem(frame: ControlFrame): ai.closepaw.chatgpt.control.ControlNode? {
        if (!safeChromeFrame(frame)) return null
        val owned = ownedChromeNodes(frame)
        val item = owned.singleOrNull {
            it.resourceId == "$CHROME:id/new_tab_menu_id" &&
                it.clickable && it.enabled && !it.password
        } ?: return null
        val labels = owned.filter {
            it.parent == item.id && it.resourceId == "$CHROME:id/menu_item_text" &&
                it.text in setOf("새 탭", "New tab") && !it.password
        }
        return item.takeIf { labels.size == 1 }
    }

    /** Only after the tab count independently proves a newly created tab. */
    internal fun blankOmnibox(frame: ControlFrame): ai.closepaw.chatgpt.control.ControlNode? {
        if (!safeChromeFrame(frame)) return null
        return ownedChromeNodes(frame).filter { node ->
            node.resourceId in setOf("$CHROME:id/url_bar", "$CHROME:id/search_box_text") &&
                node.clickable && node.enabled && !node.password &&
                node.empty && node.text.isEmpty()
            // Pre-focus Chrome NTP search_box_text may not be editable yet.
            // Editable, focused, empty caret and Samsung IME checks follow click.
        }.singleOrNull()
    }

    internal fun verifiedBlankNewTab(priorTabCount: Int, frame: ControlFrame): Boolean =
        chromeTabCount(frame) == priorTabCount + 1 && blankOmnibox(frame) != null

    /** A missing localized tab counter is not evidence of an unsafe editor.
     * The user-approved New Tab command must have succeeded, and only the uniquely
     * empty Chrome New Tab Page search box is accepted as a count-less fallback.
     * Every actual touch still passes native authority and editor/caret guards. */
    internal fun verifiedBlankAfterNewTabCommand(
        prior: Int?, frame: ControlFrame, newTabCommandConfirmed: Boolean,
    ): Boolean {
        if (!newTabCommandConfirmed) return false
        val after = chromeTabCount(frame)
        if (prior != null && after != null) return verifiedBlankNewTab(prior, frame)
        val target = blankOmnibox(frame) ?: return false
        return target.resourceId == "$CHROME:id/search_box_text"
    }

    /** Candidate-only preparation, separated from the key coroutine state machine.
     * Every action uses GenericControl's original native freshness/privacy guards. */
    private data class ChromeReady(
        val frame: ControlFrame? = null, val failure: String? = null,
        val nativeRefusal: String? = null, val diagnostics: ChromeSelectorFacts? = null,
        val tabsBefore: Int? = null, val tabsAfter: Int? = null,
        val newTabVerified: Boolean = false, val newTabEvidence: String = "none",
        val navigationClicks: Int = 0,
        val focusClicks: Int = 0, val unknown: Boolean = false,
    )

    private suspend fun prepareChromeEditor(
        port: AndroidControlPort, control: GenericControl, session: ControlSession,
        allowed: () -> Boolean, onPhase: (String) -> Unit,
    ): ChromeReady {
        var nativeRefusal: String? = null
        var diagnostics: ChromeSelectorFacts? = null
        var tabsBefore: Int? = null
        var tabsAfter: Int? = null
        var newTabVerified = false
        var newTabEvidence = "none"
        var navigationClicks = 0
        var focusClicks = 0
        var unknown = false
        fun result(frame: ControlFrame? = null, failure: String? = null) = ChromeReady(
            frame, failure, nativeRefusal, diagnostics, tabsBefore, tabsAfter,
            newTabVerified, newTabEvidence, navigationClicks, focusClicks, unknown)

        // Fresh read; only a transient invalid window may be resampled once.
        var first = port.capture()
        nativeRefusal = first.reason
        if (readOnlyResampleReason(first.reason)) {
            delay(350L)
            if (!allowed()) return result(failure = "control_authority_revoked")
            first = port.capture()
            nativeRefusal = first.reason
        }
        diagnostics = chromeSelectorFacts(first)
        if (first.reason != null) return result(failure = "preflight_frame_unavailable")
        if (first.truncated) return result(failure = "preflight_frame_truncated")
        if (first.navigationOnly) return result(failure = "preflight_navigation_only")
        if (first.packageName != CHROME) return result(failure = "preflight_chrome_not_foreground")

        /** One fresh observed native click; no coordinates, URL or synthetic text. */
        suspend fun clickChromeNode(
            frame: ControlFrame, target: ai.closepaw.chatgpt.control.ControlNode,
            phase: String, editorFocus: Boolean = false,
        ): Boolean {
            if (!safeChromeFrame(frame) || target.sourcePackage != CHROME ||
                target.sourceWindowId != frame.windowId || !target.clickable ||
                !target.enabled || target.password) return false
            val observed = control.observe()
            if (!observed.success) return false
            val matches = (observed.data["nodes"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.filter {
                    it["resource_id"]?.jsonPrimitive?.content == target.resourceId &&
                        it["class_name"]?.jsonPrimitive?.content == target.className &&
                        it["bounds"]?.jsonPrimitive?.content == target.bounds &&
                        it["clickable"]?.jsonPrimitive?.booleanOrNull == true &&
                        it["enabled"]?.jsonPrimitive?.booleanOrNull == true
                }.orEmpty()
            if (matches.size != 1) return false
            val observationId = observed.data["observation_id"]?.jsonPrimitive?.content
                ?: return false
            val nodeId = matches.single()["node_id"]?.jsonPrimitive?.intOrNull
                ?: return false
            onPhase(phase)
            val effect = control.action(buildJsonObject {
                put("action", "click")
                put("session_id", session.id)
                put("observation_id", observationId)
                put("node_id", nodeId)
            })
            val attempted = effect.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true
            if (attempted) {
                if (editorFocus) focusClicks++ else navigationClicks++
            }
            val actionUnknown = effect.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true
            unknown = unknown || actionUnknown || (attempted && !effect.success)
            return effect.success && !actionUnknown
        }

        // Preserve every existing tab: create one new tab through verified Chrome UI.
        val previousCount = chromeTabCount(first)
        tabsBefore = previousCount
        val menu = chromeMenuButton(first)
            ?: return result(failure = "unique_chrome_menu_unavailable")
        if (!clickChromeNode(first, menu, "chrome_menu_dispatch"))
            return result(failure = "chrome_menu_unconfirmed")
        val popup = port.capture()
        nativeRefusal = popup.reason
        val newTabCommand = chromeNewTabMenuItem(popup)
            ?: return result(failure = "verified_new_tab_menu_unavailable")
        if (!clickChromeNode(popup, newTabCommand, "chrome_new_tab_dispatch"))
            return result(failure = "chrome_new_tab_unconfirmed")

        // At most three passive probes and zero action replay.
        onPhase("new_blank_tab_preflight")
        var newTabFrame: ControlFrame? = null
        for (read in 0..2) {
            delay(if (read == 0) 350L else 250L)
            if (!allowed()) return result(failure = "control_authority_revoked")
            val probe = port.capture()
            nativeRefusal = probe.reason
            diagnostics = chromeSelectorFacts(probe)
            if (probe.reason != null && !readOnlyResampleReason(probe.reason))
                return result(failure = "new_tab_native_security_refusal")
            if (probe.truncated || probe.navigationOnly)
                return result(failure = "new_tab_incomplete_frame")
            if (verifiedBlankAfterNewTabCommand(previousCount, probe, newTabCommandConfirmed = true)) {
                newTabFrame = probe
                break
            }
        }
        first = newTabFrame ?: return result(failure = "new_blank_chrome_tab_unverified")
        tabsAfter = chromeTabCount(first)
        newTabVerified = true
        newTabEvidence = if (previousCount != null && tabsAfter != null)
            "tab_count_increment_and_blank_ntp" else "confirmed_new_tab_command_and_blank_ntp"

        // Pre-focus non-editable NTP search box is allowed only in this proven
        // new tab; post-focus editor, Samsung IME and 0/0 caret remain mandatory.
        val target = blankOmnibox(first)
            ?: return result(failure = "unique_blank_chrome_omnibox_unavailable")
        if (first.inputTarget == null || first.keyboard?.packageName != SAMSUNG_IME) {
            if (!clickChromeNode(first, target, "chrome_editor_focus_dispatch", editorFocus = true))
                return result(failure = "chrome_editor_focus_unconfirmed")
            delay(350L)
            if (!allowed()) return result(failure = "control_authority_revoked")
            first = port.capture()
            nativeRefusal = first.reason
            if (readOnlyResampleReason(first.reason)) {
                delay(350L)
                if (!allowed()) return result(failure = "control_authority_revoked")
                first = port.capture()
                nativeRefusal = first.reason
            }
            diagnostics = chromeSelectorFacts(first)
        }
        onPhase("editor_preflight")
        return result(frame = first)
    }

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

    private fun reportFile(context: Context) = File(context.filesDir, "debug-qa/one-stop-result.json")
    private fun batchFile(context: Context) = File(context.filesDir, "debug-qa/one-stop-batch.json")
    internal fun lastBatchResult(context: Context): JsonObject? {
        if (!BuildConfig.DEBUG || context.packageName != CaptureQaAcceptance.CANDIDATE) return null
        val file = batchFile(context)
        if (!file.isFile || file.length() !in 1L..64_000L) return null
        return runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?.takeIf { it["source_commit"]?.jsonPrimitive?.content == BuildConfig.CAPTURE_QA_SOURCE_SHA }
    }
    internal fun saveBatch(context: Context, id: String, state: String, results: List<JsonObject>) {
        require(BuildConfig.DEBUG && context.packageName == CaptureQaAcceptance.CANDIDATE)
        val snapshot = buildJsonObject {
            put("schema_version", 2); put("batch_id", id)
            put("source_commit", BuildConfig.CAPTURE_QA_SOURCE_SHA)
            put("version_name", BuildConfig.VERSION_NAME); put("version_code", BuildConfig.VERSION_CODE)
            put("state", state); put("repetitions_requested", 3)
            put("repetitions_completed", results.count { it["outcome"]?.jsonPrimitive?.content == "PASS" })
            put("runs", JsonArray(results))
            put("last_updated_epoch_ms", System.currentTimeMillis())
        }
        atomicWrite(batchFile(context), snapshot)
    }
    internal fun recordUnavailable(context: Context, reason: String) {
        val snapshot = buildJsonObject {
            put("schema_version", 2); put("run_id", UUID.randomUUID().toString())
            put("source_commit", BuildConfig.CAPTURE_QA_SOURCE_SHA)
            put("version_name", BuildConfig.VERSION_NAME); put("version_code", BuildConfig.VERSION_CODE)
            put("fixture_package", CHROME); put("suite", "six_key_first_pass")
            put("outcome", "BLOCKED"); put("reason", reason); put("completed_keys", 0)
            put("touch_attempts", 0); put("recovery_attempts", 0)
            put("ended_at_epoch_ms", System.currentTimeMillis())
        }
        write(context, snapshot)
    }

    /** Read-only persisted checkpoint, never an authorization or replay token. */
    fun lastResult(context: Context): JsonObject? {
        if (!BuildConfig.DEBUG || context.packageName != CaptureQaAcceptance.CANDIDATE) return null
        val file = reportFile(context)
        if (!file.isFile || file.length() !in 1L..64_000L) return null
        return runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?.takeIf { item ->
                item["source_commit"]?.jsonPrimitive?.content == BuildConfig.CAPTURE_QA_SOURCE_SHA &&
                    item["suite"]?.jsonPrimitive?.content == "six_key_first_pass" &&
                    item["fixture_package"]?.jsonPrimitive?.content == CHROME
            }
    }

    private fun write(context: Context, result: JsonObject) {
        atomicWrite(reportFile(context), result)
        if (result["outcome"]?.jsonPrimitive?.content != "IN_PROGRESS") {
            val id = result["run_id"]?.jsonPrimitive?.content.orEmpty()
            if (id.matches(Regex("[a-f0-9-]{36}"))) {
                atomicWrite(File(context.filesDir, "debug-qa/history/$id.json"), result)
            }
        }
    }

    private fun atomicWrite(file: File, result: JsonObject) {
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
        var preflightFacts: EditorPreflightFacts? = null
        var nativePreflightRefusal: String? = null
        var chromeSelectorDiagnostics: ChromeSelectorFacts? = null
        var priorChromeTabCount: Int? = null
        var newChromeTabCount: Int? = null
        var newChromeTabVerified = false
        var navigationClickAttempts = 0
        val started = SystemClock.elapsedRealtime()
        val startedEpochMs = System.currentTimeMillis()
        var newTabEvidence = "none"
        fun result(outcome: String, reason: String) = buildJsonObject {
            put("schema_version", 1)
            put("run_id", runId)
            put("source_commit", BuildConfig.CAPTURE_QA_SOURCE_SHA)
            put("version_name", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("started_at_epoch_ms", startedEpochMs)
            if (outcome != "IN_PROGRESS") put("ended_at_epoch_ms", System.currentTimeMillis())
            put("fixture_package", CHROME)
            put("input_method_package", SAMSUNG_IME)
            put("suite", "six_key_first_pass")
            put("stage", stage)
            put("last_safe_checkpoint", stage)
            put("outcome", outcome)
            put("reason", reason)
            put("completed_keys", verifiedKeys)
            put("touch_attempts", attempts)
            put("editor_focus_click_attempts", setupClickAttempts)
            put("navigation_click_attempts", navigationClickAttempts)
            put("new_chrome_tab_verified", newChromeTabVerified)
            put("new_chrome_tab_evidence", newTabEvidence)
            priorChromeTabCount?.let { put("chrome_tabs_before", it) }
            newChromeTabCount?.let { put("chrome_tabs_after", it) }
            chromeSelectorDiagnostics?.let { put("chrome_selector_diagnostics", it.redactedJson()) }
            put("outcome_unknown", uncertain)
            put("projection_active", ProjectionImageTransport.active()?.screenObservationAllowed() == true)
            put("control_session_state", AgentService.instance?.remoteControlSession?.state?.name ?: "unavailable")
            put("recovery_attempts", 0)
            put("actual_keys_dispatched", attempts)
            // Only proven benchmark prefixes, never arbitrary Chrome/user contents.
            put("confirmed_text", SixKey.expected(verifiedKeys))
            put("confirmed_caret", SixKey.expected(verifiedKeys).length)
            if (outcome == "PASS") {
                put("final_text", SixKey.expected(6))
                put("final_caret_start", 4); put("final_caret_end", 4)
            }
            put("goal_verified", outcome == "PASS" && verifiedKeys == SixKey.labels.size)
            put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            put("repetitions_completed", if (outcome == "PASS") 1 else 0)
            preflightFacts?.let { put("editor_preflight_checks", it.redactedJson()) }
            // A native fixed reason code only: never store Chrome content, package text or pixels.
            nativePreflightRefusal?.takeIf {
                it.length in 1..80 && it.matches(Regex("[a-z0-9_]+"))
            }?.let { put("native_preflight_refusal", it) }
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

        // Chrome was opened by the physically visible QA Activity immediately
        // after starting the foreground runner. A background FGS must NOT try to
        // bypass Android background-activity-launch restrictions.
        stage = "await_chrome"
        checkpoint("waiting_for_visible_activity_chrome_launch")
        val deadline = SystemClock.elapsedRealtime() + 60_000L
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
            // Chrome's accessibility window can be replaced during the first resume
            // animation. Let the foreground settle before a fresh full security scan.
            delay(350L)
            if (!allowed()) return stop("control_authority_revoked")
            stage = "editor_preflight"
            checkpoint("native_preflight")
            val prepared = prepareChromeEditor(port, control, session, allowed) { phase ->
                stage = phase
                checkpoint("guarded_chrome_preparation")
            }
            nativePreflightRefusal = prepared.nativeRefusal
            chromeSelectorDiagnostics = prepared.diagnostics
            priorChromeTabCount = prepared.tabsBefore
            newChromeTabCount = prepared.tabsAfter
            newChromeTabVerified = prepared.newTabVerified
            newTabEvidence = prepared.newTabEvidence
            navigationClickAttempts = prepared.navigationClicks
            setupClickAttempts = prepared.focusClicks
            uncertain = prepared.unknown
            val first = prepared.frame ?: return stop(prepared.failure ?: "chrome_preparation_refused")
            // Independent reason codes and booleans are written before any key dispatch.
            val diagnosed = editorPreflightFacts(first)
            preflightFacts = diagnosed
            editorPreflightReason(diagnosed)?.let { return stop(it) }

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
                if (active) withContext(NonCancellable + Dispatchers.Main) { session.end() }
                ControlScreenLease.mutex.unlock()
            }
        }
    }
}
