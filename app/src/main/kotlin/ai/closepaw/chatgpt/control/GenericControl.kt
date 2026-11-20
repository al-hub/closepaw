package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.*
import ai.closepaw.tool.action.TypeExecutor
import ai.closepaw.tool.action.UnifiedTextInputPolicy
import java.util.UUID
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** A display proof can target the app or its separately authorized IME. Generic
 * gestures stay within certified app pixels; IME interaction uses visual_key. */
internal object VisualActionPolicy {
    fun scopeAllowed(action: String, scope: String): Boolean = when (scope) {
        "app" -> action != "visual_key"
        "keyboard" -> action == "visual_key"
        "display" -> true
        else -> false
    }
    private fun bounds(value: String): TapBounds? {
        val parts=value.split(' ')
        if (parts.size!=4) return null
        val n=parts.map { it.toIntOrNull() ?: return null }
        return TapBounds(n[0],n[1],n[2],n[3]).takeIf { it.valid }
    }
    fun target(proof: VisualTargetProof, action: String, imageRect: TapBounds): TapBounds? {
        val target=proof.map(imageRect) ?: return null
        if (proof.scope=="display" && action!="visual_key") {
            val app=bounds(proof.applicationBounds) ?: return null
            if (!app.contains(target)) return null
            if (proof.imeBounds.isNotEmpty()) {
                val ime=bounds(proof.imeBounds) ?: return null
                if (target.left<ime.right && target.right>ime.left && target.top<ime.bottom && target.bottom>ime.top) return null
            }
        }
        return target
    }
    fun pathAllowed(proof: VisualTargetProof, start: TapBounds, end: TapBounds?): Boolean {
        if (proof.scope!="display" || end==null) return true
        // A conservative rectangle enclosing the gesture must contain no masked
        // pixels. Native dispatch separately rechecks the live app region/path.
        return OwnedPixelPolicy.contains(proof.disclosedRegions,TapBounds(minOf(start.left,end.left),minOf(start.top,end.top),
            maxOf(start.right,end.right),maxOf(start.bottom,end.bottom)))
    }
}

