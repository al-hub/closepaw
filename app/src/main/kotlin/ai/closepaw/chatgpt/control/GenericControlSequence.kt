package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.OperationResult
import ai.closepaw.chatgpt.operations.OperationInterruptionEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/**
 * Bounded low-risk semantic click sequence. Every click consumes a *new* guarded
 * observation from GenericControl.action. No coordinates, fallback, retries,
 * blind replay, irreversible actions, or cross-app continuation.
 */
internal class GenericControlSequence(
    private val control: GenericControl,
    private val session: () -> ControlSession?,
    private val ready: () -> Boolean,
) {
    suspend fun run(labels: List<String>, sessionId: String?, correlationId: String = java.util.UUID.randomUUID().toString()): OperationResult {
        val trace = ControlTrace(correlationId)
        val timing = ControlTiming(android.os.SystemClock::elapsedRealtimeNanos)
        return try {
            kotlinx.coroutines.withContext(trace + timing) { runTraced(labels, sessionId, trace) }
        } catch (cancelled: CancellationException) {
            timing.mark(ControlTiming.Event.CANCEL_OBSERVED, step = trace.step)
            currentCoroutineContext()[OperationInterruptionEvidence]?.snapshot = buildJsonObject {
                put("correlation_id", correlationId)
                put("cancel_requested", true)
                put("terminal_reason", when {
                    cancelled is kotlinx.coroutines.TimeoutCancellationException -> "operation_timeout"
                    session()?.state == ControlSession.State.ENDED -> "control_session_ended"
                    session()?.state == ControlSession.State.PAUSED -> "user_control_paused"
                    else -> "execution_cancelled"
                })
                session()?.let { put("terminal_session_epoch", it.epoch) }
                put("completed_verified_steps", trace.completed)
                put("verification_scope", "guarded_click_and_post_observation; goal_not_verified")
                // The loop advances its step index before the cancellation/safety checks.
                // Only an entered guarded action removes that step from the unstarted suffix.
                if (trace.inFlight && trace.step > trace.completed) put("interrupted_step", trace.step)
                put("interrupted_step_action_started", trace.inFlight)
                val startedPrefix = if (trace.inFlight) maxOf(trace.step, trace.completed) else trace.completed
                put("pending_steps_not_started", (labels.size - startedPrefix).coerceAtLeast(0))
                put("external_effect_uncertain", trace.inFlight)
                put("outcome_unknown", trace.inFlight)
                put("goal_verified", false)
                put("trace_diagnostics_ref", correlationId)
            }
            throw cancelled
        } finally {
            timing.mark(ControlTiming.Event.INVOCATION_FINISHED, step = trace.step)
            ControlDiagnostics.record("sequence_trace", "finished", taskId = correlationId,
                metrics = buildJsonObject {
                    trace.snapshot().forEach { (key, value) -> put(key, value) }
                    put("android_timing", timing.snapshot())
                })
        }
    }
    private suspend fun runTraced(labels: List<String>, sessionId: String?, trace: ControlTrace): OperationResult {
        val startedNs = System.nanoTime()
        fun elapsedMs() = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)
        fun failure(reason: String, completed: Int, uncertain: Boolean = false) =
            OperationResult(success = false, summary = reason, data = buildJsonObject {
                put("reason_code", reason); put("completed_steps", completed)
                put("outcome_unknown", uncertain); put("goal_verified", false)
                put("elapsed_ms", elapsedMs()); put("clock_domain", "monotonic_process"); put("trace", trace.snapshot())
            })
        if (labels.size !in 2..9 || labels.any { it.length != 1 || it[0] !in '0'..'9' }) {
            return failure("invalid_sequence", 0)
        }
        val initialSession = session() ?: return failure("control_session_required", 0)
        if (!initialSession.explicit || initialSession.state != ControlSession.State.ACTIVE ||
            initialSession.refusal(sessionId, mutation = true) != null) {
            return failure("control_session_not_active", 0)
        }
        val initialEpoch = initialSession.epoch
        fun safe(): Boolean = ready() && session() === initialSession &&
            initialSession.epoch == initialEpoch &&
            initialSession.refusal(sessionId, mutation = true) == null
        if (!safe()) return failure("control_session_changed", 0)
        val first = controlSpan("initial_observation") { control.observe() }
        if (!first.success) return failure("initial_observation_failed", 0)
        var observed = first.data
        val expectedPackage = observed["package_name"]?.jsonPrimitive?.content
            ?: return failure("foreground_unverified", 0)
        // First version is intentionally limited to calculator digit entry, not
        // arbitrary UI clicks (including payments, dialogs, authentication or send).
        if (expectedPackage !in setOf(
                "com.sec.android.app.popupcalculator",
                "com.google.android.calculator",
                "com.android.calculator2"
            )) return failure("calculator_only", 0)
        val expectedWindow = observed["window_id"]?.jsonPrimitive?.intOrNull
            ?: return failure("foreground_unverified", 0)
        initialSession.sequenceProgress(labels.size, 0, "계산기 확인 완료", initialEpoch)
        var completed = 0
        for ((index, label) in labels.withIndex()) {
            trace.step = index + 1
            currentCoroutineContext().ensureActive()
            if (!safe()) return failure("control_session_changed", completed)
            if (observed["package_name"]?.jsonPrimitive?.content != expectedPackage ||
                observed["window_id"]?.jsonPrimitive?.intOrNull != expectedWindow) {
                return failure("foreground_changed", completed)
            }
            if (observed["node_actions_allowed"]?.jsonPrimitive?.booleanOrNull != true)
                return failure("observation_not_actionable", completed)
            val nodes = observed["nodes"] as? JsonArray ?: return failure("nodes_unavailable", completed)
            val matches = controlSpan("semantic_lookup") { nodes.mapNotNull { it as? JsonObject }.filter { node ->
                node["enabled"]?.jsonPrimitive?.booleanOrNull == true &&
                    node["clickable"]?.jsonPrimitive?.booleanOrNull == true &&
                    node["editable"]?.jsonPrimitive?.booleanOrNull != true &&
                    (node["text"]?.jsonPrimitive?.content == label ||
                        node["description"]?.jsonPrimitive?.content == label)
            }
            }
            if (matches.size != 1) return failure("target_not_unique", completed)
            val id = observed["observation_id"]?.jsonPrimitive?.content
                ?: return failure("observation_required", completed)
            val nodeId = matches.single()["node_id"]?.jsonPrimitive?.intOrNull
                ?: return failure("target_unavailable", completed)
            if (!safe()) return failure("control_session_changed", completed)
            initialSession.sequenceProgress(labels.size, completed, "터치 및 결과 확인", initialEpoch)
            trace.inFlight = true
            val result = controlSpan("guarded_action") { control.action(buildJsonObject {
                put("observation_id", id); put("action", "click"); put("node_id", nodeId)
            }) }
            trace.inFlight = result.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true
            if (!result.success) {
                // A native action may already have taken effect. Stop and never replay.
                val uncertain = result.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true
                return failure("step_unconfirmed", completed, uncertain)
            }
            completed++; trace.completed = completed
            initialSession.sequenceProgress(labels.size, completed, if (completed == labels.size) "입력 단계 확인 완료, 전체 목표 별도 확인 필요" else "다음 단계 준비", initialEpoch)
            if (!safe()) return failure("control_session_changed", completed)
            observed = result.data["observation"] as? JsonObject
                ?: return failure("post_observation_missing", completed)
        }
        return OperationResult(success = true,
            summary = "All guarded clicks acknowledged and observed; requested app result is not independently verified.",
            data = buildJsonObject {
                put("completed_steps", completed); put("outcome_unknown", false)
                put("goal_verified", false); put("observation", observed)
                put("elapsed_ms", elapsedMs()); put("clock_domain", "monotonic_process"); put("trace", trace.snapshot())
            })
    }
}
