package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.*
import java.util.UUID
import kotlinx.serialization.json.*

internal data class ControlNode(val id: Int, val text: String = "", val description: String = "", val resourceId: String = "",
    val clickable: Boolean = false, val editable: Boolean = false, val scrollable: Boolean = false,
    val enabled: Boolean = true, val password: Boolean = false, val empty: Boolean = true,
    val bounds: String = "", val parent: Int = -1) {
    fun json() = buildJsonObject {
        put("node_id", id); put("text", text.take(100)); put("description", description.take(100)); put("resource_id", resourceId.take(100))
        put("bounds", bounds); put("parent", parent); put("clickable", clickable); put("editable", editable); put("scrollable", scrollable); put("enabled", enabled)
    }
}
internal data class ControlFrame(val packageName: String, val windowId: Int, val fingerprint: String,
    val nodes: List<ControlNode>, val reason: String? = null, val truncated: Boolean = false)
internal data class ControlEffect(val attempted: Boolean, val acknowledged: Boolean, val reason: String)
internal interface ControlPort {
    suspend fun capture(): ControlFrame
    // Must recheck consent, foreground fingerprint and node identity at the native action boundary.
    suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect
    suspend fun apps(filter: String): JsonArray
}

/** One current, expiring, single-use observation. No planner or model names in the executor. */
internal class GenericControl(private val port: ControlPort, private val allowed: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis) {
    private data class Observation(val id: String, val at: Long, val frame: ControlFrame)
    private var current: Observation? = null
    suspend fun observe(): OperationResult {
        current = null
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
        val bounded = frame.copy(truncated = frame.truncated || rendered.size < frame.nodes.size)
        val observation = Observation(UUID.randomUUID().toString(), now(), bounded)
        current = observation
        return OperationResult(data = buildJsonObject {
            put("observation_id", observation.id); put("observed_at_ms", observation.at); put("expires_in_ms", 60_000)
            put("package_name", frame.packageName); put("window_id", frame.windowId)
            put("truncated", bounded.truncated); put("actionable", !bounded.truncated)
            put("content_trust", "untrusted_screen_data"); put("image_included", false)
            put("nodes", JsonArray(rendered))
        }, summary = "Current accessibility screen observed; node IDs belong only to this observation.")
    }
    suspend fun action(input: JsonObject): OperationResult {
        if (!allowed()) { current = null; return failure("generic_control_disabled") }
        val observation = current ?: return failure("observation_required")
        if (input["observation_id"]?.jsonPrimitive?.content != observation.id) return failure("stale_observation")
        // Consume before any native side effect, including failure, cancellation and lost transport.
        current = null
        if (now() - observation.at !in 0L..60_000L) return failure("observation_expired")
        val frame = observation.frame
        if (frame.truncated) return failure("truncated_observation")
        val fresh = port.capture()
        if (fresh.reason != null) return failure(fresh.reason)
        if (fresh.truncated || frame.packageName != fresh.packageName || frame.windowId != fresh.windowId ||
            frame.fingerprint != fresh.fingerprint) return failure("screen_changed")
        val action = input["action"]?.jsonPrimitive?.content.orEmpty()
        if (action !in setOf("click", "long_press", "type", "scroll", "home", "back", "open_app")) return failure("unsupported_action")
        val id = input["node_id"]?.jsonPrimitive?.intOrNull
        val node = frame.nodes.singleOrNull { it.id == id }
        if (action in setOf("click", "long_press", "type", "scroll")) {
            if (node == null || !node.enabled || node.password) return failure("target_unavailable")
            if (action in setOf("click", "long_press") && !node.clickable) return failure("target_not_clickable")
            if (action == "scroll" && (!node.scrollable || input["direction"]?.jsonPrimitive?.content !in setOf("up", "down"))) return failure("target_not_scrollable")
            if (action == "type" && (!node.editable || !node.empty || input["text"]?.jsonPrimitive?.content.isNullOrBlank())) return failure("draft_not_empty_or_not_editable")
        }
        if (action == "open_app" && input["package_name"]?.jsonPrimitive?.content.isNullOrBlank()) return failure("package_required")
        if (!allowed()) return failure("generic_control_disabled")
        val effect = port.perform(frame, input, node)
        // After an attempted action even a negative native acknowledgement is not proof of no effect.
        val post = if (effect.attempted) port.capture() else null
        val observed = if (post != null && allowed()) publish(post) else null
        val changed = post != null && post.reason == null &&
            (frame.fingerprint != post.fingerprint || frame.packageName != post.packageName || frame.windowId != post.windowId)
        val inputVerified = action != "type" || post?.nodes?.any {
            it.editable && it.resourceId == node?.resourceId && it.text == input["text"]?.jsonPrimitive?.content
        } == true
        val verified = effect.acknowledged && observed?.success == true && inputVerified
        return OperationResult(success = verified, summary = if (verified)
            "Action acknowledged; inspect the returned observation to confirm the requested app result." else effect.reason,
            data = buildJsonObject {
                put("reason_code", if (!inputVerified) "input_unconfirmed" else effect.reason)
                put("action_attempted", effect.attempted); put("action_acknowledged", effect.acknowledged)
                put("outcome_unknown", effect.attempted && !verified); put("screen_changed", changed)
                put("app_result_verified", false)
                observed?.let { put("observation", it.data) }
            })
    }
    private fun failure(reason: String) = OperationResult(success = false, summary = reason,
        data = buildJsonObject { put("reason_code", reason); put("action_attempted", false); put("outcome_unknown", false) })
}
