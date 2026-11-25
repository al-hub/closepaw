package ai.closepaw.qa

import ai.closepaw.chatgpt.control.ControlSession
import java.util.UUID

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
)

internal enum class DebugQaConsent { EXISTING_OS_APPROVAL, NEW_OS_APPROVAL }
internal enum class DebugQaAdmissionOutcome { ACCEPTED, BUSY, DUPLICATE, DENIED }

internal data class DebugQaApprovedRun(
    val request: DebugQaRunRequest,
    val consent: DebugQaConsent,
    val projectionGeneration: Long,
)

/** Main-thread owner of local confirmation and one-shot service admission. Nothing is rehydrated from disk. */
internal class DebugQaRunAdmission {
    companion object {
        /** Operational admission guard (separate from native guards): do not admit local QA
         * confirmation or start while this app's control session is not IDLE/ENDED. */
        fun blocksForActiveControlSession(state: ControlSession.State?): Boolean =
            state != null && state !in setOf(ControlSession.State.IDLE, ControlSession.State.ENDED)
    }

    private val usedIds = LinkedHashSet<String>()
    var current: DebugQaRunRequest? = null
        private set
    private var waitingGeneration: Long? = null
    private var queued: DebugQaApprovedRun? = null
    var active: DebugQaApprovedRun? = null
        private set

    fun reserve(request: DebugQaRunRequest, captureBusy: Boolean): DebugQaAdmissionOutcome {
        if (request.id in usedIds || current?.id == request.id) return DebugQaAdmissionOutcome.DUPLICATE
        if (current != null || captureBusy) return DebugQaAdmissionOutcome.BUSY
        current = request
        return DebugQaAdmissionOutcome.ACCEPTED
    }

    fun waitForConsent(request: DebugQaRunRequest, generation: Long) {
        check(current == request && queued == null && active == null)
        waitingGeneration = generation
    }

    fun pendingConsent(id: String?, mode: String?, generation: Long): DebugQaRunRequest? =
        current?.takeIf { it.id == id && it.mode.code == mode && waitingGeneration == generation }

    fun queue(run: DebugQaApprovedRun): Boolean {
        if (current != run.request || queued != null || active != null) return false
        val expected = if (run.consent == DebugQaConsent.NEW_OS_APPROVAL) run.projectionGeneration else null
        if (waitingGeneration != expected) return false
        waitingGeneration = null
        queued = run
        return true
    }

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
