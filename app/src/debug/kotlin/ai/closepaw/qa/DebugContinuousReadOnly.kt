package ai.closepaw.qa

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Candidate-only bounded, read-only continuation after an ordinary QA step failure.
 * Never grants a session, never dispatches input and never relaxes native security
 * checks. Failure isolation is NOT permission to inspect a different or secure app.
 */
internal object DebugContinuousReadOnly {
    private val recoverable = setOf(
        "unique_chrome_menu_unavailable",
        "verified_new_tab_menu_unavailable",
        "unique_blank_chrome_omnibox_unavailable",
        "chrome_editor_focus_unconfirmed",
        "preflight_editor_missing",
        "preflight_keyboard_missing",
        "preflight_samsung_ime_missing",
        "preflight_keyboard_read_denied",
        "preflight_keyboard_interact_denied",
        "preflight_editor_not_focused",
        "preflight_editor_not_editable",
        "preflight_selection_not_origin",
    )
    fun mayInspect(reason: String, diagnosticOnly: Boolean, unknown: Boolean,
                   authorityLive: Boolean, chromeRoot: Boolean): Boolean =
        diagnosticOnly && !unknown && authorityLive && chromeRoot && reason in recoverable

    data class Finding(
        val stepId: String,
        val status: String,
        val reason: String,
        val details: JsonObject = buildJsonObject {},
    ) {
        fun redactedJson(): JsonObject = buildJsonObject {
            put("step_id", stepId)
            put("status", status)
            put("reason_code", reason)
            put("facts", details)
        }
    }
    data class Probe(val stepId: String, val inspect: suspend () -> Finding)

    /** Each probe is independent; an exception/timeout only fails that probe.
     * Revocation, user Stop and coroutine cancellation always terminate the loop. */
    suspend fun collect(
        probes: List<Probe>,
        authorityLive: () -> Boolean,
        onFinding: (Finding) -> Unit,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (probe in probes.take(6)) {
            currentCoroutineContext().ensureActive()
            if (!authorityLive()) {
                val revoked = Finding(probe.stepId, "PERMISSION_REVOKED", "authority_revoked")
                findings += revoked
                onFinding(revoked)
                break
            }
            val observed = try {
                withTimeoutOrNull(2_500L) { probe.inspect() }
                    ?: Finding(probe.stepId, "FAILED", "diagnostic_timeout")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                Finding(probe.stepId, "FAILED", "diagnostic_exception")
            }
            if (!authorityLive()) {
                val revoked = Finding(probe.stepId, "PERMISSION_REVOKED", "authority_revoked")
                findings += revoked
                onFinding(revoked)
                break
            }
            // The authored stepId is the only identifier persisted, not tool data.
            val safe = observed.copy(stepId = probe.stepId)
            findings += safe
            onFinding(safe)
            if (safe.status == "PERMISSION_REVOKED") break
        }
        return findings
    }
}
