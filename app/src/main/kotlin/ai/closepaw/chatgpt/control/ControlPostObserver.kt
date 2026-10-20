package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Events are wakeups, never evidence of success. Every result is a freshly guarded capture. */
internal class ControlPostObserver(
    private val now: () -> Long,
    private val version: () -> Long,
    private val awaitChange: suspend (Long, Long) -> Boolean,
    private val capture: suspend () -> ControlFrame,
) {
    suspend fun observe(before: ControlFrame, input: JsonObject, since: Long): ControlFrame {
        val deadline = now() + 1600L
        var seen = since
        var post = capture()
        var reads = 1
        while (reads < 4 && now() < deadline) {
            val transient = post.reason in setOf("active_root_missing", "foreground_application_required",
                "secure_window_changed", "screen_changed", "owned_window_root_changed") ||
                (post.reason == null && post.truncated)
            val unchanged = post.reason == null && post.fingerprint == before.fingerprint &&
                post.packageName == before.packageName && post.windowId == before.windowId
            val launchPending = input["action"]?.jsonPrimitive?.content == "open_app" &&
                post.reason == null && !post.matchesApplication(input["package_name"]?.jsonPrimitive?.content)
            if (!transient && !unchanged && !launchPending) return post
            // Protected/locked/unavailable-policy states stop immediately, even if an event follows.
            if (post.reason != null && !transient) return post
            val remaining = (deadline - now()).coerceAtLeast(1)
            // A no-change acknowledgement must not add the entire transition deadline.
            val eventBudget = if (unchanged && !launchPending && !transient) minOf(remaining, 250L) else remaining
            val woke = awaitChange(seen, if (transient) minOf(eventBudget, 100L) else eventBudget)
            if (!woke && !(transient && reads == 1 && now() < deadline)) return post
            seen = version()
            post = capture(); reads++
        }
        return post
    }
}
