package ai.closepaw.qa

import ai.closepaw.chatgpt.control.ControlSession
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class DebugQaMode(val code: String, val label: String, val action: String) {
    STRUCTURE("structure_diagnostic", "Structure diagnosis · no key input", "ai.closepaw.qa.DIAG_USER_ARMED"),
    SIX_KEY("six_key_first_pass", "Guarded six-key first-pass", "ai.closepaw.qa.RUN_USER_ARMED");

    val diagnosticOnly get() = this == STRUCTURE
    val repetitions get() = if (diagnosticOnly) 1 else DebugQaBatchPolicy.REPETITIONS
}

internal data class DebugQaRunRequest(
    val mode: DebugQaMode,
    val exportGrant: String? = null,
    val id: String = UUID.randomUUID().toString(),
    val confirmedAtEpochMs: Long = System.currentTimeMillis(),
    val confirmedAtElapsedMs: Long? = null,
)

internal enum class DebugQaConsent { EXISTING_OS_APPROVAL, NEW_OS_APPROVAL }
internal enum class DebugQaAdmissionOutcome { ACCEPTED, BUSY, DUPLICATE, DENIED }

internal data class DebugQaApprovedRun(
    val request: DebugQaRunRequest,
    val consent: DebugQaConsent,
    val projectionGeneration: Long,
    val approvedAtEpochMs: Long = System.currentTimeMillis(),
    val approvedAtElapsedMs: Long? = null,
)

/**
 * Pure-JVM deterministic lifecycle, correlation, and atomic-export policy for
 * Debug QA batches — including pre-run denials, busy/duplicate rejections, and
 * process-restarted consent drops. Contains no raw screen content, URLs, or PII.
 */
internal object DebugQaLifecyclePolicy {
    const val MAX_BATCH_FILE_BYTES = 256_000L
    const val MAX_EXPORT_BYTES = 256_000
    private val UUID_REGEX = Regex("[a-f0-9-]{36}")
    private val SAFE_CODE_REGEX = Regex("[a-z0-9_]+")

    val EXPORTABLE_TERMINAL_STATES = setOf(
        "PASS", "BLOCKED", "DIAGNOSTIC_COMPLETE", "FAILED", "CANCELLED",
        "UNKNOWN", "DENIED", "BUSY", "DUPLICATE", "INTERRUPTED",
    )

    fun isExportableTerminalState(state: String?): Boolean = state in EXPORTABLE_TERMINAL_STATES

    fun safeCode(value: String?): String =
        value?.takeIf { it.length in 1..80 && it.matches(SAFE_CODE_REGEX) } ?: "unavailable"

    fun safeUuidOrFresh(value: String?, fresh: () -> String = { UUID.randomUUID().toString() }): String =
        value?.takeIf { it.matches(UUID_REGEX) } ?: fresh()

    fun buildPreRunBatchSnapshot(
        batchId: String,
        requestId: String?,
        sourceCommit: String,
        versionName: String,
        versionCode: Int,
        mode: DebugQaMode?,
        state: String,
        stage: String,
        reason: String,
        admissionOutcome: DebugQaAdmissionOutcome? = null,
        consent: DebugQaConsent? = null,
        candidateControlSessionState: ControlSession.State? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): JsonObject {
        val safeBatchId = safeUuidOrFresh(batchId)
        val safeRequestId = safeUuidOrFresh(requestId)
        val safeState = state.takeIf { it in EXPORTABLE_TERMINAL_STATES } ?: "FAILED"
        return buildJsonObject {
            put("schema_version", 3)
            put("batch_id", safeBatchId)
            put("source_commit", sourceCommit)
            put("version_name", versionName)
            put("version_code", versionCode)
            put("state", safeState)
            put("stage", safeCode(stage))
            put("reason", safeCode(reason))
            put("request_id", safeRequestId)
            put("mode", mode?.code ?: "unknown")
            put("projection_consent", consent?.name ?: "NONE")
            put("service_action", mode?.action ?: "none")
            put("service_admission", admissionOutcome?.name ?: "NOT_REACHED")
            put("candidate_control_session_state", candidateControlSessionState?.name ?: "unavailable")
            // Candidate in-process state cannot prove a separate Release package session ended.
            put("release_session_ended_externally_verified", false)
            put("pre_run_terminal", true)
            put("outcome_unknown", false)
            put("repetitions_requested", mode?.repetitions ?: 0)
            put("repetitions_completed", 0)
            put("runs", JsonArray(emptyList()))
            put("last_updated_epoch_ms", nowEpochMs)
        }
    }
}

/** Main-thread owner of local confirmation and one-shot service admission. Nothing is rehydrated from disk. */
internal class DebugQaRunAdmission {
    companion object {
        /** Operational admission guard (separate from native guards): do not admit local QA
         * confirmation or start while this app's control session is not IDLE/ENDED. */
        fun blocksForActiveControlSession(state: ControlSession.State?): Boolean =
            state != null && state !in setOf(ControlSession.State.IDLE, ControlSession.State.ENDED)
    }

    private val usedIds = LinkedHashSet<String>()
    @Volatile var current: DebugQaRunRequest? = null
        private set
    private var waitingGeneration: Long? = null
    private var queued: DebugQaApprovedRun? = null
    @Volatile var active: DebugQaApprovedRun? = null
        private set

    @Synchronized
    fun reserve(request: DebugQaRunRequest, captureBusy: Boolean): DebugQaAdmissionOutcome {
        if (request.id in usedIds || current?.id == request.id) return DebugQaAdmissionOutcome.DUPLICATE
        if (current != null || captureBusy) return DebugQaAdmissionOutcome.BUSY
        current = request
        return DebugQaAdmissionOutcome.ACCEPTED
    }

    @Synchronized
    fun waitForConsent(request: DebugQaRunRequest, generation: Long) {
        check(current == request && queued == null && active == null)
        waitingGeneration = generation
    }

    @Synchronized
    fun pendingConsent(id: String?, mode: String?, generation: Long): DebugQaRunRequest? =
        current?.takeIf { it.id == id && it.mode.code == mode && waitingGeneration == generation }

    @Synchronized
    fun queue(run: DebugQaApprovedRun): Boolean {
        if (current != run.request || queued != null || active != null) return false
        val expected = if (run.consent == DebugQaConsent.NEW_OS_APPROVAL) run.projectionGeneration else null
        if (waitingGeneration != expected) return false
        waitingGeneration = null
        queued = run
        return true
    }

    @Synchronized
    fun admit(id: String?, action: String?): Pair<DebugQaAdmissionOutcome, DebugQaApprovedRun?> {
        if (id != null && id in usedIds) return DebugQaAdmissionOutcome.DUPLICATE to null
        val run = queued
        if (run == null || id != run.request.id || action != run.request.mode.action)
            return (if (current != null && id != current?.id) DebugQaAdmissionOutcome.BUSY
                else DebugQaAdmissionOutcome.DENIED) to null
        queued = null
        active = run
        remember(run.request.id)
        return DebugQaAdmissionOutcome.ACCEPTED to run
    }

    @Synchronized
    fun finish(request: DebugQaRunRequest): Boolean {
        if (current != request) return false
        remember(request.id)
        current = null
        waitingGeneration = null
        queued = null
        active = null
        return true
    }

    private fun remember(id: String) {
        usedIds.add(id)
        if (usedIds.size > 64) usedIds.remove(usedIds.first())
    }
}
