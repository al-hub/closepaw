package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.*
import java.util.UUID
import kotlinx.serialization.json.*

internal data class ControlNode(val id: Int, val text: String = "", val description: String = "", val resourceId: String = "",
    val clickable: Boolean = false, val editable: Boolean = false, val scrollable: Boolean = false,
    val enabled: Boolean = true, val password: Boolean = false, val empty: Boolean = true,
    val bounds: String = "", val parent: Int = -1, val longClickable: Boolean = false,
    val expandable: Boolean = false, val collapsible: Boolean = false, val className: String = "", val selected: Boolean = false) {
    fun json() = buildJsonObject {
        put("expandable", expandable); put("collapsible", collapsible); put("class_name", className.take(100)); put("selected", selected)
        put("node_id", id); put("text", text.take(100)); put("description", description.take(100)); put("resource_id", resourceId.take(100))
        put("long_clickable", longClickable); put("bounds", bounds); put("parent", parent); put("clickable", clickable); put("editable", editable); put("scrollable", scrollable); put("enabled", enabled)
    }
}
internal data class ControlFrame(val packageName: String, val windowId: Int, val fingerprint: String,
    val nodes: List<ControlNode>, val reason: String? = null, val truncated: Boolean = false,
    val truncationReasons: Set<String> = emptySet(), val visitedNodes: Int = 0, val maxDepth: Int = 0,
    val touchContext: String = "")
internal data class ControlEffect(val attempted: Boolean, val acknowledged: Boolean, val reason: String)
internal interface ControlPort {
    suspend fun capture(): ControlFrame
    // Must recheck consent and action-scoped freshness/identity at the native action boundary.
    suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect
    suspend fun apps(filter: String): JsonArray
    fun invalidate() {}
}

/** One current, expiring, single-use observation. No planner or model names in the executor. */
internal class GenericControl(private val port: ControlPort, private val allowed: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis) {
    private data class Observation(val id: String, val at: Long, val frame: ControlFrame)
    private var current: Observation? = null
    private var chainStarted = 0L
    private var chainActions = 0
    private var noProgress = 0
    fun invalidate() { current = null; port.invalidate() }

    suspend fun observe(): OperationResult {
        current = null
        chainStarted = now(); chainActions = 0; noProgress = 0
        if (!allowed()) return failure("generic_control_disabled")
        return publish(port.capture())
    }
    private fun publish(frame: ControlFrame): OperationResult {
        if (!allowed()) { current = null; return failure("generic_control_disabled") }
        if (frame.reason != null) { current = null; return failure(frame.reason) }
        val rendered = mutableListOf<JsonElement>()
        var size = 0
        for (node in frame.nodes.take(50)) {
            val json = node.json()
            if (size + json.toString().length > 24_000) break
            rendered.add(json); size += json.toString().length
        }
        val outputLimited = rendered.size < frame.nodes.size
        val bounded = frame.copy(truncated = frame.truncated || outputLimited,
            truncationReasons = frame.truncationReasons + if (outputLimited) setOf("output_budget") else emptySet())
        val observation = Observation(UUID.randomUUID().toString(), now(), bounded)
        current = observation
        return OperationResult(data = buildJsonObject {
            put("observation_id", observation.id); put("observed_at_ms", observation.at); put("expires_in_ms", 60_000)
            put("package_name", frame.packageName); put("window_id", frame.windowId)
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
        if (!allowed()) { current = null; return failure("generic_control_disabled") }
        val observation = current ?: return failure("observation_required")
        if (input["observation_id"]?.jsonPrimitive?.content != observation.id) return failure("stale_observation")
        // Consume before any native side effect, including failure, cancellation and lost transport.
        current = null
        if (now() - observation.at !in 0L..60_000L) return failure("observation_expired")
        if (chainActions >= 20 || now() - chainStarted !in 0L..180_000L || noProgress >= 3)
            return failure("control_chain_limit_observe_again")
        val action = input["action"]?.jsonPrimitive?.content.orEmpty()
        if (action !in setOf("click", "tap", "long_press", "type", "scroll", "expand", "collapse", "home", "back", "open_app")) return failure("unsupported_action")
        val frame = observation.frame
        val fresh = port.capture()
        GenericControlActionPolicy.refusal(frame, fresh, action)?.let { return failure(it, GenericControlActionPolicy.validationScope(action)) }
        val id = input["node_id"]?.jsonPrimitive?.intOrNull
        val node = frame.nodes.singleOrNull { it.id == id }
        if (action in setOf("click", "tap", "long_press", "type", "scroll", "expand", "collapse")) {
            if (node == null || !node.enabled || node.password) return failure("target_unavailable")
            if ((action in setOf("click", "tap") && !node.clickable) || (action == "long_press" && !node.longClickable)) return failure("target_not_clickable")
            if ((action == "expand" && !node.expandable) || (action == "collapse" && !node.collapsible)) return failure("native_action_unavailable")
            if (action == "scroll" && (!node.scrollable || input["direction"]?.jsonPrimitive?.content !in setOf("up", "down"))) return failure("target_not_scrollable")
            if (action == "type" && (!node.editable || !node.empty || input["text"]?.jsonPrimitive?.content.isNullOrBlank())) return failure("draft_not_empty_or_not_editable")
        }
        if (action == "open_app" && input["package_name"]?.jsonPrimitive?.content.isNullOrBlank()) return failure("package_required")
        if (!allowed()) return failure("generic_control_disabled")
        chainActions++
        val validatedAt = now()
        val effect = port.perform(frame, input, node)
        val performedAt = now()
        // After an attempted action even a negative native acknowledgement is not proof of no effect.
        val post = if (effect.attempted) port.capture() else null
        val observed = if (post != null && allowed()) publish(post) else null
        val changed = post != null && post.reason == null &&
            (frame.fingerprint != post.fingerprint || frame.packageName != post.packageName || frame.windowId != post.windowId)
        noProgress = if (changed) 0 else noProgress + 1
        val inputVerified = action != "type" || post?.nodes?.singleOrNull {
            it.editable && it.resourceId == node?.resourceId && it.bounds == node?.bounds
        }?.text == input["text"]?.jsonPrimitive?.content
        val verified = effect.acknowledged && observed?.success == true && inputVerified
        return OperationResult(success = verified, summary = if (verified)
            "Action acknowledged; inspect the returned observation to confirm the requested app result." else effect.reason,
            data = buildJsonObject {
                put("validation_scope", GenericControlActionPolicy.validationScope(action))
                put("reason_code", if (!inputVerified) "input_unconfirmed" else effect.reason)
                put("action_attempted", effect.attempted); put("action_acknowledged", effect.acknowledged)
                put("outcome_unknown", effect.attempted && !verified); put("screen_changed", changed)
                put("app_result_verified", false)
                put("feedback", when { !effect.attempted -> "not_attempted"; !verified -> "outcome_unconfirmed";
                    changed -> "screen_change_observed"; else -> "acknowledged_no_visible_change" })
                put("timing_ms", buildJsonObject {
                    put("validation", (validatedAt - started).coerceAtLeast(0))
                    put("native_action", (performedAt - validatedAt).coerceAtLeast(0))
                    put("post_observation", (now() - performedAt).coerceAtLeast(0))
                    put("total", (now() - started).coerceAtLeast(0))
                })
                observed?.let { put("observation", it.data) }
            })
    }
    private fun failure(reason: String, validationScope: String? = null) = OperationResult(success = false, summary = reason,
        data = buildJsonObject { put("reason_code", reason); put("action_attempted", false); put("outcome_unknown", false); validationScope?.let { put("validation_scope", it) } })
}
