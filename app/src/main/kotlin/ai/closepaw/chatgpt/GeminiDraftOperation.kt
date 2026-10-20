package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal data class DraftComposerState(val foreground: Boolean, val candidates: Int, val text: String)
internal enum class DraftWriteResult { WRITTEN, EXISTING_DRAFT, TARGET_CHANGED, REJECTED }

/** Deliberately has no click, IME action, key event, clipboard or send primitive. */
internal interface GeminiDraftUi {
    suspend fun isLocked(): Boolean
    suspend fun launch(): Boolean
    suspend fun inspect(): DraftComposerState
    suspend fun setIfEmpty(text: String): DraftWriteResult
    suspend fun matches(text: String): Boolean
    fun evidence(): DraftWindowEvidence = DraftWindowEvidence()
    val launchFailureReason: String get() = "target_not_foreground"
}
internal data class DraftWindowEvidence(
    val observedPackage: String? = null,
    val rootPackage: String? = null,
    val windowId: Int? = null,
    val windowType: Int? = null,
    val active: Boolean? = null,
    val focused: Boolean? = null,
    val reason: String = "not_observed",
) {
    fun toJson() = buildJsonObject {
        observedPackage?.let { put("observed_package", it.take(160)) }
        rootPackage?.let { put("root_package", it.take(160)) }
        windowId?.let { put("window_id", it) }; windowType?.let { put("window_type", it) }
        active?.let { put("active", it) }; focused?.let { put("focused", it) }
        put("window_reason", reason)
    }
}
internal data class DraftDiagnostic(val taskId: String, val stage: String, val reason: String, val elapsedMs: Long, val window: DraftWindowEvidence = DraftWindowEvidence()) {
    fun toJson() = buildJsonObject {
        put("task_id", taskId); put("stage", stage); put("reason_code", reason); put("elapsed_ms", elapsedMs); put("window", window.toJson())
    }
}

/** Only metadata enters results/logs; requested text is held transiently by the invocation. */
internal class GeminiDraftOperation(
    private val ui: GeminiDraftUi,
    private val allowed: () -> Boolean,
    private val record: (DraftDiagnostic) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : OperationHandler {
    companion object {
        fun descriptor(readiness: () -> OperationReadiness = { OperationReadiness() }) = OperationDescriptor(
            id = "gemini.draft.insert",
            description = "Insert text into one empty native Gemini composer and verify it. Never sends or overwrites an existing draft. Requires an unlocked, interactive phone and local Gemini draft permission. Failure after a write may have changed the draft: inspect before retrying. Diagnostics are returned by get_task and get_diagnostics.",
            inputSchema = buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject {
                    put("session_id", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 128) })
                    put("text", buildJsonObject {
                    put("type", "string"); put("minLength", 1); put("maxLength", 8000)
                }) })
                put("required", buildJsonArray { add(JsonPrimitive("text")) })
            },
            readOnly = false, permissions = listOf("accessibility", "gemini_draft_input"),
            effects = listOf("app_launch", "draft_input"), readiness = readiness,
        )
    }
    override suspend fun invoke(invocation: OperationInvocation): OperationResult {
        val start = clock()
        val events = mutableListOf<DraftDiagnostic>()
        var writeAttempted = false
        var diagnosticsPersisted = true
        fun event(stage: String, reason: String = "ok") {
            val diagnostic = DraftDiagnostic(invocation.taskId, stage, reason, (clock() - start).coerceAtLeast(0), ui.evidence())
            events.add(diagnostic)
            // Logging failure never repeats or changes the UI action.
            runCatching { record(diagnostic) }.onFailure { diagnosticsPersisted = false }
        }
        fun finish(reason: String, success: Boolean = false, unknown: Boolean = false): OperationResult {
            event("complete", reason)
            return OperationResult(success = success, summary = when {
                success -> "Gemini draft inserted and verified in the composer. No send action was invoked."
                unknown -> "Draft may have changed but could not be verified. Do not retry automatically; inspect Gemini."
                else -> "Gemini draft not inserted: $reason."
            }, data = buildJsonObject {
                put("app", "gemini"); put("reason_code", reason); put("verified", success)
                put("sent", false); put("send_action_invoked", false); put("outcome_unknown", unknown)
                put("diagnostics", JsonArray(events.map { it.toJson() })); put("diagnostics_persisted", diagnosticsPersisted)
            })
        }
        event("start")
        try {
            val text = invocation.input["text"]?.jsonPrimitive?.content.orEmpty()
            if (text.isBlank() || text.length > 8000) return finish("invalid_text")
            if (!allowed()) return finish("gemini_draft_disabled")
            if (ui.isLocked()) return finish("device_locked")
            event("launch")
            if (!ui.launch()) return finish(ui.launchFailureReason)
            event("find_composer")
            val composer = ui.inspect()
            if (!composer.foreground) return finish("target_not_foreground")
            if (composer.candidates != 1) return finish(if (composer.candidates == 0) "composer_not_found" else "composer_ambiguous")
            if (composer.text.isNotBlank()) return finish("existing_draft")
            // Recheck consent/lock after launch and immediately before the guarded write.
            if (!allowed()) return finish("gemini_draft_disabled")
            if (ui.isLocked()) return finish("device_locked")
            event("set_text")
            writeAttempted = true
            when (ui.setIfEmpty(text)) {
                DraftWriteResult.EXISTING_DRAFT -> return finish("existing_draft")
                DraftWriteResult.TARGET_CHANGED -> return finish("target_changed")
                DraftWriteResult.REJECTED -> return finish("set_text_unconfirmed", unknown = true)
                DraftWriteResult.WRITTEN -> Unit
            }
            event("verify")
            if (!ui.matches(text)) return finish("verification_failed", unknown = true)
            return finish("draft_verified", success = true)
        } catch (cancel: CancellationException) {
            event("interrupted", "observer_cancelled")
            throw cancel
        } catch (_: Exception) {
            return finish("execution_exception", unknown = writeAttempted)
        }
    }
}
