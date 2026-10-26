package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.*
import ai.closepaw.tool.action.TypeExecutor
import ai.closepaw.tool.action.UnifiedTextInputPolicy
import java.util.UUID
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal data class ControlNode(val id: Int, val text: String = "", val description: String = "", val resourceId: String = "",
    val clickable: Boolean = false, val editable: Boolean = false, val scrollable: Boolean = false,
    val enabled: Boolean = true, val password: Boolean = false, val empty: Boolean = true,
    val bounds: String = "", val parent: Int = -1, val longClickable: Boolean = false,
    val expandable: Boolean = false, val collapsible: Boolean = false, val className: String = "", val selected: Boolean = false,
    val checkable: Boolean = false, val checked: Boolean = false,
    val focused: Boolean = false, val selectionStart: Int = -1, val selectionEnd: Int = -1,
    val setText: Boolean = false, val paste: Boolean = false, val focusable: Boolean = false,
    val scrollDirections: Set<String> = emptySet(), val sourceWindowId: Int = -1,
    val sourcePackage: String = "", val keyboardKey: String = "", val textEntryKey: Boolean = false, val terminal: TerminalEvidence? = null) {
    fun json() = buildJsonObject {
        put("terminal_input_supported", terminal != null)
        put("focused", focused); put("focusable", focusable); put("set_text_supported", setText); put("paste_supported", paste)
        put("selection_start", selectionStart); put("selection_end", selectionEnd)
        put("scroll_directions", JsonArray(scrollDirections.sorted().map { JsonPrimitive(it) }))
        put("expandable", expandable); put("collapsible", collapsible); put("class_name", className.take(100)); put("selected", selected)
        put("checkable", checkable); put("checked", checked)
        put("source_window_id", sourceWindowId); put("keyboard_key", keyboardKey)
        put("node_id", id); put("text", if (keyboardKey.isNotEmpty()) keyboardKey else text.take(100)); put("description", if (keyboardKey.isNotEmpty()) "" else description.take(100)); put("resource_id", resourceId.take(100))
        put("long_clickable", longClickable); put("bounds", bounds); put("parent", parent); put("clickable", clickable); put("editable", editable); put("scrollable", scrollable); put("enabled", enabled)
    }
}
internal data class ControlFrame(val packageName: String, val windowId: Int, val fingerprint: String,
    val nodes: List<ControlNode>, val reason: String? = null, val truncated: Boolean = false,
    val truncationReasons: Set<String> = emptySet(), val visitedNodes: Int = 0, val maxDepth: Int = 0,
    val touchContext: String = "", val foregroundWindowId: Int = windowId, val windowContext: String = "",
    val windowDiagnostics: JsonObject? = null, val configurationRevision: Long = 0, val outputLimit: Int = 50,
    val verifiedApplicationPackage: String? = null, val keyboard: ControlKeyboard? = null, val inputTargetId: Int? = null, val inputTarget: ControlNode? = null, val visual: VisualTargetProof? = null,
    val terminalSubmission: TerminalSubmission? = null, val keyboardDiagnostics: JsonObject? = null,
    val navigationOnly: Boolean = false)
/** Logical identity is supplied only by a complete, guarded native application identity scan. */
internal fun ControlFrame.matchesApplication(expected: String?): Boolean = expected != null && reason == null &&
    (packageName == expected || (!truncated && verifiedApplicationPackage == expected))

