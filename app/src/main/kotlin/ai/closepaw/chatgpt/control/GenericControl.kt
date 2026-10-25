package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.*
import java.util.UUID
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
    val sourcePackage: String = "", val keyboardKey: String = "", val textEntryKey: Boolean = false) {
    fun json() = buildJsonObject {
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
    val verifiedApplicationPackage: String? = null, val keyboard: ControlKeyboard? = null, val inputTargetId: Int? = null, val inputTarget: ControlNode? = null)
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
    private data class Observation(val id: String, val at: Long, val frame: ControlFrame, val epoch: Long)
    private var current: Observation? = null
    private var chainStarted = 0L
    private var chainActions = 0
    private var noProgress = 0
    fun invalidate(clearSelections: Boolean = false) { current = null; port.invalidate(); if (clearSelections) port.clearSelections() }

    suspend fun observe(filter: String = ""): OperationResult {
        current = null
        chainStarted = now(); chainActions = 0; noProgress = 0
        if (!allowed()) return failure("generic_control_disabled")
        val visualEpoch = port.session()?.epoch
        port.presentation(ControlSession.VisualPhase.OBSERVING, visualEpoch)
        val result = publish(port.capture(), filter)
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
        val observation = Observation(UUID.randomUUID().toString(), now(), bounded, port.session()?.epoch ?: 0L)
        current = observation
        return OperationResult(data = buildJsonObject {
            put("observation_id", observation.id); put("observed_at_ms", observation.at); put("expires_in_ms", 60_000)
            frame.keyboard?.let { put("keyboard", buildJsonObject {
                put("window_id", it.windowId); put("package_name", it.packageName); put("input_method_id", it.inputMethodId)
                put("read_allowed", it.readAllowed); put("interaction_allowed", it.interactAllowed)
                put("semantic_keys_only", true); put("input_target_verified", frame.inputTargetId != null)
            }) }
            put("package_name", frame.packageName); put("window_id", frame.windowId)
            frame.verifiedApplicationPackage?.let { put("verified_application_package", it) }
            put("foreground_window_id", frame.foregroundWindowId)
            frame.windowDiagnostics?.let { put("window_diagnostics", it) }
            put("configuration_revision", frame.configurationRevision)
            put("security_scan_complete", !frame.truncated); put("output_truncated", outputLimited)
            put("matched_node_count", candidates.size)
            put("truncated", bounded.truncated); put("actionable", !bounded.truncated)
            put("node_actions_allowed", !bounded.truncated)
            put("node_tap_available", !bounded.truncated && bounded.touchContext.isNotEmpty())
            put("global_navigation_validation_scope", "foreground_app_window")
            put("node_and_back_validation_scope", "full_screen_fingerprint")
            put("navigation_actions", JsonArray((if (bounded.truncated) listOf("home", "open_app") else listOf("home", "back", "open_app")).map { JsonPrimitive(it) }))
            put("truncation_reasons", JsonArray(bounded.truncationReasons.sorted().map { JsonPrimitive(it) }))
            put("visited_nodes", bounded.visitedNodes); put("max_depth", bounded.maxDepth)
            put("content_trust", "untrusted_screen_data"); put("image_included", false)
            put("nodes", JsonArray(rendered))
        }, summary = "Current accessibility screen observed; node IDs belong only to this observation.")
    }
    suspend fun action(input: JsonObject): OperationResult {
        val started = now()
        val startedNs = System.nanoTime()
        if (!allowed()) { current = null; return failure("generic_control_disabled") }
        val observation = current ?: return failure("observation_required")
        if (input["observation_id"]?.jsonPrimitive?.content != observation.id) return failure("stale_observation")
        // Consume before any native side effect, including failure, cancellation and lost transport.
        current = null
        if (observation.epoch != (port.session()?.epoch ?: 0L)) return failure("control_session_changed_observe_again")
        if (now() - observation.at !in 0L..60_000L) return failure("observation_expired")
        if (chainActions >= 20 || now() - chainStarted !in 0L..180_000L || noProgress >= 3)
            return failure("control_chain_limit_observe_again")
        val action = input["action"]?.jsonPrimitive?.content.orEmpty()
        if (action !in setOf("click", "tap", "long_press", "type", "input", "focus", "key", "scroll", "expand", "collapse", "home", "back", "open_app")) return failure("unsupported_action")
        val frame = observation.frame
        if (!port.validatesAtNativeBoundary) {
            val fresh = port.capture()
            GenericControlActionPolicy.refusal(frame, fresh, action)?.let {
                return failure(it, GenericControlActionPolicy.validationScope(action), fresh.windowDiagnostics)
            }
        }
        val id = input["node_id"]?.jsonPrimitive?.intOrNull
        val node = frame.nodes.singleOrNull { it.id == id }
        if (action in setOf("click", "tap", "long_press", "type", "input", "focus", "key", "scroll", "expand", "collapse")) {
            if (node == null || !node.enabled || node.password) return failure("target_unavailable")
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
        val post = if (effect.attempted) controlSpan("post_observation") { port.captureAfter(frame, input) } else null
        val observed = if (post != null && allowed()) publish(post) else null
        val changed = post != null && post.reason == null &&
            (frame.fingerprint != post.fingerprint || frame.packageName != post.packageName || frame.windowId != post.windowId)
        noProgress = if (changed) 0 else noProgress + 1
        val verdict = controlSpan("result_validation") { ControlResultPolicy.verify(action, frame, post, node, input) }
        val verified = effect.acknowledged && observed?.success == true && verdict.confirmed
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
    private fun failure(reason: String, validationScope: String? = null, diagnostics: JsonObject? = null) = OperationResult(success = false, summary = reason,
        data = buildJsonObject { put("reason_code", reason); put("action_attempted", false); put("outcome_unknown", false); validationScope?.let { put("validation_scope", it) }
            diagnostics?.let { put("window_diagnostics", it) } })
}
