package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.OperationResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Test-only indicator lease. No app effects, no screen text, and no changes to normal-action timing. */
internal class ControlFeedbackTest(
    private val now: () -> Long,
    private val refusal: suspend () -> String?,
    private val activity: suspend (Boolean) -> Unit,
    private val attached: suspend () -> Boolean,
) {
    suspend fun run(durationMs: Long): OperationResult {
        val started = now()
        refusal()?.let { return OperationResult(success = false, summary = it,
            data = buildJsonObject { put("reason_code", it); put("action_attempted", false) }) }
        var stop = "duration_completed"
        try {
            activity(true)
            // addView can precede the first ViewRoot traversal. Bound this test-only attach wait.
            val attachDeadline = now() + 250
            while (!attached() && now() < attachDeadline && refusal() == null) delay(16)
            if (!attached()) stop = refusal() ?: "indicator_not_attached"
            else {
                val until = now() + durationMs.coerceIn(1, 5_000)
                while (now() < until) {
                    val reason = refusal()
                    if (reason != null) { stop = reason; break }
                    delay(minOf(100, until - now()).coerceAtLeast(1))
                }
                // Do not lose a lock/consent change at the final interval boundary.
                refusal()?.let { stop = it }
            }
        } finally {
            withContext(NonCancellable) { activity(false) }
        }
        val removed = !attached()
        return OperationResult(success = stop == "duration_completed" && removed,
            summary = "Indicator test ended: $stop; human touch and appearance require independent confirmation.",
            data = buildJsonObject {
                put("reason_code", if (!removed) "indicator_remove_unconfirmed" else stop)
                put("state", if (stop == "duration_completed" && removed) "completed" else "interrupted")
                put("elapsed_ms", now() - started); put("indicator_removed", removed)
                put("action_attempted", false); put("human_touch_verified", false)
            })
    }
}