internal data class ControlEffect(val attempted: Boolean, val acknowledged: Boolean, val reason: String)
internal interface ControlPort : IslandControlPort {
    override fun islandRefusal(sessionId: String): String? = "island_control_unavailable"
    override suspend fun observeIsland(): IslandBounds? = null
    override suspend fun dragIsland(before: IslandBounds, dx: Int, dy: Int, holdMs: Long, moveMs: Long, sessionId: String): Boolean = false
    /** Only adapters that enforce the complete freshness policy inside perform may opt in. */
    val validatesAtNativeBoundary: Boolean get() = false
    fun session(): ControlSession? = null
    fun bindResume(check: suspend () -> Boolean) {}
    suspend fun feedback(label: String) {}
    suspend fun presentation(phase: ControlSession.VisualPhase, expectedEpoch: Long?) {}
    suspend fun feedbackAtEpoch(label: String, expectedEpoch: Long?) { feedback(label) }
    suspend fun activity(active: Boolean) {}
    suspend fun feedbackTest(durationMs: Long): OperationResult = OperationResult(success = false, summary = "feedback_test_unavailable")
    suspend fun captureAfter(frame: ControlFrame, input: JsonObject): ControlFrame {
        var post = capture()
        repeat(2) {
            if (post.reason !in setOf("active_root_missing", "foreground_application_required", "secure_window_changed")) return post
            kotlinx.coroutines.yield()
            post = capture()
        }
        return post
    }
    suspend fun visual(frame: ControlFrame): VisualTargetProof? = null
    fun visualDisclosureAllowed(frame: ControlFrame): Boolean = false
    suspend fun capture(): ControlFrame
    // Must recheck consent and action-scoped freshness/identity at the native action boundary.
    suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect
    suspend fun apps(filter: String): JsonArray
    fun appDiagnostics(): JsonObject = buildJsonObject {}
    fun invalidate() {}
    fun clearSelections() {}
}

/** One current, expiring, single-use observation. No planner or model names in the executor. */
internal class GenericControl(private val port: ControlPort, private val allowed: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis) {
    // Action IDs remain limited to disclosed nodes; postcondition uniqueness uses
    // the complete native scan, including nodes omitted by filter/output limits.
    private data class Observation(val id: String, val at: Long, val frame: ControlFrame, val epoch: Long,
        val verificationFrame: ControlFrame)
    private var current: Observation? = null
    private data class PendingTerminal(val proof: TerminalSubmission, val frame: ControlFrame, val node: ControlNode, val epoch: Long, val at: Long)
    private var pendingTerminal: PendingTerminal? = null
    private var chainStarted = 0L
    private var chainActions = 0
    private var noProgress = 0
    fun invalidate(clearSelections: Boolean = false) { pendingTerminal = null; VisualImageCache.remove(current?.frame?.visual?.id); current = null; port.invalidate(); if (clearSelections) port.clearSelections() }

