package ai.closepaw.qa

import ai.closepaw.chatgpt.control.AndroidControlPort
import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.chatgpt.control.GenericControl
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Independent read-only probes following a failed Chrome preparation step.
 * Kept outside the six-key coroutine to bound its JVM bytecode and isolate
 * postmortem work. Every probe is still gated by active OS/user authority.
 */
internal object DebugPostFailureProbes {
    private const val CHROME = "com.android.chrome"

    suspend fun collect(
        port: AndroidControlPort,
        control: GenericControl,
        session: ControlSession,
        projectionLive: () -> Boolean,
        accessibilityLive: () -> Boolean,
        allowed: () -> Boolean,
        inspectWindow: suspend () -> DebugOneStopQa.ChromeWindowFacts,
        onFinding: (DebugContinuousReadOnly.Finding) -> Unit,
    ): List<DebugContinuousReadOnly.Finding> {
                var followUpFrame: ControlFrame? = null
                val probes = listOf(
                    DebugContinuousReadOnly.Probe("projection_control") {
                        DebugContinuousReadOnly.Finding("projection_control", "PASS",
                            "approved_session_live", buildJsonObject {
                                put("projection_active", projectionLive())
                                put("session_active", session.state == ControlSession.State.ACTIVE)
                                put("accessibility_active", accessibilityLive())
                            })
                    },
                    DebugContinuousReadOnly.Probe("chrome_window") {
                        val window = inspectWindow()
                        DebugContinuousReadOnly.Finding("chrome_window",
                            if (window.chromeRoot && window.chromeOwned && window.applicationWindow)
                                "PASS" else "PERMISSION_REVOKED",
                            if (window.chromeRoot && window.chromeOwned && window.applicationWindow)
                                "chrome_window_observed" else "chrome_owner_unverified",
                            window.redactedJson())
                    },
                    DebugContinuousReadOnly.Probe("chrome_tree") {
                        val fresh = port.capture()
                        val refusal = fresh.reason
                        when {
                            refusal != null && (refusal.contains("secure") ||
                                refusal.contains("lock") || refusal.contains("permission")) ->
                                DebugContinuousReadOnly.Finding("chrome_tree", "PERMISSION_REVOKED",
                                    "native_security_refusal")
                            refusal != null ->
                                DebugContinuousReadOnly.Finding("chrome_tree", "FAILED",
                                    "native_capture_refused")
                            fresh.packageName != CHROME || fresh.navigationOnly ->
                                DebugContinuousReadOnly.Finding("chrome_tree", "PERMISSION_REVOKED",
                                    "chrome_owner_unverified")
                            else -> {
                                followUpFrame = fresh
                                DebugContinuousReadOnly.Finding("chrome_tree",
                                    if (fresh.truncated) "NOT_OBSERVABLE" else "PASS",
                                    if (fresh.truncated) "native_tree_incomplete"
                                    else "chrome_tree_observed",
                                    DebugOneStopQa.chromeSelectorFacts(fresh).redactedJson())
                            }
                        }
                    },
                    DebugContinuousReadOnly.Probe("samsung_ime") {
                        val frame = followUpFrame
                        when {
                            frame == null -> DebugContinuousReadOnly.Finding("samsung_ime",
                                "DEPENDENCY_BLOCKED", "chrome_tree_unavailable")
                            frame.keyboard == null -> DebugContinuousReadOnly.Finding("samsung_ime",
                                "NOT_OBSERVABLE", "keyboard_not_visible")
                            else -> DebugContinuousReadOnly.Finding("samsung_ime", "PASS",
                                "ime_structure_observed",
                                DebugOneStopQa.editorPreflightFacts(frame).redactedJson())
                        }
                    },
                    DebugContinuousReadOnly.Probe("keyboard_candidates") {
                        val frame = followUpFrame
                        when {
                            frame == null -> DebugContinuousReadOnly.Finding("keyboard_candidates",
                                "DEPENDENCY_BLOCKED", "chrome_tree_unavailable")
                            frame.truncated || frame.navigationOnly || frame.inputTarget?.password == true ->
                                DebugContinuousReadOnly.Finding("keyboard_candidates",
                                    "DEPENDENCY_BLOCKED", "unsafe_or_incomplete_frame")
                            frame.keyboard == null -> DebugContinuousReadOnly.Finding("keyboard_candidates",
                                "NOT_OBSERVABLE", "keyboard_not_visible")
                            frame.inputTarget?.text?.isNotEmpty() != false ->
                                DebugContinuousReadOnly.Finding("keyboard_candidates",
                                    "DEPENDENCY_BLOCKED", "empty_editor_not_verified")
                            else -> {
                                val observed = control.observe(includeImage = true,
                                    imageScope = "keyboard",
                                    keyboardSequenceLabels = listOf("ㄱ", "ㅏ", "ㄴ", "Space"))
                                // Keep the original parent refusal while the redacted facts
                                // retain the separately sanitized native reason_code.
                                val evidence = DebugKeyboardEvidence.fromObservation(observed.data,
                                    observed.success, listOf("ㄱ", "ㅏ", "ㄴ", "Space"))
                                DebugContinuousReadOnly.Finding("keyboard_candidates",
                                    if (observed.success) "PASS" else "FAILED",
                                    if (observed.success) "redacted_candidate_counts_observed"
                                    else "keyboard_observation_refused", evidence)
                            }
                        }
                    },
                )

        return DebugContinuousReadOnly.collect(probes, allowed, onFinding)
    }
}
