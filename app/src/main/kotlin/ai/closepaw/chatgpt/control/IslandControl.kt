package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.OperationResult
import ai.closepaw.ui.overlay.IslandMoveVerifier
import ai.closepaw.ui.overlay.IslandPosition
import ai.closepaw.ui.overlay.IslandMoveVerdict
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Snapshot of the attached owned window, including display and input generation. */
internal data class IslandBounds(val x: Int, val y: Int, val width: Int, val height: Int,
    val displayWidth: Int, val displayHeight: Int, val identity: String = "") {
    val position get() = IslandPosition(x, y)
}
internal interface IslandControlPort {
    fun islandRefusal(sessionId: String): String?
    suspend fun observeIsland(): IslandBounds?
    suspend fun dragIsland(before: IslandBounds, dx: Int, dy: Int, holdMs: Long, moveMs: Long, sessionId: String): Boolean
}

/** Single-use observations and postconditions. No screen coordinates or automatic retries. */
internal class IslandControl(private val port: IslandControlPort, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Observation(val id: String, val session: String, val at: Long, val bounds: IslandBounds)
    private var observation: Observation? = null
    suspend fun observe(sessionId: String): OperationResult {
        observation = null
        port.islandRefusal(sessionId)?.let { return failure(it) }
        val bounds = port.observeIsland() ?: return failure("island_not_visible")
        port.islandRefusal(sessionId)?.let { return failure(it) }
        val value = Observation(UUID.randomUUID().toString(), sessionId, now(), bounds)
        observation = value
        return OperationResult(summary = "Owned island position observed.", data = buildJsonObject {
            put("observation_id", value.id); put("expires_in_ms", 60_000); put("bounds", json(bounds))
            put("input_path", "owned_window_motion_events")
        })
    }
    suspend fun drag(sessionId: String, observationId: String, dx: Int, dy: Int, holdMs: Long, moveMs: Long): OperationResult {
        val prior = observation
        observation = null // consume on every attempt, including validation failures and timeouts
        port.islandRefusal(sessionId)?.let { return failure(it) }
        if (prior == null || prior.id != observationId || prior.session != sessionId || now() - prior.at !in 0L..60_000L)
            return failure("island_observation_required")
        val b = prior.bounds
        val x = b.x.toLong() + dx; val y = b.y.toLong() + dy
        if (holdMs !in 450L..2000L || moveMs !in 100L..4000L || holdMs + moveMs > 4500L ||
            (kotlin.math.abs(dx.toLong()) < 8 && kotlin.math.abs(dy.toLong()) < 8) ||
            x < 0 || y < 0 || x + b.width > b.displayWidth || y + b.height > b.displayHeight)
            return failure("invalid_island_drag")
        if (port.observeIsland() != b) return failure("island_geometry_changed")
        port.islandRefusal(sessionId)?.let { return failure(it) }
        var acknowledged = false
        val completed = withTimeoutOrNull(5_000) {
            acknowledged = port.dragIsland(b, dx, dy, holdMs, moveMs, sessionId)
            true
        } ?: false
        val after = port.observeIsland()
        val refusal = port.islandRefusal(sessionId)
        val sameWindow = after != null && after.identity == b.identity && after.width == b.width &&
            after.height == b.height && after.displayWidth == b.displayWidth && after.displayHeight == b.displayHeight
        val verdict = IslandMoveVerifier.verify(b.position, after?.takeIf { sameWindow }?.position, completed && acknowledged && refusal == null)
        val moved = verdict == IslandMoveVerdict.MOVED && after?.x == x.toInt() && after.y == y.toInt()
        return OperationResult(success = moved, summary = if (moved) "island_moved" else refusal ?: "island_move_unconfirmed",
            data = buildJsonObject {
                put("action_attempted", true); put("outcome_unknown", !completed || !acknowledged || !sameWindow || refusal != null)
                put("timed_out", !completed); put("before", json(b)); after?.let { put("after", json(it)) }
                put("movement_verified", moved); put("input_path", "owned_window_motion_events"); put("automatic_replay_allowed", false)
            })
    }
    private fun failure(reason: String) = OperationResult(success = false, summary = reason,
        data = buildJsonObject { put("reason_code", reason); put("action_attempted", false); put("automatic_replay_allowed", false) })
    private fun json(b: IslandBounds) = buildJsonObject {
        put("x", b.x); put("y", b.y); put("width", b.width); put("height", b.height)
        put("display_width", b.displayWidth); put("display_height", b.displayHeight)
    }
}