    suspend fun observe(filter: String = "", includeImage: Boolean = false): OperationResult {
        VisualImageCache.remove(current?.frame?.visual?.id); current = null
        chainStarted = now(); chainActions = 0; noProgress = 0
        if (!allowed()) return failure("generic_control_disabled")
        val visualEpoch = port.session()?.epoch
        port.presentation(ControlSession.VisualPhase.OBSERVING, visualEpoch)
        var frame = port.capture()
        if (includeImage && frame.reason == null && !frame.truncated && !frame.navigationOnly) {
            val visual = port.visual(frame)
            frame = if (visual == null) frame.copy(reason = "visual_observation_unavailable") else frame.copy(visual = visual)
        }
        val result = publish(frame, filter)
        port.presentation(if (result.success) ControlSession.VisualPhase.WAITING else ControlSession.VisualPhase.FAILED, visualEpoch)
        port.feedbackAtEpoch(if (result.success) "접근성 확인 완료 · 다음 명령 대기" else "화면 확인 실패 · 재관찰 필요", visualEpoch)
        return result
    }
    private fun publish(frame: ControlFrame, filter: String = ""): OperationResult {
        if (!allowed()) { current = null; return failure("generic_control_disabled") }
        if (frame.reason != null) { current = null; return failure(frame.reason, diagnostics = frame.windowDiagnostics) }
        val rendered = mutableListOf<JsonElement>()
        var size = 0
        val candidates = frame.nodes.filter { filter.isEmpty() || it.text.contains(filter, true) ||
            it.description.contains(filter, true) || it.resourceId.contains(filter, true) }
        val exposed = mutableListOf<ControlNode>()
        for (node in candidates.take(frame.outputLimit)) {
            val json = node.json()
            if (size + json.toString().length > 24_000) break
            rendered.add(json); exposed.add(node); size += json.toString().length
        }
        val outputLimited = rendered.size < candidates.size
        // Full security/fingerprint traversal is independent of bounded presentation.
        // Only IDs actually disclosed in this observation may be acted on.
        val bounded = frame.copy(nodes = exposed)
        val observation = Observation(UUID.randomUUID().toString(), now(), bounded, port.session()?.epoch ?: 0L, frame)
        current = observation
        frame.visual?.let { proof ->
            val sessionId = port.session()?.id; val epoch = port.session()?.epoch
            val owner = java.lang.ref.WeakReference(this)
            VisualImageCache.put(proof) { owner.get()?.canReadVisual(sessionId, epoch) == true }
        }
        return OperationResult(data = buildJsonObject {
            frame.visual?.let { put("visual", it.metadata()) }
            put("observation_id", observation.id); put("observed_at_ms", observation.at); put("expires_in_ms", 60_000)
            frame.keyboard?.let { put("keyboard", buildJsonObject {
                put("window_id", it.windowId); put("package_name", it.packageName); put("input_method_id", it.inputMethodId)
                put("read_allowed", it.readAllowed); put("interaction_allowed", it.interactAllowed)
                put("semantic_keys_only", true); put("input_target_verified", frame.inputTargetId != null)
                frame.keyboardDiagnostics?.let { diagnostics -> put("scan", diagnostics) }
            }) }
            put("package_name", frame.packageName); put("window_id", frame.windowId)
            frame.verifiedApplicationPackage?.let { put("verified_application_package", it) }
            put("foreground_window_id", frame.foregroundWindowId)
            frame.windowDiagnostics?.let { put("window_diagnostics", it) }
            put("configuration_revision", frame.configurationRevision)
            put("security_scan_complete", !frame.truncated); put("output_truncated", outputLimited)
            put("matched_node_count", candidates.size)
            put("truncated", bounded.truncated); put("navigation_only", bounded.navigationOnly)
            put("actionable", !bounded.truncated && !bounded.navigationOnly)
            put("node_actions_allowed", !bounded.truncated && !bounded.navigationOnly)
            put("node_tap_available", !bounded.truncated && !bounded.navigationOnly && bounded.touchContext.isNotEmpty())
            put("global_navigation_validation_scope", "foreground_app_window")
            put("node_and_back_validation_scope", "full_screen_fingerprint")
            put("navigation_actions", JsonArray((if (bounded.truncated || bounded.navigationOnly) listOf("home", "open_app") else listOf("home", "back", "open_app")).map { JsonPrimitive(it) }))
            put("truncation_reasons", JsonArray(bounded.truncationReasons.sorted().map { JsonPrimitive(it) }))
            put("visited_nodes", bounded.visitedNodes); put("max_depth", bounded.maxDepth)
            put("content_trust", "untrusted_screen_data"); put("image_included", false)
            put("nodes", JsonArray(rendered))
        }, summary = "Current accessibility screen observed; node IDs belong only to this observation.")
    }
    suspend fun action(input: JsonObject): OperationResult {
        if (!allowed()) { current = null; return failure("generic_control_disabled") }
        val observation = current ?: return failure("observation_required")
        if (input["observation_id"]?.jsonPrimitive?.content != observation.id) return failure("stale_observation")
        // Route keyboard text through this SAME single-use action executor for every key.
        // The runner never invokes the platform directly or retries an unknown effect.
        if (input["action"]?.jsonPrimitive?.content == "input") {
            val field = observation.frame.nodes.singleOrNull { it.id == input["node_id"]?.jsonPrimitive?.intOrNull }
            if (field != null) {
                val plan = UnifiedInputPolicy.plan(field, input["text"]?.jsonPrimitive?.content.orEmpty(),
                    input["method"]?.jsonPrimitive?.content ?: "auto", input["mode"]?.jsonPrimitive?.content ?: "empty",
                    input["clipboard_consent"]?.jsonPrimitive?.booleanOrNull == true)
                if (plan.refusal == null) {
                    val keyboard = observation.frame.keyboard
                    return TypeExecutor().executeObservedInput(
                        UnifiedTextInputPolicy.Evidence(
                            fresh = now() - observation.at in 0L..60_000L && observation.epoch == (port.session()?.epoch ?: 0L),
                            allowed = allowed(), targetVerified = field.focused && field.editable && field.enabled,
                            directSupported = plan.method == "direct" && field.setText,
                            clipboardAllowed = plan.method == "clipboard" && input["clipboard_consent"]?.jsonPrimitive?.booleanOrNull == true,
                            pasteSupported = field.paste,
                            imeKeysVerified = plan.method == "keyboard" && keyboard?.readAllowed == true && keyboard.interactAllowed &&
                                observation.frame.inputTargetId == field.id,
                            sensitive = field.password,
                            // Production native boundary independently requires explicit ACTIVE+ID;
                            // ports without a session are pure test adapters, never Android executors.
                            sessionActive = port.session()?.let { it.explicit && it.state == ControlSession.State.ACTIVE &&
                                input["session_id"]?.jsonPrimitive?.content == it.id } ?: !port.validatesAtNativeBoundary
                        ), dispatch = { channel ->
                            if (channel == UnifiedTextInputPolicy.Channel.VISIBLE_IME) keyboardInput(input, observation, field, plan.resultText)
                            else actionNative(input, observation)
                        }, denied = { current = null; failure("input_evidence_unavailable") }
                    )
                }
            }
        }
        return actionNative(input, observation)
    }
    private suspend fun actionNative(input: JsonObject, observation: Observation): OperationResult {
        val started = now()
        val startedNs = System.nanoTime()
        // Consume before any native side effect, including failure, cancellation and lost transport.
        VisualImageCache.remove(observation.frame.visual?.id)
        current = null
        if (observation.epoch != (port.session()?.epoch ?: 0L)) return failure("control_session_changed_observe_again")
        if (now() - observation.at !in 0L..60_000L) return failure("observation_expired")
        if (chainActions >= 20 || now() - chainStarted !in 0L..180_000L || noProgress >= 3)
            return failure("control_chain_limit_observe_again")
        val action = input["action"]?.jsonPrimitive?.content.orEmpty()
        if (action !in setOf("click", "tap", "long_press", "type", "input", "terminal_input", "terminal_submit", "focus", "key", "scroll", "visual_tap", "visual_long_press", "visual_drag", "visual_scroll", "expand", "collapse", "home", "back", "open_app")) return failure("unsupported_action")
        val pending = pendingTerminal
        pendingTerminal = null // Submission authority is single-use, including failures.
        val candidate = observation.frame.nodes.singleOrNull { it.id == input["node_id"]?.jsonPrimitive?.intOrNull }
        val issued = pending?.takeIf { action == "terminal_submit" && candidate != null &&
            it.epoch == observation.epoch && now()-it.at in 0L..60_000L &&
            it.frame.packageName == observation.frame.packageName && it.frame.windowId == observation.frame.windowId &&
            it.node.resourceId == candidate.resourceId && it.node.className == candidate.className &&
            it.node.bounds == candidate.bounds && it.node.sourcePackage == candidate.sourcePackage &&
            it.node.sourceWindowId == candidate.sourceWindowId && it.proof.pending == candidate.terminal }?.proof
        val frame = observation.frame.copy(terminalSubmission = issued)
        if (!port.validatesAtNativeBoundary) {
            val fresh = port.capture()
            GenericControlActionPolicy.refusal(frame, fresh, action)?.let {
                return failure(it, GenericControlActionPolicy.validationScope(action), fresh.windowDiagnostics)
            }
        }
        if (action.startsWith("visual_")) {
            val proof = frame.visual ?: return failure("visual_observation_required")
            if (input["visual_id"]?.jsonPrimitive?.content != proof.id || input["visual_target_confirmed"]?.jsonPrimitive?.booleanOrNull != true ||
                now() - proof.at !in 0L..60_000L) return failure("visual_proof_invalid_or_expired")
        }
        val id = input["node_id"]?.jsonPrimitive?.intOrNull
        val node = frame.nodes.singleOrNull { it.id == id }
        if (action in setOf("click", "tap", "long_press", "type", "input", "terminal_input", "terminal_submit", "focus", "key", "scroll", "expand", "collapse")) {
            if (id == null) return failure("node_id_required")
            if (node == null) return failure("target_not_in_observation")
            if (!node.enabled || node.password) return failure("target_unavailable")
            if ((action in setOf("click", "tap") && !node.clickable) || (action == "long_press" && !node.longClickable)) return failure("target_not_clickable")
            if ((action == "expand" && !node.expandable) || (action == "collapse" && !node.collapsible)) return failure("native_action_unavailable")
            if (action == "scroll" && (!node.scrollable || input["direction"]?.jsonPrimitive?.content !in (node.scrollDirections + setOf("up", "down")))) return failure("target_not_scrollable")
            if (node.keyboardKey.isNotEmpty() && action != "key") return failure("keyboard_key_action_required")
            if (action == "key") {
                if (frame.keyboard == null || !frame.keyboard.readAllowed || !frame.keyboard.interactAllowed || frame.inputTargetId == null || node.sourceWindowId != frame.keyboard.windowId ||
                    node.sourcePackage != frame.keyboard.packageName) return failure("keyboard_target_unverified")
                val field = frame.inputTarget ?: return failure("keyboard_target_unverified")
                if (!field.editable || !field.enabled || !field.focused || field.password || field.id != frame.inputTargetId || field.sourcePackage != frame.packageName ||
                    field.sourceWindowId != frame.windowId || field.text.length > 2000 || InputPayloadPolicy.sensitive(field.text)) return failure("keyboard_target_unverified")
                val key = KeyboardKeyPolicy.identify(node.text, node.description, node.className, node.clickable, node.textEntryKey)
                    ?: return failure("keyboard_key_unrecognized")
                if (field.editable && key.kind in setOf(KeyboardKeyPolicy.Kind.CHARACTER, KeyboardKeyPolicy.Kind.SPACE, KeyboardKeyPolicy.Kind.BACKSPACE) &&
                    KeyboardKeyPolicy.expectedText(field, key) == null) return failure("keyboard_literal_unverified")
                if (key.kind == KeyboardKeyPolicy.Kind.SUBMIT && input["submit_authorized"]?.jsonPrimitive?.booleanOrNull != true)
                    return failure("submission_authorization_required")
            }
            if (action == "focus" && !node.focusable) return failure("target_not_focusable")
            if (action == "terminal_submit") {
                TerminalInputPolicy.submitRefusal(node, input["text"]?.jsonPrimitive?.content.orEmpty(),
                    input["terminal_authorized"]?.jsonPrimitive?.booleanOrNull == true,
                    input["submit_authorized"]?.jsonPrimitive?.booleanOrNull == true, frame.terminalSubmission)?.let { return failure(it) }
            }
            if (action == "terminal_input") {
                TerminalInputPolicy.refusal(node, input["text"]?.jsonPrimitive?.content.orEmpty(),
                    input["terminal_authorized"]?.jsonPrimitive?.booleanOrNull == true)?.let { return failure(it) }
            }
            if (action == "input") {
                val plan = UnifiedInputPolicy.plan(node, input["text"]?.jsonPrimitive?.content.orEmpty(),
                    input["method"]?.jsonPrimitive?.content ?: "auto", input["mode"]?.jsonPrimitive?.content ?: "empty",
                    input["clipboard_consent"]?.jsonPrimitive?.booleanOrNull == true)
                plan.refusal?.let { return failure(it) }
            }
            if (action == "type" && (!node.editable || !node.empty || input["text"]?.jsonPrimitive?.content.isNullOrBlank())) return failure("draft_not_empty_or_not_editable")
        }
        if (action == "open_app" && input["package_name"]?.jsonPrimitive?.content.isNullOrBlank()) return failure("package_required")
        if (!allowed()) return failure("generic_control_disabled")
        chainActions++
        val validatedAtNs = System.nanoTime()
        val visualEpoch = port.session()?.epoch
        port.presentation(ControlSession.VisualPhase.EXECUTING, visualEpoch)
        val effect = controlSpan("native_action") { port.perform(frame, input, node) }
        val performedAtNs = System.nanoTime()
        // After an attempted action even a negative native acknowledgement is not proof of no effect.
        val post = if (effect.attempted) controlSpan("post_observation") { port.captureAfter(frame, input).copy(visual = null) } else null
        val observed = if (post != null && allowed()) publish(post) else null
        val changed = post != null && post.reason == null &&
            (frame.fingerprint != post.fingerprint || frame.packageName != post.packageName || frame.windowId != post.windowId)
        noProgress = if (changed) 0 else noProgress + 1
        val verdict = controlSpan("result_validation") { ControlResultPolicy.verify(action, observation.verificationFrame, post, node, input) }
        val verified = effect.acknowledged && observed?.success == true && verdict.confirmed
        if (verified && action == "terminal_input" && node?.terminal != null && post != null) {
            val after = post.nodes.singleOrNull { it.resourceId == node.resourceId && it.className == node.className &&
                it.bounds == node.bounds && it.sourcePackage == node.sourcePackage && it.sourceWindowId == node.sourceWindowId }
            after?.terminal?.let { terminal ->
                TerminalInputPolicy.submission(node.terminal,terminal,input["text"]?.jsonPrimitive?.content.orEmpty())?.let { proof ->
                    pendingTerminal = PendingTerminal(proof,post,after,observation.epoch,now())
                }
            }
        }
        val resultReason = if (effect.attempted && effect.acknowledged && !verdict.confirmed) verdict.reason else effect.reason
        port.presentation(if (verified) ControlSession.VisualPhase.COMPLETED else ControlSession.VisualPhase.FAILED, visualEpoch)
        port.feedbackAtEpoch(if (verified) "화면 변화 확인 · 다음 단계 대기" else "결과 미확인 · 재관찰 필요", visualEpoch)
        val completedNs = System.nanoTime()
        fun durationMs(startNs: Long, endNs: Long) = ((endNs - startNs) / 1_000_000L).coerceAtLeast(0L)
        return OperationResult(success = verified, summary = if (verified)
            if (verdict.goalVerified) "Observed action postcondition confirmed." else
                "Screen transition observed; the wider task result remains unverified." else resultReason,
            data = buildJsonObject {
                if (action == "input" && node != null) put("input_method", UnifiedInputPolicy.plan(node,
                    input["text"]?.jsonPrimitive?.content.orEmpty(), input["method"]?.jsonPrimitive?.content ?: "auto",
                    input["mode"]?.jsonPrimitive?.content ?: "empty", input["clipboard_consent"]?.jsonPrimitive?.booleanOrNull == true).method)
                if (action == "input" && input["clipboard_consent"]?.jsonPrimitive?.booleanOrNull == true)
                    put("clipboard_policy", "explicit_replace_no_automatic_restore")
                put("observation_age_ms", (started - observation.at).coerceAtLeast(0))
                put("validation_scope", GenericControlActionPolicy.validationScope(action))
                put("reason_code", resultReason)
                put("action_attempted", effect.attempted); put("action_acknowledged", effect.acknowledged)
                put("outcome_unknown", effect.attempted && !verified); put("screen_changed", changed)
                put("app_result_verified", verified && verdict.goalVerified)
                put("feedback", when { !effect.attempted -> "not_attempted"; !verified -> "outcome_unconfirmed";
                    changed -> "screen_change_observed"; else -> "postcondition_confirmed" })
                put("validation_location", if (port.validatesAtNativeBoundary) "native_boundary" else "engine_and_native")
                put("timing_clock_domain", "monotonic_process")
                put("timing_ms", buildJsonObject {
                    put("validation", durationMs(startedNs, validatedAtNs))
                    put("native_action", durationMs(validatedAtNs, performedAtNs))
                    put("post_observation", durationMs(performedAtNs, completedNs))
                    put("total", durationMs(startedNs, completedNs))
                })
                observed?.let { put("observation", it.data) }
            })
    }
    private suspend fun keyboardInput(input: JsonObject, initial: Observation, field: ControlNode, expected: String): OperationResult {
        val text = input["text"]?.jsonPrimitive?.content.orEmpty()
        val labels = HangulKeyComposer.labels(text)
        fun stop(reason: String, attempted: Boolean, completed: Int) = OperationResult(success = false, summary = reason,
            data = buildJsonObject { put("reason_code", reason); put("action_attempted", attempted)
                put("outcome_unknown", attempted); put("completed_keys", completed); put("input_method", "keyboard") })
        // Bounded operation. Preflight every literal against the current observed layout before
        // the first effect. Unknown layouts are never guessed or toggled speculatively.
        if (labels.isEmpty() || labels.size > 20) { current = null; return stop("keyboard_input_limit", false, 0) }
        if (field.selectionStart != field.selectionEnd || field.selectionEnd != field.text.length) {
            current = null; return stop("keyboard_end_caret_required", false, 0)
        }
        fun matching(frame: ControlFrame, label: String) = frame.nodes.filter {
            it.keyboardKey == label && it.sourceWindowId == frame.keyboard?.windowId &&
                it.sourcePackage == frame.keyboard?.packageName
        }
        fun keyboardNodes(frame: ControlFrame) = frame.nodes.filter {
            it.sourceWindowId == frame.keyboard?.windowId && it.sourcePackage == frame.keyboard?.packageName
        }
        if (initial.frame.keyboard == null || labels.any { label ->
            val matches = matching(initial.frame, label)
            matches.size > 1 || (matches.isEmpty() && KeyboardKeyPolicy.layoutFor(label, keyboardNodes(initial.frame)) == null)
        }) {
            current = null; return stop("keyboard_keys_not_uniquely_observed", false, 0)
        }
        var completed = 0
        var attempted = false
        var layoutSteps = 0
        val pending = java.util.ArrayDeque(labels)
        val switched = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val label = pending.peekFirst()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val observed = current ?: return stop("keyboard_observation_lost", attempted, completed)
            val liveField = observed.frame.inputTarget
            if (observed.epoch != initial.epoch || observed.frame.packageName != initial.frame.packageName ||
                observed.frame.windowId != initial.frame.windowId || observed.frame.keyboard != initial.frame.keyboard ||
                observed.frame.touchContext != initial.frame.touchContext || observed.frame.configurationRevision != initial.frame.configurationRevision ||
                liveField == null || liveField.resourceId != field.resourceId || liveField.className != field.className ||
                liveField.sourceWindowId != field.sourceWindowId || liveField.sourcePackage != field.sourcePackage ||
                liveField.bounds != field.bounds || !liveField.focused) {
                current = null; return stop("keyboard_input_target_changed", attempted, completed)
            }
            val matches = matching(observed.frame, label)
            if (matches.size > 1) { current = null; return stop("keyboard_key_ambiguous", attempted, completed) }
            val literal = matches.singleOrNull()
            val key = literal ?: KeyboardKeyPolicy.layoutFor(label, keyboardNodes(observed.frame))
                ?: run { current = null; return stop("keyboard_layout_changed", attempted, completed) }
            if (literal == null && (layoutSteps >= 4 || !switched.add("$label:${observed.frame.fingerprint}:${key.keyboardKey}"))) {
                current = null; return stop("keyboard_layout_limit", attempted, completed)
            }
            val result = action(buildJsonObject {
                put("action", "key"); put("observation_id", observed.id); put("node_id", key.id)
                input["session_id"]?.let { put("session_id", it) }
            })
            attempted = attempted || result.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true
            if (!result.success) return stop(result.data["reason_code"]?.jsonPrimitive?.content ?: "keyboard_input_unconfirmed", attempted, completed)
            if (literal == null) layoutSteps++ else { completed++; pending.removeFirst() }
        }
        val actual = current?.frame?.inputTarget
        if (actual == null || actual.text != expected || actual.selectionStart != expected.length || actual.selectionEnd != expected.length)
            return stop("keyboard_final_text_unconfirmed", attempted, completed)
        return OperationResult(summary = "Exact keyboard text result verified.", data = buildJsonObject {
            put("input_method", "keyboard"); put("action_attempted", attempted); put("outcome_unknown", false)
            put("app_result_verified", true); put("completed_keys", completed); put("layout_steps", layoutSteps)
            current?.let { put("observation_id", it.id) }
        })
    }
    private fun canReadVisual(id: String?, epoch: Long?) = allowed() && port.session()?.id == id &&
        port.session()?.epoch == epoch && port.session()?.state == ControlSession.State.ACTIVE &&
        current?.frame?.let(port::visualDisclosureAllowed) == true
    private fun failure(reason: String, validationScope: String? = null, diagnostics: JsonObject? = null) = OperationResult(success = false, summary = reason,
        data = buildJsonObject { put("reason_code", reason); put("action_attempted", false); put("outcome_unknown", false); validationScope?.let { put("validation_scope", it) }
            diagnostics?.let { put("window_diagnostics", it) } })
}