internal data class ControlNode(val id: Int, val text: String = "", val description: String = "", val resourceId: String = "",
    val clickable: Boolean = false, val editable: Boolean = false, val scrollable: Boolean = false,
    val enabled: Boolean = true, val password: Boolean = false, val empty: Boolean = true,
    val bounds: String = "", val parent: Int = -1, val longClickable: Boolean = false,
    val expandable: Boolean = false, val collapsible: Boolean = false, val className: String = "", val selected: Boolean = false,
    val checkable: Boolean = false, val checked: Boolean = false,
    val focused: Boolean = false, val selectionStart: Int = -1, val selectionEnd: Int = -1,
    val setText: Boolean = false, val paste: Boolean = false, val focusable: Boolean = false,
    val scrollDirections: Set<String> = emptySet(), val sourceWindowId: Int = -1,
    val sourcePackage: String = "", val keyboardKey: String = "", val textEntryKey: Boolean = false, val visualKeyCandidate: Boolean = false, val terminal: TerminalEvidence? = null,
    val visualKeyChildCount: Int = -1, val keyboardCursorGeneration: Long? = null,
    val keyboardImageCandidate: Boolean = false, val webEditorContainer: Int = -1,
    val nativeEditorId: String? = null) {
    fun json() = buildJsonObject {
        put("terminal_input_supported", terminal != null)
        if (terminal != null) {
            // Binding availability and safe dispatch are different capabilities.
            // Termux does not publish its screen-selection state through this binding.
            put("terminal_selection_state", "unknown")
            put("terminal_input_allowed", false)
            put("terminal_input_reason", TerminalInputPolicy.selectionRefusal())
        }
        put("focused", focused); put("focusable", focusable); put("set_text_supported", setText); put("paste_supported", paste)
        put("selection_start", selectionStart); put("selection_end", selectionEnd)
        put("scroll_directions", JsonArray(scrollDirections.sorted().map { JsonPrimitive(it) }))
        put("expandable", expandable); put("collapsible", collapsible); put("class_name", className.take(100)); put("selected", selected)
        put("checkable", checkable); put("checked", checked)
        put("keyboard_image_candidate", keyboardImageCandidate)
        put("visual_key_candidate", visualKeyCandidate); put("source_window_id", sourceWindowId); put("keyboard_key", keyboardKey)
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
    val navigationOnly: Boolean = false,
    val keyboardCaptionSlots: List<KeyboardCaptionSlot> = emptyList(),
    val keyboardSuggestionGeometry: List<ImeGeometryNode> = emptyList(),
    val keyboardVisualFingerprintParts: KeyboardVisualFingerprintParts? = null)
/** Logical identity is supplied only by a complete, guarded native application identity scan. */
internal fun ControlFrame.matchesApplication(expected: String?): Boolean = expected != null && reason == null &&
    (packageName == expected || (!truncated && verifiedApplicationPackage == expected))

internal data class ControlEffect(val attempted: Boolean, val acknowledged: Boolean, val reason: String,
    val visualSourceFailure: VisualSourceFailure? = null)
/** The frame is the snapshot certified with these pixels, not an earlier proposal. */
internal data class CertifiedKeyboardFrame(val frame: ControlFrame, val proof: VisualTargetProof)

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
    suspend fun visualKeyboard(frame: ControlFrame): VisualTargetProof? = null
    suspend fun captureKeyboardSequenceFrame(): ControlFrame = capture()
    suspend fun visualKeyboardSequenceFollowUp(frame: ControlFrame,
        onPostCheckRefusal: (JsonObject) -> Unit): CertifiedKeyboardFrame? =
        visualKeyboard(frame)?.let { CertifiedKeyboardFrame(frame, it) }
    suspend fun visualDisplay(frame: ControlFrame): VisualTargetProof? = null
    /** Explicitly consented shared-screen reading; never a visual action proof. */
    suspend fun visualScreenObserve(frame: ControlFrame): VisualTargetProof? = null
    fun visualFailure(): String = "visual_observation_unavailable"
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
    private val visualKeyMaps = VisualKeyMapCache()
    private data class KeyboardCheckpoint(val session: String, val epoch: Long, val consent: Long,
        val frame: ControlFrame)
    private var keyboardCheckpoint: KeyboardCheckpoint? = null
    private fun checkpointFrame(frame: ControlFrame): ControlFrame? {
        val field = frame.inputTarget ?: return null
        val identityNodes = frame.nodes.filter {
            it.id == field.id || (it.sourcePackage == field.sourcePackage && it.sourceWindowId == field.sourceWindowId &&
                it.resourceId == field.resourceId && it.className == field.className)
        }
        return frame.copy(nodes = identityNodes, visual = null, keyboardVisualFingerprintParts = null)
    }
    private data class PendingTerminal(val proof: TerminalSubmission, val frame: ControlFrame, val node: ControlNode, val epoch: Long, val at: Long)
    private var pendingTerminal: PendingTerminal? = null
    private var chainStarted = 0L
    private var chainActions = 0
    private var noProgress = 0
    fun invalidate(clearSelections: Boolean = false) {
        pendingTerminal = null; keyboardCheckpoint = null; visualKeyMaps.clear()
        VisualImageCache.remove(current?.frame?.visual?.id); current = null; port.invalidate()
        if (clearSelections) port.clearSelections()
    }

    suspend fun observe(filter: String = "", includeImage: Boolean = false, imageScope: String = "app",
        keyboardKeyExact: String? = null, keyboardSequenceLabels: List<String>? = null,
        onKeyboardPostCheckRefusal: ((JsonObject) -> Unit)? = null): OperationResult {
        VisualImageCache.remove(current?.frame?.visual?.id); current = null
        chainStarted = now(); chainActions = 0; noProgress = 0
        if (!allowed()) return failure("generic_control_disabled")
        if (keyboardKeyExact != null && (filter.isNotEmpty() ||
                KeyboardKeyPolicy.identifyLabel(keyboardKeyExact, "")?.label != keyboardKeyExact))
            return failure("keyboard_key_exact_invalid")
        if (keyboardSequenceLabels != null && (filter.isNotEmpty() || keyboardKeyExact != null ||
                !includeImage || imageScope != "keyboard" || keyboardSequenceLabels.size !in 1..6 ||
                keyboardSequenceLabels.any { VisualSequenceCandidates.literal(it) == null }))
            return failure("keyboard_sequence_labels_invalid")
        if (imageScope !in setOf("app", "keyboard", "display", "screen_observe") || (imageScope != "app" && !includeImage)) return failure("image_scope_invalid")
        val visualEpoch = port.session()?.epoch
        port.presentation(ControlSession.VisualPhase.OBSERVING, visualEpoch)
        val followUpDiagnostic = onKeyboardPostCheckRefusal?.takeIf { includeImage && imageScope == "keyboard" }
        var frame = if (followUpDiagnostic == null) port.capture() else port.captureKeyboardSequenceFrame()
        if (includeImage && frame.reason == null && !frame.truncated && !frame.navigationOnly) {
            val certified = followUpDiagnostic?.let { port.visualKeyboardSequenceFollowUp(frame, it) }
            val visual = if (followUpDiagnostic != null) certified?.proof else when (imageScope) {
                "keyboard" -> port.visualKeyboard(frame)
                "display" -> port.visualDisplay(frame)
                "screen_observe" -> port.visualScreenObserve(frame)
                else -> port.visual(frame)
            }
            frame = when {
                visual == null -> frame.copy(reason = port.visualFailure())
                imageScope == "screen_observe" && visual.scope != "screen_observe" ->
                    frame.copy(reason = "screen_observation_proof_invalid")
                else -> (certified?.frame ?: frame).copy(visual = visual)
            }
        }
        val result = publish(frame, filter, keyboardKeyExact, keyboardSequenceLabels)
        port.presentation(if (result.success) ControlSession.VisualPhase.WAITING else ControlSession.VisualPhase.FAILED, visualEpoch)
        port.feedbackAtEpoch(if (result.success) "접근성 확인 완료 · 다음 명령 대기" else "화면 확인 실패 · 재관찰 필요", visualEpoch)
        return result
    }
    private fun publish(frame: ControlFrame, filter: String = "", keyboardKeyExact: String? = null,
        keyboardSequenceLabels: List<String>? = null): OperationResult {
        if (!allowed()) { current = null; return failure("generic_control_disabled") }
        if (frame.reason != null) { current = null; return failure(frame.reason, diagnostics = frame.windowDiagnostics) }
        val rendered = mutableListOf<JsonElement>()
        var size = 0
        val candidates = if (keyboardSequenceLabels != null) {
            VisualSequenceCandidates.select(frame, keyboardSequenceLabels)
        } else if (keyboardKeyExact != null) {
            val ime = frame.keyboard?.takeIf { it.readAllowed }
            frame.nodes.filter { node -> ime != null && node.sourceWindowId == ime.windowId &&
                node.sourcePackage == ime.packageName && node.keyboardKey == keyboardKeyExact }
        } else if (filter == "keyboard_image_candidate") {
            frame.nodes.filter { node -> node.keyboardImageCandidate &&
                frame.visual?.scope == "keyboard" && KeyboardImageFallbackPolicy.canPropose(frame, node) }
        } else if (filter == "keyboard_sequence_candidate") {
            val ime = frame.keyboard
            frame.nodes.filter { node -> ime != null && frame.visual?.scope == "keyboard" &&
                node.sourceWindowId == ime.windowId && node.sourcePackage == ime.packageName &&
                (node.visualKeyCandidate || node.keyboardImageCandidate && KeyboardImageFallbackPolicy.canPropose(frame, node)) }
        } else frame.nodes.filter { filter.isEmpty() || it.text.contains(filter, true) ||
            it.description.contains(filter, true) || it.resourceId.contains(filter, true) || it.keyboardKey.contains(filter, true) }
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
                put("interaction_reason", when {
                    !it.readAllowed -> "keyboard_read_not_authorized"
                    !it.interactAllowed -> "keyboard_interaction_not_authorized"
                    frame.inputTargetId == null -> "keyboard_input_target_unverified"
                    else -> "available"
                })
                put("selection_source", frame.inputTarget?.let { field -> when {
                    field.keyboardCursorGeneration != null -> "bound_empty_editor"
                    field.selectionStart in 0..field.text.length && field.selectionEnd in field.selectionStart..field.text.length -> "accessibility"
                    else -> "unverified"
                } } ?: "unverified")
                put("semantic_keys_only", false); put("visual_keys_require_keyboard_image", true); put("input_target_verified", frame.inputTargetId != null)
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
        // Only a positively verified visual layout change can carry a sequence
        // checkpoint across a separate action. An uncertain effect never carries it.
        val checkpoint = keyboardCheckpoint
        keyboardCheckpoint = null
        val isLayout = input["action"]?.jsonPrimitive?.content == "visual_key" &&
            observation.frame.nodes.singleOrNull { it.id == input["node_id"]?.jsonPrimitive?.intOrNull }?.let {
                VisualKeyboardPolicy.identifyCandidate(it, observation.frame.keyboard)?.kind ==
                    KeyboardKeyPolicy.Kind.LAYOUT
            } == true
        if (isLayout) {
            val result = actionNative(input, observation)
            val after = current?.verificationFrame
            if (checkpoint != null && result.success && after != null &&
                KeyboardEditorContinuity.sameFocusedEditor(checkpoint.frame, after, allowAnonymousResize = false) &&
                KeyboardEditorContinuity.sameValue(checkpoint.frame, after))
                keyboardCheckpoint = checkpointFrame(after)?.let { checkpoint.copy(frame = it) }
            return result
        }
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

    /**
     * A bounded, single-layout sequence from model-confirmed locations in one
     * *real* keyboard image. The opt-in path can reuse its proposal against a
     * newer guarded tree. The Android port still checks fresh native pixels and
     * the exact postcondition on every touch. Layout changes are separate
     * verified actions. No backspace, submit, coordinate guessing or replay.
     */
    suspend fun visualKeySequence(input: JsonObject, expectedEpoch: Long? = null): OperationResult {
        var confirmed = 0
        var imageFallbackSpaces = 0
        var touched = false
        val reuseMaps = input["reuse_key_map"]?.jsonPrimitive?.booleanOrNull == true
        var reusedKeySteps = 0
        var mapHits = 0
        var mapEntriesConfirmed = 0
        var mapsLearned = 0
        var renewals = 0
        var resolvedAcknowledgements = 0
        var usedFallback = false
        var observationRetryReason: String? = null
        fun JsonObjectBuilder.metrics() {
            if (!reuseMaps) return
            put("reused_key_steps", reusedKeySteps)
            put("key_map_hits", mapHits)
            put("confirmed_key_map_entries", mapEntriesConfirmed)
            put("new_layout_key_maps", mapsLearned)
            put("keyboard_image_renewals", renewals)
            put("fallback_used", usedFallback)
            put("resolved_acknowledgements", resolvedAcknowledgements)
        }
        val postCheckRefusals = mutableListOf<JsonObject>()
        fun stop(reason: String, unknown: Boolean = false, observationReason: String? = null,
            visualSourceFailure: JsonObject? = null): OperationResult {
            VisualImageCache.remove(current?.frame?.visual?.id)
            current = null
            if (unknown) keyboardCheckpoint = null
            return OperationResult(success = false, summary = reason, data = buildJsonObject {
                metrics()
                put("reason_code", reason); put("completed_keys", confirmed)
                observationReason?.let { put("observation_reason_code", it) }
                if (postCheckRefusals.isNotEmpty()) put("keyboard_post_check_refusals", JsonArray(postCheckRefusals))
                observationRetryReason?.let { put("observation_retry_reason_code", it) }
                visualSourceFailure?.let { put("visual_source_failure", it) }
                put("confirmed_image_fallback_spaces", imageFallbackSpaces)
                put("action_attempted", touched); put("outcome_unknown", unknown)
                put("input_method", "visual_keyboard_sequence")
            })
        }
        if (!allowed()) return stop("generic_control_disabled")
        val original = current ?: return stop("observation_required")
        if (input["observation_id"]?.jsonPrimitive?.content != original.id)
            return stop("stale_observation")
        val session = port.session()?.takeIf {
            it.explicit && it.state == ControlSession.State.ACTIVE &&
                it.id == input["session_id"]?.jsonPrimitive?.content && it.epoch == original.epoch &&
                (expectedEpoch == null || it.epoch == expectedEpoch)
        } ?: return stop("explicit_active_session_required")
        fun sessionCurrent() = allowed() && port.session() === session &&
            session.explicit && session.state == ControlSession.State.ACTIVE &&
            session.id == input["session_id"]?.jsonPrimitive?.content && session.epoch == original.epoch
        val firstProof = original.frame.visual ?: return stop("keyboard_visual_required")
        if (firstProof.scope != "keyboard" || firstProof.sourceDisplayId != 0 ||
            !firstProof.sourceDisclosureAllowed() ||
            input["visual_id"]?.jsonPrimitive?.content != firstProof.id ||
            input["visual_target_confirmed"]?.jsonPrimitive?.booleanOrNull != true ||
            now() - firstProof.at !in 0L..10_000L)
            return stop("keyboard_visual_proof_unavailable")
        val ime = original.frame.keyboard?.takeIf { it.readAllowed && it.interactAllowed }
            ?: return stop("keyboard_interaction_not_authorized")
        if (VisualKeyboardPolicy.proofOwnerRefusal(firstProof, ime) != null)
            return stop("keyboard_visual_owner_unverified")
        val editor = original.frame.inputTarget ?: return stop("keyboard_input_target_unverified")
        if (editor.id != original.frame.inputTargetId || !editor.focused ||
            !editor.editable || !editor.focusable || !editor.enabled || editor.password ||
            editor.sourcePackage != original.frame.packageName || editor.sourceWindowId != original.frame.windowId)
            return stop("keyboard_input_target_unverified")
        val prefix = input["expected_prefix"]?.jsonPrimitive?.contentOrNull
        val consent = ai.closepaw.security.ImageRevocationAuthority.currentEpoch
        if (prefix == null) {
            if (editor.text.isNotEmpty() || editor.selectionStart != 0 || editor.selectionEnd != 0)
                return stop("keyboard_empty_editor_required")
        } else {
            val checkpoint = keyboardCheckpoint
            if (!reuseMaps || prefix.isEmpty() || editor.text != prefix ||
                editor.selectionStart != prefix.length || editor.selectionEnd != prefix.length ||
                checkpoint == null || checkpoint.session != session.id || checkpoint.epoch != session.epoch ||
                checkpoint.consent != consent || !KeyboardEditorContinuity.sameFocusedEditor(
                    checkpoint.frame, original.verificationFrame, allowAnonymousResize = false) ||
                !KeyboardEditorContinuity.sameValue(checkpoint.frame, original.verificationFrame))
                return stop("keyboard_sequence_prefix_unverified")
        }
        val text = input["text"]?.jsonPrimitive?.contentOrNull ?: return stop("keyboard_text_required")
        val plan = UnifiedInputPolicy.plan(editor, text, "keyboard", if (prefix == null) "empty" else "insert", false)
        if (plan.refusal != null) return stop(plan.refusal)
        val labels = HangulKeyComposer.labels(text)
        val submitted = input["steps"] as? JsonArray
        if (!reuseMaps && submitted == null) return stop("keyboard_steps_required")
        if (labels.size !in (if (reuseMaps) 1 else 2)..6 || submitted != null && submitted.size != labels.size)
            return stop("keyboard_sequence_limit")
        val candidates = VisualSequenceCandidates.select(original.verificationFrame, labels)
        val byLabel = labels.distinct().zip(candidates).toMap()
        val witnesses = (input["layout_witness_labels"] as? JsonArray)?.map { it.jsonPrimitive.content }
        if (witnesses != null && (!reuseMaps || !VisualSequenceCandidates.witnessesLayout(
                original.verificationFrame, witnesses)))
            return stop("keyboard_sequence_layout_witness_mismatch")
        val imeBounds = KeyboardImageFallbackPolicy.bounds(firstProof.imeBounds)
            ?: return stop("keyboard_sequence_layout_unverified")
        fun layout(frame: ControlFrame) =
            KeyboardTouchLayout.fingerprint(VisualSequenceCandidates.layoutNodes(frame), imeBounds, ime)
        val initialLayout = layout(original.verificationFrame)
        if (initialLayout.isEmpty()) return stop("keyboard_sequence_layout_unverified")
        // Only unique native/resource identity survives across independent observations.
        val stableEditor = editor.nativeEditorId?.let { "native:$it" } ?: editor.resourceId.takeIf { id ->
            id.isNotEmpty() && original.verificationFrame.nodes.count {
                it.resourceId == id && it.className == editor.className &&
                    it.sourceWindowId == editor.sourceWindowId && it.sourcePackage == editor.sourcePackage
            } == 1
        }?.let { "resource:$it" }
        val owner = if (reuseMaps && stableEditor != null) VisualKeyMapCache.Owner(session.id, session.epoch,
            consent, original.frame.configurationRevision, original.frame.windowContext, ime, stableEditor, editor.className)
            else null
        val layoutId = VisualKeyMapCache.Layout(initialLayout, firstProof.window, firstProof.imeBounds,
            firstProof.rotation, firstProof.image.width, firstProof.image.height)
        data class Step(val label: String, val originalNode: ControlNode, val crop: TapBounds, val bounds: TapBounds)
        val planned = mutableListOf<Step>()
        for ((i, label) in labels.withIndex()) {
            if (VisualSequenceCandidates.literal(label) == null) return stop("keyboard_sequence_literal_only")
            val node: ControlNode
            val crop: TapBounds
            if (submitted == null) {
                val map = owner?.let { visualKeyMaps.find(it, layoutId, label) }
                    ?: return stop("keyboard_sequence_key_map_miss")
                node = byLabel[label] ?: return stop("keyboard_sequence_candidate_not_unique")
                if (KeyboardImageFallbackPolicy.bounds(node.bounds) != map.screenBounds ||
                    node.keyboardImageCandidate != map.imageOnly) return stop("keyboard_sequence_candidate_changed")
                crop = map.crop
                mapHits++
            } else {
                val entry = submitted[i] as? JsonObject ?: return stop("keyboard_sequence_step_invalid")
                if (entry["label"]?.jsonPrimitive?.content != label) return stop("keyboard_sequence_literal_only")
                val id = entry["node_id"]?.jsonPrimitive?.intOrNull ?: return stop("keyboard_sequence_step_invalid")
                node = original.frame.nodes.singleOrNull { it.id == id }
                    ?: return stop("keyboard_sequence_candidate_undisclosed")
                val raw = entry["image_rect"] as? JsonArray ?: return stop("keyboard_sequence_step_invalid")
                if (raw.size != 4) return stop("keyboard_sequence_step_invalid")
                val coords = raw.map { it.jsonPrimitive.intOrNull ?: return stop("keyboard_sequence_step_invalid") }
                crop = TapBounds(coords[0], coords[1], coords[2], coords[3])
            }
            if ((!node.visualKeyCandidate && !node.keyboardImageCandidate) ||
                node.sourceWindowId != ime.windowId || node.sourcePackage != ime.packageName)
                return stop("keyboard_sequence_candidate_unverified")
            if (node != byLabel[label]) return stop("keyboard_sequence_candidate_not_unique")
            val native = if (node.keyboardImageCandidate) KeyboardImageFallbackPolicy.key(label)
                else VisualKeyboardPolicy.identifyCandidate(node, ime)
            if (native?.label != label || native.kind !in
                setOf(KeyboardKeyPolicy.Kind.CHARACTER, KeyboardKeyPolicy.Kind.SPACE))
                return stop("keyboard_sequence_literal_unverified")
            VisualKeyboardPolicy.refusal(original.verificationFrame, node, "keyboard",
                label.takeIf { node.keyboardImageCandidate })?.let { return stop(it) }
            val inScreen = firstProof.map(crop) ?: return stop("keyboard_sequence_pixels_unverified")
            val nodeBounds = KeyboardImageFallbackPolicy.bounds(node.bounds)
                ?: return stop("keyboard_visual_bounds_invalid")
            if (!nodeBounds.contains(inScreen) || !imeBounds.contains(nodeBounds) ||
                !OwnedPixelPolicy.contains(firstProof.disclosedRegions, nodeBounds))
                return stop("keyboard_sequence_pixels_unverified")
            planned.add(Step(label, node, crop, nodeBounds))
        }
        if (submitted == null) {
            // Admit only the already verified candidates for this private request.
            // No new nodes/authority are exposed as reusable MCP observations.
            current = original.copy(frame = original.frame.copy(nodes = planned.map { it.originalNode }.distinct()))
        }
        var lastVerified = original.verificationFrame
        fun checkpoint(frame: ControlFrame) {
            if (reuseMaps) keyboardCheckpoint = checkpointFrame(frame)?.let {
                KeyboardCheckpoint(session.id, session.epoch, consent, it)
            }
        }
        fun cachedFollowUp(step: Step): Observation? {
            if (!port.validatesAtNativeBoundary || now() - firstProof.at !in 0L..10_000L ||
                !firstProof.sourceDisclosureAllowed()) return null
            val fresh = current?.verificationFrame?.copy(visual = firstProof) ?: return null
            if (!KeyboardEditorContinuity.sameFocusedEditor(lastVerified, fresh, allowAnonymousResize = false) ||
                !KeyboardEditorContinuity.sameValue(lastVerified, fresh) || fresh.touchContext != original.frame.touchContext ||
                layout(fresh) != initialLayout) return null
            val candidate = VisualSequenceCandidates.select(fresh, listOf(step.label)).singleOrNull() ?: return null
            return Observation(UUID.randomUUID().toString(), now(), fresh.copy(nodes = listOf(candidate)), session.epoch, fresh)
        }
        var retryUsed = false
        var refresh = false
        for ((index, step) in planned.withIndex()) {
            var followUpAttempt = 0
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!sessionCurrent()) return stop("control_session_changed_observe_again")
                val cached = if (refresh && reuseMaps && !usedFallback) cachedFollowUp(step) else null
                if (cached != null) current = cached
                if (refresh && cached == null) {
                    if (reuseMaps) usedFallback = true
                    renewals++
                    followUpAttempt++
                    val startedNs = System.nanoTime()
                    var postCheck: JsonObject? = null
                    val fresh = kotlinx.coroutines.withContext(KeyboardWindowTrace(index + 1, followUpAttempt, reuseMaps)) {
                        observe(includeImage = true, imageScope = "keyboard",
                            keyboardSequenceLabels = listOf(step.label),
                            onKeyboardPostCheckRefusal = { postCheck = it })
                    }
                    postCheck?.let { change ->
                        postCheckRefusals.add(buildJsonObject {
                            put("key_index", index + 1)
                            put("read_attempt", followUpAttempt)
                            put("read_elapsed_ms", ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L))
                            put("change", change)
                        })
                    }
                    if (!fresh.success) {
                        val reason = (fresh.data["reason_code"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,79}")) }
                        // observe is read-only. Retry only the known post-capture tree race
                        // and share the request budget with pre-touch dispatch refusals.
                        // The next iteration still verifies the editor, value/caret, key
                        // geometry and live pixels before it can dispatch the pending step.
                        if (!retryUsed && reason == "visual_post_screen_changed" && sessionCurrent()) {
                            retryUsed = true
                            observationRetryReason = reason
                            continue
                        }
                        return stop("keyboard_sequence_new_image_unavailable", observationReason = reason)
                    }
                }
                val active = current ?: return stop("keyboard_observation_lost")
                val proof = active.frame.visual ?: return stop("keyboard_visual_required")
                if (!sessionCurrent() || active.epoch != original.epoch)
                    return stop("control_session_changed_observe_again")
                if (active.frame.touchContext != original.frame.touchContext ||
                    !KeyboardEditorContinuity.sameFocusedEditor(lastVerified, active.verificationFrame,
                        allowAnonymousResize = true) ||
                    proof.scope != "keyboard" || proof.sourceDisplayId != firstProof.sourceDisplayId ||
                    proof.sourceImeWindowId != firstProof.sourceImeWindowId ||
                    proof.window != firstProof.window || proof.rotation != firstProof.rotation ||
                    proof.imeBounds != firstProof.imeBounds ||
                    proof.image.width != firstProof.image.width || proof.image.height != firstProof.image.height ||
                    now() - proof.at !in 0L..10_000L || !proof.sourceDisclosureAllowed() ||
                    VisualKeyboardPolicy.proofOwnerRefusal(proof, ime) != null ||
                    (cached == null && layout(active.verificationFrame) != initialLayout))
                    return stop("keyboard_sequence_layout_or_target_changed")
                if (!KeyboardEditorContinuity.sameValue(lastVerified, active.verificationFrame))
                    return stop("keyboard_input_unconfirmed", unknown = touched)
                val node = active.frame.nodes.singleOrNull { candidate ->
                    candidate.bounds == step.originalNode.bounds &&
                        candidate.className == step.originalNode.className &&
                        candidate.description == step.originalNode.description &&
                        candidate.keyboardKey == step.originalNode.keyboardKey &&
                        candidate.visualKeyChildCount == step.originalNode.visualKeyChildCount &&
                        candidate.visualKeyCandidate == step.originalNode.visualKeyCandidate &&
                        candidate.keyboardImageCandidate == step.originalNode.keyboardImageCandidate &&
                        candidate.sourceWindowId == ime.windowId && candidate.sourcePackage == ime.packageName
                } ?: return stop("keyboard_sequence_candidate_changed")
                if (proof.map(step.crop)?.let(step.bounds::contains) != true ||
                    !OwnedPixelPolicy.contains(proof.disclosedRegions, step.bounds))
                    return stop("keyboard_sequence_pixels_unverified")
                VisualKeyboardPolicy.refusal(active.verificationFrame, node, "keyboard",
                    step.label.takeIf { node.keyboardImageCandidate })?.let { return stop(it) }
                keyboardCheckpoint = null // Uncertain dispatch or cancellation must leave no resume authority.
                val actionInput = buildJsonObject {
                    put("session_id", session.id); put("observation_id", active.id)
                    put("action", "visual_key"); put("node_id", node.id)
                    put("visual_id", proof.id); put("visual_target_confirmed", true)
                    put("image_rect", JsonArray(listOf(step.crop.left, step.crop.top,
                        step.crop.right, step.crop.bottom).map { JsonPrimitive(it) }))
                    if (node.keyboardImageCandidate) put("image_key_label", step.label)
                }
                val result = kotlinx.coroutines.withContext(KeyboardWindowTrace(index + 1, followUpAttempt, reuseMaps)) {
                    actionNative(actionInput, active)
                }
                val attempted = result.data["action_attempted"]?.jsonPrimitive?.booleanOrNull
                val unknown = result.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull
                val reason = result.data["reason_code"]?.jsonPrimitive?.content ?: "keyboard_input_unconfirmed"
                touched = touched || attempted == true
                var verified = current?.verificationFrame
                if (!result.success || result.data["app_result_verified"]?.jsonPrimitive?.booleanOrNull != true) {
                    if (attempted == false && unknown == false) {
                        checkpoint(lastVerified)
                        if (cached != null && reason in setOf("screen_changed", "visual_pixels_or_geometry_changed",
                                "visual_revalidation_failed", VISUAL_SOURCE_REFUSED)) {
                            usedFallback = true
                            refresh = true
                            continue
                        }
                        if (!retryUsed && reason == "screen_changed") {
                            retryUsed = true
                            refresh = true
                            continue
                        }
                    }
                    // An exact ASCII echo can settle a lost acknowledgement, never
                    // a possibly composing Hangul deliverable or ambiguous target.
                    // Require two independent post-reads for the same pending key.
                    val asciiKey = step.label == "Space" || step.label.singleOrNull()?.let { it.code in 33..126 } == true
                    val asciiValue = lastVerified.inputTarget?.text?.all { it.code in 32..126 } == true
                    fun confirmsPending(read: ControlFrame) =
                        ControlResultPolicy.verify("visual_key", active.verificationFrame, read, node, actionInput)
                            .let { it.confirmed && it.goalVerified }
                    val firstRead = verified
                    verified = if (reuseMaps && attempted == true && unknown == true && asciiKey && asciiValue &&
                        sessionCurrent() && firstRead != null && confirmsPending(firstRead)) {
                        val independent = port.capture()
                        independent.takeIf {
                            KeyboardEditorContinuity.sameFocusedEditor(firstRead, it, allowAnonymousResize = false) &&
                                KeyboardEditorContinuity.sameValue(firstRead, it) &&
                                confirmsPending(it)
                        }
                    } else null
                    if (verified == null) return stop(reason, unknown = attempted != false || unknown != false,
                        visualSourceFailure = if (reason == VISUAL_SOURCE_REFUSED)
                            result.data["visual_source_failure"] as? JsonObject else null)
                    resolvedAcknowledgements++
                    usedFallback = true
                    publish(verified)
                }
                val confirmedFrame = verified
                    ?: return stop("keyboard_postcondition_unavailable", unknown = attempted == true)
                if (!sessionCurrent()) return stop("control_session_changed_observe_again", unknown = true)
                lastVerified = confirmedFrame
                checkpoint(confirmedFrame)
                confirmed++
                if (cached != null) reusedKeySteps++
                if (owner != null && submitted != null) {
                    if (visualKeyMaps.remember(owner, layoutId, step.label, VisualKeyMapCache.Entry(
                            step.bounds, step.crop, node.keyboardImageCandidate))) mapsLearned++
                    mapEntriesConfirmed++
                }
                if (step.label == "Space" && node.keyboardImageCandidate) imageFallbackSpaces++
                refresh = true
                break
            }
        }
        // Read only: a transient echo can be replaced by an autocomplete value
        // even after the last gesture. Never infer the buffer from its prefix.
        val finalFrame = port.capture()
        val result = finalFrame.inputTarget
        if (!sessionCurrent() || !KeyboardEditorContinuity.sameFocusedEditor(lastVerified, finalFrame,
                allowAnonymousResize = true) || !KeyboardEditorContinuity.sameValue(lastVerified, finalFrame) ||
            result == null || result.text != plan.resultText ||
            result.selectionStart != plan.resultText.length || result.selectionEnd != plan.resultText.length)
            return stop("keyboard_final_text_unconfirmed", unknown = touched)
        // The final read, not the earlier transient postcondition, owns the next observation.
        publish(finalFrame)
        checkpoint(finalFrame)
        return OperationResult(summary = "Exact bounded visual keyboard sequence confirmed.",
            data = buildJsonObject {
                metrics()
                put("input_method", "visual_keyboard_sequence")
                put("action_attempted", touched); put("outcome_unknown", false)
                put("app_result_verified", true); put("completed_keys", confirmed)
                observationRetryReason?.let { put("observation_retry_reason_code", it) }
                if (postCheckRefusals.isNotEmpty()) put("keyboard_post_check_refusals", JsonArray(postCheckRefusals))
                put("confirmed_image_fallback_spaces", imageFallbackSpaces)
                current?.let { put("observation_id", it.id) }
            })
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
        if (action !in setOf("click", "tap", "long_press", "type", "input", "terminal_input", "terminal_submit", "focus", "key", "scroll", "visual_tap", "visual_long_press", "visual_drag", "visual_scroll", "visual_key", "expand", "collapse", "home", "back", "open_app")) return failure("unsupported_action")
        val imageLabel = (input["image_key_label"] as? JsonPrimitive)?.contentOrNull
        if (imageLabel != null && action != "visual_key") return failure("keyboard_image_label_unexpected")
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
            if (action == "visual_key") {
                val trace = kotlinx.coroutines.currentCoroutineContext()[KeyboardWindowTrace]
                val mark = if (trace != null && proof.displayGeneration >= 0)
                    DisplayWindowChangeJournal.Mark(proof.displayGeneration, System.nanoTime(), trace.keyIndex, trace.readAttempt)
                else null
                proof.sourceFailure(VisualSourceStage.ACTION_VALIDATION)?.let {
                    return failure(VISUAL_SOURCE_REFUSED, visualSourceFailure = it)
                }
                trace?.mark = mark
            } else if (!proof.sourceDisclosureAllowed()) return failure(VISUAL_SOURCE_REFUSED)
            if (!VisualActionPolicy.scopeAllowed(action,proof.scope)) return failure("visual_scope_mismatch")
            if (input["visual_id"]?.jsonPrimitive?.content != proof.id || input["visual_target_confirmed"]?.jsonPrimitive?.booleanOrNull != true ||
                now() - proof.at !in 0L..60_000L) return failure("visual_proof_invalid_or_expired")
        }
        val id = input["node_id"]?.jsonPrimitive?.intOrNull
        val node = frame.nodes.singleOrNull { it.id == id }
        val visualSearch = action == "visual_key" && node?.let {
            VisualSearchPolicy.isSearch(VisualKeyboardPolicy.identifyCandidate(it, frame.keyboard))
        } == true
        if ("expected_search_query" in input && !visualSearch) return failure("keyboard_search_query_unexpected")
        if (action in setOf("click", "tap", "long_press", "type", "input", "terminal_input", "terminal_submit", "focus", "key", "visual_key", "scroll", "expand", "collapse")) {
            if (id == null) return failure("node_id_required")
            if (node == null) return failure("target_not_in_observation")
            if (!node.enabled || node.password) return failure("target_unavailable")
            if ((action in setOf("click", "tap") && !node.clickable) || (action == "long_press" && !node.longClickable)) return failure("target_not_clickable")
            if ((action == "expand" && !node.expandable) || (action == "collapse" && !node.collapsible)) return failure("native_action_unavailable")
            if (action == "scroll" && (!node.scrollable || input["direction"]?.jsonPrimitive?.content !in (node.scrollDirections + setOf("up", "down")))) return failure("target_not_scrollable")
            if ((node.keyboardKey.isNotEmpty() || node.keyboardImageCandidate) && action !in setOf("key", "visual_key"))
                return failure("keyboard_key_action_required")
            if (action == "visual_key") {
                VisualKeyboardPolicy.refusal(observation.verificationFrame, node, frame.visual?.scope.orEmpty(), imageLabel, input)
                    ?.let { return failure(it) }
            }
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
                    input["terminal_authorized"]?.jsonPrimitive?.booleanOrNull == true,
                    input["terminal_force_authorized"]?.jsonPrimitive?.booleanOrNull == true)?.let { return failure(it) }
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
        val searchSession = if (visualSearch) {
            val requestedSession = input["session_id"]?.jsonPrimitive?.content
                ?: return failure("explicit_active_session_required")
            val active = port.session()?.takeIf { it.explicit && it.state == ControlSession.State.ACTIVE &&
                requestedSession == it.id && it.epoch == observation.epoch }
                ?: return failure("explicit_active_session_required")
            if (!active.reserveVisualSearch(requestedSession, observation.epoch)) return failure("keyboard_search_replay_refused")
            active to requestedSession
        } else null
        val effect = controlSpan("native_action") { port.perform(frame, input, node) }
        searchSession?.let { (session, id) -> session.releaseVisualSearchIfNotAttempted(id, effect.attempted) }
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
                if (resultReason == VISUAL_SOURCE_REFUSED)
                    effect.visualSourceFailure?.let { put("visual_source_failure", it.json()) }
                if (action == "key" || action == "visual_key") put("keyboard_key_origin", when {
                    node?.keyboardImageCandidate == true -> "image_fallback"
                    action == "visual_key" -> "accessibility_label_with_image"
                    else -> "accessibility"
                })
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
            KeyboardKeyPolicy.identify(it.text,it.description,it.className,it.clickable,it.textEntryKey) != null && it.keyboardKey == label && it.sourceWindowId == frame.keyboard?.windowId &&
                it.sourcePackage == frame.keyboard?.packageName
        }
        fun keyboardNodes(frame: ControlFrame) = frame.nodes.filter {
            KeyboardKeyPolicy.identify(it.text,it.description,it.className,it.clickable,it.textEntryKey) != null &&
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
    private fun failure(reason: String, validationScope: String? = null, diagnostics: JsonObject? = null,
        visualSourceFailure: VisualSourceFailure? = null) = OperationResult(success = false, summary = reason,
        data = buildJsonObject { put("reason_code", reason); put("action_attempted", false); put("outcome_unknown", false); validationScope?.let { put("validation_scope", it) }
            diagnostics?.let { put("window_diagnostics", it) }; visualSourceFailure?.let { put("visual_source_failure", it.json()) } })
}
