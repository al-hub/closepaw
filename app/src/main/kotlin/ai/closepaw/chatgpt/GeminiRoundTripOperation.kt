package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Orchestrates a single submission. The dispatcher owns durable request identity and restart recovery. */
internal class GeminiRoundTripOperation(
    private val ui: GeminiRoundTripUi,
    private val allowed: () -> Boolean,
    private val record: (taskId: String, stage: String, reason: String) -> Unit,
    private val collectorFactory: (Long) -> GeminiReplyCollector = { timeout ->
        GeminiReplyCollector(ui, allowed, timeoutMs = timeout, maxPolls = 300)
    },
) : OperationHandler {
    private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()

    companion object {
        fun descriptor(readiness: () -> OperationReadiness = { OperationReadiness() }) = OperationDescriptor(
            id = "gemini.question.ask",
            description = "Ask one question in the current Gemini conversation and observe its new reply. Sends at most once per request ID, preserves existing drafts, requires an unlocked phone and separate local question-send permission. Use execute then get_task. A partial or unknown result must never be automatically resubmitted. Cancellation stops ClosePaw observation, not Gemini generation. New-conversation selection is unsupported.",
            inputSchema = buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject {
                    put("text", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 8000) })
                    put("timeout_ms", buildJsonObject { put("type", "integer"); put("minimum", 1000); put("maximum", 120000) })
                    put("conversation", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("current") }) })
                })
                put("required", buildJsonArray { add("text") })
            },
            readOnly = false,
            permissions = listOf("accessibility", "gemini_question_send"),
            effects = listOf("app_launch", "draft_input", "question_send"),
            cancellationSupported = true, readiness = readiness,
        )
    }

    /** False is deliberate: only our observer stops; Gemini's underlying generation is not cancelled. */
    override suspend fun cancel(taskId: String): Boolean {
        cancellations[taskId]?.set(true)
        return false
    }

    override suspend fun invoke(invocation: OperationInvocation): OperationResult {
        val cancelled = AtomicBoolean(false)
        if (cancellations.putIfAbsent(invocation.taskId, cancelled) != null) return OperationResult(
            success = false, summary = "This task is already being observed. Use get_task; do not resubmit.",
            data = buildJsonObject { put("reason_code", "task_already_running") },
        )
        var stage = "not_sent"
        var sent = false
        var sendActionInvoked = false
        var unknown = false
        var diagnosticsPersisted = true
        fun event(next: String, reason: String = "ok", required: Boolean = false): Boolean {
            return try {
                record(invocation.taskId, next, reason)
                stage = next
                true
            } catch (_: Exception) {
                diagnosticsPersisted = false
                if (!required) stage = next
                false
            }
        }
        fun finish(reason: String, answer: String? = null, completed: Boolean = false,
                   partial: Boolean = false, timedOut: Boolean = false, stopped: Boolean = false,
                   evidence: String? = null): OperationResult {
            val finalStage = when {
                unknown -> "outcome_unknown"
                stopped -> "interrupted"
                completed -> "completed"
                partial -> "partial"
                sent -> "waiting_failed"
                else -> "not_sent"
            }
            event(finalStage, reason)
            return OperationResult(success = completed, stdout = answer.orEmpty(), summary = when {
                completed -> "Collected the new Gemini reply."
                stopped && sent -> "ClosePaw stopped observing the submitted question. Gemini generation was not cancelled. Do not resend automatically."
                stopped -> "ClosePaw stopped before a confirmed submission. Inspect the draft before another request."
                unknown -> "Gemini submission outcome is unknown. Do not resend automatically; inspect Gemini or this task."
                partial -> "Collected a partial Gemini reply: $reason. Do not resend the question automatically."
                sent -> "Question submitted, but its new reply was not confirmed: $reason. Do not resend automatically."
                else -> "Question not sent: $reason. A prepared draft may remain."
            }, data = buildJsonObject {
                put("app", "gemini"); put("state", finalStage); put("reason_code", reason)
                put("submission_state", if(unknown) "outcome_unknown" else if(sent) "sent" else "not_sent")
                put("sent", sent); put("send_action_invoked", sendActionInvoked)
                put("outcome_unknown", unknown); put("partial", partial)
                put("timed_out", timedOut); put("observation_stopped", stopped)
                put("cancel_confirmed", false); put("generation_cancelled", false)
                put("diagnostics_persisted", diagnosticsPersisted)
                evidence?.let { put("completion_evidence", it) }
            })
        }
        try {
            if (OperationSchemaValidator.validate(descriptor().inputSchema, invocation.input) != null)
                return finish("invalid_input")
            val text = invocation.input.getValue("text").jsonPrimitive.content
            if (text.isBlank()) return finish("invalid_text")
            val timeout = invocation.input["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 60_000L
            if (!event("not_sent", required = true)) return finish("task_storage_failed")
            val submission = GeminiQuestionSubmitter(ui) { allowed() && !cancelled.get() }.submit(text) {
                // Persist this boundary BEFORE the UI primitive. A failed write prevents all sends.
                if (cancelled.get()) false else event("submitting", required = true)
            }
            when (submission) {
                is GeminiSubmissionResult.NotSent -> return finish(
                    if(cancelled.get()) "observer_cancelled" else submission.reason,
                    stopped = cancelled.get(),
                )
                is GeminiSubmissionResult.Unknown -> {
                    sendActionInvoked = true
                    unknown = true
                    return finish(submission.reason)
                }
                is GeminiSubmissionResult.Sent -> {
                    sendActionInvoked = true
                    if (!event("send_action_invoked", required = true)) {
                        unknown = true
                        return finish("task_storage_failed")
                    }
                    event("waiting")
                    val reply = collectorFactory(timeout).collect(text, submission.baseline) { cancelled.get() }
                    sent = reply.submissionObserved
                    unknown = !sent
                    if (sent) event("sent")
                    return finish(reply.reason, reply.answer,
                        completed = reply.state == "completed", partial = reply.partial,
                        timedOut = reply.reason.contains("timeout"), stopped = reply.state == "cancelled",
                        evidence = reply.completionEvidence,
                    )
                }
            }
        } catch (_: CancellationException) {
            // A coroutine interruption at the click boundary cannot prove a click did not happen.
            unknown = !sent && (sendActionInvoked || stage == "submitting")
            return finish("observer_cancelled", stopped = true)
        } catch (_: Exception) {
            unknown = !sent && (sendActionInvoked || stage == "submitting")
            return finish("execution_exception")
        } finally {
            cancellations.remove(invocation.taskId, cancelled)
        }
    }
}
