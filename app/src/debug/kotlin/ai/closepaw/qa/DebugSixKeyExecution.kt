package ai.closepaw.qa

import ai.closepaw.chatgpt.control.AndroidControlPort
import ai.closepaw.chatgpt.control.AutonomousCertifiedKeyStep
import ai.closepaw.chatgpt.control.AutonomousKeyboardSequenceProof
import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.chatgpt.control.DisplayObservationRecoveryPolicy
import ai.closepaw.chatgpt.control.DisplayWindowCensus
import ai.closepaw.chatgpt.control.GenericControl
import ai.closepaw.chatgpt.control.KeyboardContextMismatch
import ai.closepaw.chatgpt.control.KeyboardEditorContinuity
import ai.closepaw.chatgpt.control.KoreanLayoutSwitchCandidate
import ai.closepaw.chatgpt.control.KoreanLayoutSwitchRoute
import ai.closepaw.chatgpt.control.NativeKeyboardStructuralCertification
import ai.closepaw.chatgpt.control.PendingKeyStepState
import ai.closepaw.chatgpt.control.PhysicalKeyboardLayout
import ai.closepaw.chatgpt.control.PixelCertificationOutcome
import ai.closepaw.chatgpt.control.RecoveryDecision
import ai.closepaw.chatgpt.control.SharedQaRecoveryBudget
import ai.closepaw.chatgpt.control.TapBounds
import ai.closepaw.chatgpt.control.VISUAL_SOURCE_REFUSED
import ai.closepaw.chatgpt.control.VisualKeyboardPolicy
import ai.closepaw.chatgpt.control.VisualSourceCondition
import ai.closepaw.chatgpt.control.VisualSourceFailure
import ai.closepaw.chatgpt.control.VisualSourceStage
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * #147 one-shot physical key state machine. Reuses the production
 * VisualSequenceCandidates observation, explicit debug-only hybrid certification
 * (NativeKeyboardStructuralCertification + crop-scoped live surface liveness for
 * unique native IME keys; SpaceBarGlyphPolicy + ML Kit Korean OCR >= 0.92 for
 * image-only Space), and GenericControl.visualKeySequenceAutonomous.
 * Never forges visual_target_confirmed=true, guesses coordinates, picks arbitrarily
 * among duplicates, weakens native guards, or replays UNKNOWN outcomes.
 */
internal object DebugSixKeyExecution {
    /**
     * Explicit bounded total wall-clock deadline for one complete six-key sequence
     * execution (preflight, optional layout switch, certification, up to 6 guarded
     * keys, bounded read-only recovery episode, and final verification). Strictly
     * separate from SharedQaRecoveryBudget's 15_000 ms recovery episode window and
     * aligned with LocalVerifiedInput's 120_000 ms bounded input precedent.
     */
    internal const val DEFAULT_TOTAL_SEQUENCE_DEADLINE_MS = 120_000L
    private val sequenceLabels = listOf("ㄱ", "ㅏ", "ㄴ", "Space")
    private val allowedLabels = sequenceLabels.toSet()

    internal data class Progress(
        val verified: Int, val attempts: Int, val uncertain: Boolean, val stage: String,
    )

    internal data class Outcome(
        val progress: Progress, val failure: String?,
        val evidence: List<JsonObject>, val observationElapsedMs: Long,
        val followUpNativeReads: Int, val followUpStatus: String,
        val followUpEvidence: JsonObject?,
        val initialPhysicalLayout: String = PhysicalKeyboardLayout.UNVERIFIED.code,
        val finalPhysicalLayout: String = PhysicalKeyboardLayout.UNVERIFIED.code,
        val layoutSwitchAttempts: Int = 0,
        val layoutSwitchConfirmed: Boolean = false,
        val recoveryAttempts: Int = 0,
    )

    internal data class LayoutSwitchPacket(
        val success: Boolean,
        val attempted: Boolean,
        val unknown: Boolean,
        val reason: String? = null,
        val visualSourceStage: String? = null,
        val visualSourceCondition: String? = null,
        val displayPlanRefusal: String? = null,
        val displayWindowCensus: DisplayWindowCensus? = null,
        val captureWindowChanges: JsonObject? = null,
        val cursorProofMismatch: KeyboardContextMismatch? = null,
    )

    internal data class PhysicalCandidate(
        val label: String,
        val nodeId: Int,
        val bounds: String,
        val imageOnly: Boolean,
    )

    internal data class ObservePacket(
        val success: Boolean,
        val data: JsonObject,
        val certifiedFrame: ControlFrame? = null,
        val displayPlanRefusal: String? = null,
        val displayWindowCensus: DisplayWindowCensus? = null,
        val captureWindowChanges: JsonObject? = null,
        val visualSourceStage: String? = null,
        val visualSourceCondition: String? = null,
        val cursorProofMismatch: KeyboardContextMismatch? = null,
    )

    internal data class SequencePacket(
        val success: Boolean,
        val data: JsonObject,
        val captureWindowChanges: JsonObject? = null,
        val cursorProofMismatch: KeyboardContextMismatch? = null,
        val displayPlanRefusal: String? = null,
    )

    private fun safeCode(value: String?): String =
        value?.takeIf { it.length in 1..80 && it.matches(Regex("[a-z0-9_]+")) }
            ?: "unavailable"

    private fun boundedPreservingFirstAndLast(raw: JsonArray?, maxItems: Int = 8): Pair<List<JsonObject>, Int> {
        val objects = raw?.mapNotNull { it as? JsonObject }.orEmpty()
        if (objects.size <= maxItems) return objects to 0
        val headCount = (maxItems - 1).coerceAtLeast(1)
        val kept = objects.take(headCount) + objects.last()
        return kept to (objects.size - kept.size)
    }

    private fun safeInnerRecoveryDecisions(raw: JsonArray?): Pair<JsonArray?, Int> {
        val (kept, dropped) = boundedPreservingFirstAndLast(raw, maxItems = 8)
        if (kept.isEmpty()) return null to 0
        val sanitized = kept.map { obj ->
            buildJsonObject {
                put("phase", safeCode(obj["phase"]?.jsonPrimitive?.contentOrNull))
                obj["allowed"]?.jsonPrimitive?.booleanOrNull?.let { put("allowed", it) }
                obj["rejection_reason"]?.jsonPrimitive?.contentOrNull?.let { put("rejection_reason", safeCode(it)) }
                put("refusal_reason", safeCode(obj["refusal_reason"]?.jsonPrimitive?.contentOrNull))
                obj["budget_used"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }?.let { put("budget_used", it) }
                obj["budget_max"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }?.let { put("budget_max", it) }
                obj["budget_elapsed_ms"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0L }?.let { put("budget_elapsed_ms", it) }
                obj["budget_max_elapsed_ms"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0L }?.let { put("budget_max_elapsed_ms", it) }
                obj["run_elapsed_ms"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0L }?.let { put("run_elapsed_ms", it) }
                obj["recovery_episode_started"]?.jsonPrimitive?.booleanOrNull?.let { put("recovery_episode_started", it) }
                obj["within_window"]?.jsonPrimitive?.booleanOrNull?.let { put("within_window", it) }
                obj["authority_active"]?.jsonPrimitive?.booleanOrNull?.let { put("authority_active", it) }
                obj["pending_key_index"]?.jsonPrimitive?.intOrNull
                    ?.takeIf { it in 1..DebugOneStopQa.SixKey.labels.size }
                    ?.let { put("pending_key_index", it) }
                obj["pending_step_state"]?.jsonPrimitive?.contentOrNull?.let { put("pending_step_state", safeCode(it)) }
            }
        }
        return JsonArray(sanitized) to dropped
    }

    private fun safePostCheckChange(change: JsonObject?): JsonObject? {
        if (change == null) return null
        return buildJsonObject {
            change["source"]?.jsonPrimitive?.contentOrNull?.let { put("source", safeCode(it)) }
            for (flag in listOf(
                "app_identity_changed", "target_window_changed", "foreground_window_changed",
                "frame_context_changed", "app_tree_changed", "ime_tree_changed",
                "chrome_editor_changed", "chrome_suggestions_changed", "chrome_other_changed",
            )) {
                change[flag]?.jsonPrimitive?.booleanOrNull?.let { put(flag, it) }
            }
        }
    }

    private fun safePostCheckRefusals(raw: JsonArray?): Pair<JsonArray?, Int> {
        val (kept, dropped) = boundedPreservingFirstAndLast(raw, maxItems = 8)
        if (kept.isEmpty()) return null to 0
        val sanitized = kept.map { obj ->
            buildJsonObject {
                obj["key_index"]?.jsonPrimitive?.intOrNull
                    ?.takeIf { it in 1..DebugOneStopQa.SixKey.labels.size }
                    ?.let { put("key_index", it) }
                obj["read_attempt"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }?.let { put("read_attempt", it) }
                obj["read_elapsed_ms"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0L }?.let { put("read_elapsed_ms", it) }
                safePostCheckChange(obj["change"] as? JsonObject)?.let { put("change", it) }
            }
        }
        return JsonArray(sanitized) to dropped
    }

    private fun safeCensusJson(raw: JsonObject?): JsonObject? {
        if (raw == null) return null
        return buildJsonObject {
            raw["total_windows"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }?.let { put("total_windows", it) }
            raw["content_windows"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }?.let { put("content_windows", it) }
            raw["empty_content"]?.jsonPrimitive?.booleanOrNull?.let { put("empty_content", it) }
            raw["duplicate_content_ids"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }?.let { put("duplicate_content_ids", it) }
            raw["unowned_content_windows"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }?.let { put("unowned_content_windows", it) }
            raw["rows_truncated"]?.jsonPrimitive?.booleanOrNull?.let { put("rows_truncated", it) }
            (raw["windows"] as? JsonArray)?.let { rows ->
                put("windows", JsonArray(rows.take(DisplayWindowCensus.MAX_ROWS).mapNotNull { elem ->
                    val r = elem as? JsonObject ?: return@mapNotNull null
                    buildJsonObject {
                        r["type"]?.jsonPrimitive?.contentOrNull?.let { put("type", safeCode(it)) }
                        r["ownership"]?.jsonPrimitive?.contentOrNull?.let { put("ownership", safeCode(it)) }
                        r["included_in_policy"]?.jsonPrimitive?.booleanOrNull?.let { put("included_in_policy", it) }
                        r["layer_relative_to_ime"]?.jsonPrimitive?.contentOrNull?.let { put("layer_relative_to_ime", safeCode(it)) }
                        r["overlaps_ime_bounds"]?.jsonPrimitive?.booleanOrNull?.let { put("overlaps_ime_bounds", it) }
                        r["active"]?.jsonPrimitive?.booleanOrNull?.let { put("active", it) }
                        r["focused"]?.jsonPrimitive?.booleanOrNull?.let { put("focused", it) }
                    }
                }))
            }
        }
    }

    private fun safeAttemptFailureEvidence(raw: JsonArray?): Pair<JsonArray?, Int> {
        val (kept, dropped) = boundedPreservingFirstAndLast(raw, maxItems = 8)
        if (kept.isEmpty()) return null to 0
        val sanitized = kept.map { obj ->
            buildJsonObject {
                obj["attempt_seq"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 1 }?.let { put("attempt_seq", it) }
                obj["key_index"]?.jsonPrimitive?.intOrNull
                    ?.takeIf { it in 1..DebugOneStopQa.SixKey.labels.size }
                    ?.let { put("key_index", it) }
                obj["read_attempt"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }?.let { put("read_attempt", it) }
                put("source", safeCode(obj["source"]?.jsonPrimitive?.contentOrNull))
                put("reason_code", safeCode(obj["reason_code"]?.jsonPrimitive?.contentOrNull))
                obj["pending_step_state"]?.jsonPrimitive?.contentOrNull?.let { put("pending_step_state", safeCode(it)) }
                obj["failed_generation_recorded"]?.jsonPrimitive?.booleanOrNull?.let { put("failed_generation_recorded", it) }
                DebugKeyboardEvidence.safeDisplayRefusal(obj["display_plan_refusal"]?.jsonPrimitive?.contentOrNull)?.let {
                    put("display_plan_refusal", it)
                }
                safeCensusJson(obj["display_window_census"] as? JsonObject)?.let { put("display_window_census", it) }
                (obj["capture_window_changes"] as? JsonObject)?.let {
                    put("capture_window_changes", DebugKeyboardEvidence.safeWindowChanges(it))
                }
                (obj["cursor_proof_mismatch"] as? JsonObject)?.let { cm ->
                    put("cursor_proof_mismatch", buildJsonObject {
                        cm["kind"]?.jsonPrimitive?.contentOrNull?.let { put("kind", safeCode(it)) }
                        cm["location"]?.jsonPrimitive?.contentOrNull?.let { put("location", safeCode(it)) }
                        cm["before_cause"]?.jsonPrimitive?.contentOrNull?.let { put("before_cause", safeCode(it)) }
                        cm["after_cause"]?.jsonPrimitive?.contentOrNull?.let { put("after_cause", safeCode(it)) }
                        cm["recoverable"]?.jsonPrimitive?.booleanOrNull?.let { put("recoverable", it) }
                    })
                }
                safePostCheckChange(obj["post_check"] as? JsonObject)?.let { put("post_check", it) }
            }
        }
        return JsonArray(sanitized) to dropped
    }

    /**
     * Explicit narrow safe allowlist for post-stop independent prefix verification.
     * NEVER performs additional observation after security, privacy, unowned-window,
     * lock, revocation, cursor/keyboard-context change, integrity failure, target-change,
     * or unknown refusals — those stay strictly UNKNOWN.
     */
    private val safePartialPrefixObservationReasons = setOf(
        "display_window_changed_during_capture",
        "visual_post_screen_changed",
        "elapsed_budget",
    )

    private val safePartialPrefixSequenceReasons = setOf(
        "display_window_changed_during_capture",
        "display_window_changed_since_observation",
        "visual_post_screen_changed",
        "screen_changed",
        "keyboard_sequence_recovery_budget_exhausted",
        "recovery_episode_elapsed_budget",
    )

    internal fun mayVerifyPartialPrefixAfter(seqReason: String?, obsReason: String?): Boolean = when {
        seqReason == "keyboard_sequence_new_image_unavailable" ->
            obsReason != null && obsReason in safePartialPrefixObservationReasons
        seqReason != null && seqReason in safePartialPrefixSequenceReasons ->
            obsReason == null || obsReason in safePartialPrefixObservationReasons
        else -> false
    }

    private fun extractWindowEvents(sourceFailure: JsonObject?): JsonObject? =
        (sourceFailure?.get("window_event_interval") as? JsonObject)
            ?: (sourceFailure?.get("window_events") as? JsonObject)

    /**
     * Strictly resolves exactly one unique production physical candidate per
     * required key label from a keyboard-sequence observation. Rejects blocked
     * preflight, truncated output, missing visual proof, duplicate non-entry nodes,
     * missing Space fallback, or duplicate node IDs.
     */
    internal fun resolveUniqueSequenceCandidates(
        data: JsonObject,
        labels: List<String> = sequenceLabels,
    ): List<PhysicalCandidate>? {
        if (data["security_scan_complete"]?.jsonPrimitive?.booleanOrNull != true) return null
        if (data["output_truncated"]?.jsonPrimitive?.booleanOrNull != false) return null
        val visual = data["visual"] as? JsonObject ?: return null
        if (visual["scope"]?.jsonPrimitive?.contentOrNull != "keyboard") return null
        if (visual["visual_id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return null
        val preflight = data["keyboard_preflight"] as? JsonObject ?: return null
        if (preflight["status"]?.jsonPrimitive?.contentOrNull != "REVIEW_READY") return null
        if (preflight["reason_code"]?.jsonPrimitive?.contentOrNull != "image_rect_review_required") return null
        if (preflight["effective_empty_caret_zero_verified"]?.jsonPrimitive?.booleanOrNull != true) return null

        val nodes = (data["nodes"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.filter { it["enabled"]?.jsonPrimitive?.booleanOrNull == true }
            .orEmpty()
        val distinctLabels = labels.distinct()
        val resolved = mutableListOf<PhysicalCandidate>()
        for (label in distinctLabels) {
            val native = nodes.filter { node ->
                node["keyboard_key"]?.jsonPrimitive?.contentOrNull == label &&
                    node["visual_key_candidate"]?.jsonPrimitive?.booleanOrNull == true &&
                    node["keyboard_image_candidate"]?.jsonPrimitive?.booleanOrNull != true
            }
            val chosen = when {
                native.isNotEmpty() -> native.singleOrNull() ?: return null
                label == "Space" -> nodes.filter { node ->
                    node["keyboard_image_candidate"]?.jsonPrimitive?.booleanOrNull == true &&
                        node["keyboard_key"]?.jsonPrimitive?.contentOrNull.orEmpty().isEmpty()
                }.singleOrNull() ?: return null
                else -> return null
            }
            val nodeId = chosen["node_id"]?.jsonPrimitive?.intOrNull ?: return null
            val bounds = chosen["bounds"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return null
            val imageOnly = chosen["keyboard_image_candidate"]?.jsonPrimitive?.booleanOrNull == true
            resolved += PhysicalCandidate(label, nodeId, bounds, imageOnly)
        }
        return resolved.takeIf { list ->
            list.size == distinctLabels.size && list.map { it.nodeId }.distinct().size == list.size
        }
    }

    internal suspend fun runWithSeam(
        captureFrame: suspend () -> ControlFrame,
        observeSequence: suspend (List<String>) -> ObservePacket,
        certifyCandidate: suspend (String, ControlFrame, PhysicalCandidate, List<String>) -> PixelCertificationOutcome,
        dispatchSequence: suspend (JsonObject, AutonomousKeyboardSequenceProof) -> SequencePacket,
        sessionId: String,
        sessionEpoch: Long = 0L,
        first: ControlFrame,
        allowed: () -> Boolean,
        checkpoint: (Progress, String) -> Unit,
        clockMs: () -> Long = { SystemClock.elapsedRealtime() },
        nonceGenerator: () -> String = { UUID.randomUUID().toString() },
        switchToKoreanLayout: suspend (ControlFrame, KoreanLayoutSwitchCandidate, String) -> LayoutSwitchPacket = { _, _, _ ->
            LayoutSwitchPacket(false, false, false, "korean_layout_switch_unavailable")
        },
        invalidateObservation: () -> Unit = {},
        recoveryBudget: SharedQaRecoveryBudget = SharedQaRecoveryBudget(clockMs = clockMs),
        sessionCurrent: () -> Boolean = allowed,
        commitPass: () -> Boolean = { allowed() && sessionCurrent() },
        totalSequenceDeadlineMs: Long = DEFAULT_TOTAL_SEQUENCE_DEADLINE_MS,
    ): Outcome {
        require(totalSequenceDeadlineMs > 0L)
        val sequenceStartedAtMs = clockMs()
        fun totalRunElapsedMs(): Long = (clockMs() - sequenceStartedAtMs).coerceAtLeast(0L)
        fun isDeadlineExpired(): Boolean = totalRunElapsedMs() >= totalSequenceDeadlineMs
        var verified = 0
        var attempts = 0
        var uncertain = false
        var stage = "key_1_preflight"
        var observationMs = 0L
        var extraReads = 0
        var afterFailure = "not_required"
        var followUpEvidence: JsonObject? = null
        var initialLayoutCode = PhysicalKeyboardLayout.UNVERIFIED.code
        var finalLayoutCode = PhysicalKeyboardLayout.UNVERIFIED.code
        var layoutSwitchAttempts = 0
        var layoutSwitchConfirmed = false
        var layoutSwitchDispatchPending = false
        var sequenceDispatchPending = false
        val evidence = mutableListOf<JsonObject>()
        fun progress() = Progress(verified, attempts, uncertain, stage)
        fun fail(reason: String) = Outcome(
            progress(), reason, evidence.toList(), observationMs,
            extraReads, afterFailure, followUpEvidence,
            initialLayoutCode, finalLayoutCode, layoutSwitchAttempts, layoutSwitchConfirmed,
            recoveryBudget.used,
        )
        fun hasDispatchedMutation(): Boolean =
            attempts > 0 || verified > 0 || layoutSwitchAttempts > 0 ||
                sequenceDispatchPending || layoutSwitchDispatchPending || uncertain
        fun failDeadline(phase: String, afterDispatch: Boolean): Outcome {
            invalidateObservation()
            if (afterDispatch) uncertain = true
            evidence += buildJsonObject {
                put("source", "sequence_deadline_exceeded")
                put("phase", safeCode(phase))
                put("run_elapsed_ms", totalRunElapsedMs())
                put("total_sequence_deadline_ms", totalSequenceDeadlineMs)
                put("recovery_budget_used", recoveryBudget.used)
                put("recovery_budget_max", recoveryBudget.maxAttempts)
                put("recovery_budget_elapsed_ms", recoveryBudget.elapsedMs())
                put("recovery_episode_started", recoveryBudget.isEpisodeStarted())
                put("after_dispatch", afterDispatch)
                put("layout_switch_attempted", layoutSwitchAttempts > 0)
                put("layout_switch_confirmed", layoutSwitchConfirmed)
                put("layout_switch_dispatch_pending", layoutSwitchDispatchPending)
                put("completed_keys", verified)
                put("attempts", attempts)
            }
            return fail("six_key_sequence_deadline_exceeded")
        }
        fun checkpointAt(next: String, reason: String) {
            stage = next
            checkpoint(progress(), reason)
        }
        fun recordRecoveryDecision(
            phase: String,
            refusalReason: String?,
            decision: RecoveryDecision,
            actionAttempted: Boolean,
            outcomeUnknown: Boolean,
            completedKeys: Int = 0,
            visualSourceStage: String? = null,
            visualSourceCondition: String? = null,
        ) {
            evidence += buildJsonObject {
                put("source", "recovery_decision")
                put("phase", safeCode(phase))
                put("allowed", decision.allowed)
                decision.rejectionReason?.let { put("rejection_reason", safeCode(it.code)) }
                put("refusal_reason", safeCode(refusalReason))
                put("budget_used", decision.used)
                put("budget_max", decision.maxAttempts)
                put("budget_elapsed_ms", decision.elapsedMs)
                put("budget_max_elapsed_ms", decision.maxElapsedMs)
                put("run_elapsed_ms", decision.runElapsedMs)
                put("recovery_episode_started", decision.episodeStarted)
                put("within_window", decision.withinWindow)
                put("authority_active", decision.authorityActive)
                put("action_attempted", actionAttempted)
                put("outcome_unknown", outcomeUnknown)
                put("completed_keys", completedKeys)
                visualSourceStage?.let { put("visual_source_stage", safeCode(it)) }
                visualSourceCondition?.let { put("visual_source_condition", safeCode(it)) }
            }
        }
        fun authorityActive(): Boolean = allowed() && sessionCurrent()
        fun revokeAndFail(markUncertain: Boolean = attempts > 0): Outcome {
            invalidateObservation()
            if (markUncertain) uncertain = true
            return fail("control_authority_revoked")
        }
        fun evaluateAndClaimRecovery(
            phase: String,
            reason: String?,
            actionAttempted: Boolean,
            outcomeUnknown: Boolean,
            completedKeys: Int = 0,
            visualSourceStage: String? = null,
            visualSourceCondition: String? = null,
            observationOnly: Boolean = false,
            recoverableCursorProofRace: Boolean = false,
        ): Boolean {
            if (isDeadlineExpired()) {
                val elapsed = recoveryBudget.elapsedMs()
                val decision = RecoveryDecision(
                    allowed = false,
                    rejectionReason = ai.closepaw.chatgpt.control.RecoveryRejectionReason.DEADLINE_EXCEEDED,
                    used = recoveryBudget.used,
                    maxAttempts = recoveryBudget.maxAttempts,
                    elapsedMs = elapsed,
                    maxElapsedMs = recoveryBudget.maxElapsedMs,
                    withinWindow = elapsed <= recoveryBudget.maxElapsedMs,
                    authorityActive = authorityActive(),
                    runElapsedMs = recoveryBudget.totalElapsedMs(),
                    episodeStarted = recoveryBudget.isEpisodeStarted(),
                )
                recordRecoveryDecision(
                    phase = phase,
                    refusalReason = reason,
                    decision = decision,
                    actionAttempted = actionAttempted,
                    outcomeUnknown = outcomeUnknown,
                    completedKeys = completedKeys,
                    visualSourceStage = visualSourceStage,
                    visualSourceCondition = visualSourceCondition,
                )
                return false
            }
            val decision = DisplayObservationRecoveryPolicy.evaluateAndClaimPreExecution(
                budget = recoveryBudget,
                reason = reason,
                actionAttempted = actionAttempted,
                outcomeUnknown = outcomeUnknown,
                authorityActive = authorityActive(),
                completedKeys = completedKeys,
                visualSourceStage = visualSourceStage,
                visualSourceCondition = visualSourceCondition,
                observationOnly = observationOnly,
                recoverableCursorProofRace = recoverableCursorProofRace,
            )
            recordRecoveryDecision(
                phase = phase,
                refusalReason = reason,
                decision = decision,
                actionAttempted = actionAttempted,
                outcomeUnknown = outcomeUnknown,
                completedKeys = completedKeys,
                visualSourceStage = visualSourceStage,
                visualSourceCondition = visualSourceCondition,
            )
            return decision.allowed
        }
        suspend fun captureFrameRecoverable(stageName: String): ControlFrame {
            if (!authorityActive()) {
                invalidateObservation()
                return ControlFrame("", -1, "", emptyList(), reason = "control_authority_revoked")
            }
            if (isDeadlineExpired()) {
                invalidateObservation()
                return ControlFrame("", -1, "", emptyList(), reason = "six_key_sequence_deadline_exceeded")
            }
            var frame = captureFrame()
            while (frame.reason != null &&
                DisplayObservationRecoveryPolicy.isRecoverableRecaptureReason(frame.reason) &&
                !isDeadlineExpired()
            ) {
                val decision = recoveryBudget.claimDetailed(authorityActive = authorityActive())
                recordRecoveryDecision(
                    phase = "frame_recapture",
                    refusalReason = frame.reason,
                    decision = decision,
                    actionAttempted = false,
                    outcomeUnknown = false,
                )
                if (!decision.allowed) break
                checkpointAt(stageName, "read_only_recapture_recovery")
                invalidateObservation()
                frame = captureFrame()
            }
            if (!authorityActive()) {
                invalidateObservation()
                return ControlFrame("", -1, "", emptyList(), reason = "control_authority_revoked")
            }
            return frame
        }
        // Bounded passive read-only postcondition re-observation: never replays any touch/action
        // and never consumes pre-dispatch recovery budget; bound to original session/authority
        // before, during, and after capture; still fails closed as UNKNOWN if unprovable.
        suspend fun capturePostconditionReadOnly(maxRetries: Int = 2): ControlFrame {
            if (!authorityActive()) {
                invalidateObservation()
                return ControlFrame("", -1, "", emptyList(), reason = "control_authority_revoked")
            }
            if (isDeadlineExpired()) {
                invalidateObservation()
                return ControlFrame("", -1, "", emptyList(), reason = "six_key_sequence_deadline_exceeded")
            }
            var frame = captureFrame()
            var retries = 0
            while (frame.reason != null &&
                DisplayObservationRecoveryPolicy.isRecoverableRecaptureReason(frame.reason) &&
                retries < maxRetries && authorityActive() && !isDeadlineExpired()
            ) {
                retries++
                frame = captureFrame()
            }
            if (!authorityActive()) {
                invalidateObservation()
                return ControlFrame("", -1, "", emptyList(), reason = "control_authority_revoked")
            }
            return frame
        }
        fun recertifyEmptyEditor(baseline: ControlFrame, fresh: ControlFrame): String? =
            DisplayObservationRecoveryPolicy.recertificationRefusal(baseline, fresh)
                ?: when {
                    !DebugOneStopQa.sameEditor(first, fresh) -> "editor_identity_changed"
                    fresh.packageName != "com.android.chrome" -> "target_package_changed"
                    fresh.keyboard?.packageName != "com.samsung.android.honeyboard" ->
                        "keyboard_changed_or_unauthorized"
                    fresh.inputTarget == null || !DebugOneStopQa.SixKey.matches(
                        fresh.inputTarget.text, fresh.inputTarget.selectionStart, fresh.inputTarget.selectionEnd, 0,
                    ) -> "editor_value_or_caret_changed"
                    else -> null
                }

        return withTimeoutOrNull(totalSequenceDeadlineMs) {
        currentCoroutineContext().ensureActive()
        if (!authorityActive()) return@withTimeoutOrNull revokeAndFail(markUncertain = false)
        if (isDeadlineExpired()) return@withTimeoutOrNull failDeadline("key_1_preflight", afterDispatch = false)
        checkpointAt("key_1_preflight", "fresh_keyboard_sequence_observation")

        var before = captureFrameRecoverable("key_1_preflight")
        if (!authorityActive()) return@withTimeoutOrNull revokeAndFail(markUncertain = false)
        if (before.reason == "six_key_sequence_deadline_exceeded" || isDeadlineExpired()) {
            return@withTimeoutOrNull failDeadline("key_1_preflight", afterDispatch = false)
        }
        if (before.reason != null || before.truncated || before.navigationOnly) {
            return@withTimeoutOrNull fail("editor_checkpoint_changed")
        }
        if (!DebugOneStopQa.sameEditor(first, before) ||
            !KeyboardEditorContinuity.sameFocusedEditor(first, before, allowAnonymousResize = false)) {
            return@withTimeoutOrNull fail("editor_identity_changed")
        }
        val field = before.inputTarget
        if (before.packageName != "com.android.chrome" ||
            before.keyboard?.packageName != "com.samsung.android.honeyboard" || field == null ||
            !field.focused || !field.editable || !field.focusable || !field.enabled || field.password ||
            !DebugOneStopQa.SixKey.matches(field.text, field.selectionStart, field.selectionEnd, 0))
            return@withTimeoutOrNull fail("editor_checkpoint_changed")

        // Post-focus target-layout readiness check: distinguish physical keys from suggestion
        // captions and allow at most one guarded LATIN -> HANGUL switch before sequence acquisition.
        val initialLayout = NativeKeyboardStructuralCertification.classifyPhysicalLayout(before)
        initialLayoutCode = initialLayout.code
        finalLayoutCode = initialLayout.code
        fun recordLayoutEvidence(
            route: String,
            refusal: String? = null,
            packet: LayoutSwitchPacket? = null,
        ) {
            evidence += buildJsonObject {
                put("source", "korean_layout_readiness")
                put("initial_physical_layout", initialLayoutCode)
                put("final_physical_layout", finalLayoutCode)
                put("layout_switch_attempted", layoutSwitchAttempts > 0)
                put("layout_switch_attempts", layoutSwitchAttempts)
                put("layout_switch_confirmed", layoutSwitchConfirmed)
                put("layout_switch_route", safeCode(route))
                put("read_only_recovery_attempts", recoveryBudget.used)
                refusal?.let { put("refusal_reason", safeCode(it)) }
                packet?.let { p ->
                    p.visualSourceStage?.let { put("visual_source_stage", safeCode(it)) }
                    p.visualSourceCondition?.let { put("visual_source_condition", safeCode(it)) }
                    DebugKeyboardEvidence.safeDisplayRefusal(p.displayPlanRefusal)?.let {
                        put("display_plan_refusal", it)
                    }
                    if (DebugKeyboardEvidence.shouldAttachWindowChanges(refusal, p.visualSourceCondition) &&
                        p.captureWindowChanges != null) {
                        put("capture_window_changes", DebugKeyboardEvidence.safeWindowChanges(p.captureWindowChanges))
                    }
                    if (refusal == "visual_post_keyboard_context_changed" && p.cursorProofMismatch != null) {
                        put("cursor_proof_mismatch", DebugKeyboardEvidence.safeCursorProofMismatch(p.cursorProofMismatch))
                    }
                    if (p.displayPlanRefusal == "display_unowned_window" &&
                        refusal == "display_surface_unverified" && p.displayWindowCensus != null) {
                        put("display_window_census", DebugKeyboardEvidence.safeDisplayWindowCensus(p.displayWindowCensus))
                    }
                }
            }
        }
        when (initialLayout) {
            PhysicalKeyboardLayout.HANGUL -> {
                recordLayoutEvidence("none")
            }
            PhysicalKeyboardLayout.SYMBOLS_OR_NUMBERS -> {
                recordLayoutEvidence("none", "korean_layout_switch_unsupported_page")
                return@withTimeoutOrNull fail("korean_layout_switch_unsupported_page")
            }
            PhysicalKeyboardLayout.UNVERIFIED -> {
                recordLayoutEvidence("none", "korean_layout_unverified")
                return@withTimeoutOrNull fail("korean_layout_unverified")
            }
            PhysicalKeyboardLayout.LATIN -> {
                var switchCandidate = NativeKeyboardStructuralCertification.findUniqueKoreanLayoutSwitchCandidate(before)
                    ?: run {
                        recordLayoutEvidence("none", "korean_layout_switch_unavailable")
                        return@withTimeoutOrNull fail("korean_layout_switch_unavailable")
                    }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!authorityActive()) {
                        recordLayoutEvidence(switchCandidate.route.code, "control_authority_revoked")
                        return@withTimeoutOrNull revokeAndFail(markUncertain = layoutSwitchAttempts > 0)
                    }
                    if (isDeadlineExpired()) {
                        recordLayoutEvidence(switchCandidate.route.code, "six_key_sequence_deadline_exceeded")
                        return@withTimeoutOrNull failDeadline("layout_switch", afterDispatch = layoutSwitchAttempts > 0)
                    }
                    checkpointAt("layout_readiness_switch_dispatch", "guarded_korean_layout_switch")
                    layoutSwitchDispatchPending = true
                    val switched = switchToKoreanLayout(before, switchCandidate, nonceGenerator())
                    if (!switched.attempted && !switched.unknown) {
                        layoutSwitchDispatchPending = false
                    }
                    if (switched.attempted) layoutSwitchAttempts = 1
                    if (!authorityActive()) {
                        recordLayoutEvidence(switchCandidate.route.code, "control_authority_revoked")
                        return@withTimeoutOrNull revokeAndFail(markUncertain = switched.attempted || switched.unknown || layoutSwitchAttempts > 0)
                    }
                    if (switched.success && switched.attempted && !switched.unknown) break
                    val refusal = safeCode(switched.reason ?: "korean_layout_switch_unconfirmed")
                    recordLayoutEvidence(switchCandidate.route.code, refusal, switched)
                    if (switched.attempted || switched.unknown) {
                        uncertain = true
                        return@withTimeoutOrNull fail(refusal)
                    }
                    if (!evaluateAndClaimRecovery(
                            phase = "layout_switch",
                            reason = switched.reason,
                            actionAttempted = switched.attempted,
                            outcomeUnknown = switched.unknown,
                            visualSourceStage = switched.visualSourceStage,
                            visualSourceCondition = switched.visualSourceCondition,
                            recoverableCursorProofRace = switched.cursorProofMismatch?.recoverableReadOnlyCursorRace == true,
                        )) {
                        if (isDeadlineExpired()) return@withTimeoutOrNull failDeadline("layout_switch", afterDispatch = hasDispatchedMutation())
                        return@withTimeoutOrNull fail(refusal)
                    }
                    // Confirmed pre-dispatch non-execution: discard stale observation, acquire fresh
                    // observation, re-authenticate target/editor/IME/layout, and re-resolve switch key.
                    checkpointAt("layout_readiness_recovery", "read_only_capture_recovery")
                    invalidateObservation()
                    val fresh = captureFrameRecoverable("layout_readiness_recovery")
                    if (!authorityActive()) {
                        recordLayoutEvidence(switchCandidate.route.code, "control_authority_revoked")
                        return@withTimeoutOrNull revokeAndFail(markUncertain = hasDispatchedMutation())
                    }
                    if (fresh.reason == "six_key_sequence_deadline_exceeded" || isDeadlineExpired()) {
                        recordLayoutEvidence(switchCandidate.route.code, "six_key_sequence_deadline_exceeded")
                        return@withTimeoutOrNull failDeadline("layout_readiness_recovery", afterDispatch = hasDispatchedMutation())
                    }
                    val recertRefusal = recertifyEmptyEditor(before, fresh)
                    if (recertRefusal != null) {
                        val safe = safeCode(recertRefusal)
                        recordLayoutEvidence(switchCandidate.route.code, safe)
                        return@withTimeoutOrNull fail(safe)
                    }
                    val freshLayout = NativeKeyboardStructuralCertification.classifyPhysicalLayout(fresh)
                    finalLayoutCode = freshLayout.code
                    if (freshLayout != PhysicalKeyboardLayout.LATIN) {
                        val layoutRefusal = if (freshLayout == PhysicalKeyboardLayout.HANGUL) {
                            "layout_changed_unexpectedly_before_switch"
                        } else {
                            "korean_layout_changed_before_switch"
                        }
                        recordLayoutEvidence(switchCandidate.route.code, layoutRefusal)
                        return@withTimeoutOrNull fail(layoutRefusal)
                    }
                    val freshCandidate = NativeKeyboardStructuralCertification.findUniqueKoreanLayoutSwitchCandidate(fresh)
                    if (freshCandidate == null) {
                        recordLayoutEvidence("none", "korean_layout_switch_unavailable")
                        return@withTimeoutOrNull fail("korean_layout_switch_unavailable")
                    }
                    before = fresh
                    switchCandidate = freshCandidate
                }
                if (!authorityActive()) {
                    uncertain = true
                    recordLayoutEvidence(switchCandidate.route.code, "control_authority_revoked")
                    return@withTimeoutOrNull fail("control_authority_revoked")
                }
                val afterSwitch = capturePostconditionReadOnly()
                if (!authorityActive()) {
                    uncertain = true
                    recordLayoutEvidence(switchCandidate.route.code, "control_authority_revoked")
                    return@withTimeoutOrNull fail("control_authority_revoked")
                }
                if (afterSwitch.reason == "six_key_sequence_deadline_exceeded" || isDeadlineExpired()) {
                    recordLayoutEvidence(switchCandidate.route.code, "six_key_sequence_deadline_exceeded")
                    return@withTimeoutOrNull failDeadline("layout_post_switch", afterDispatch = true)
                }
                val afterField = afterSwitch.inputTarget
                val afterLayout = NativeKeyboardStructuralCertification.classifyPhysicalLayout(afterSwitch)
                finalLayoutCode = afterLayout.code
                val switchedCleanly = afterSwitch.reason == null &&
                    !afterSwitch.truncated && !afterSwitch.navigationOnly &&
                    afterSwitch.packageName == "com.android.chrome" &&
                    afterSwitch.fingerprint != before.fingerprint &&
                    DebugOneStopQa.sameEditor(first, afterSwitch) &&
                    afterSwitch.keyboard == before.keyboard &&
                    afterField != null &&
                    DebugOneStopQa.SixKey.matches(afterField.text, afterField.selectionStart, afterField.selectionEnd, 0) &&
                    afterLayout == PhysicalKeyboardLayout.HANGUL &&
                    VisualKeyboardPolicy.literalLayoutChanged(
                        before, afterSwitch, languageSwitch = true,
                        destination = Character.UnicodeScript.HANGUL,
                    )
                if (!switchedCleanly) {
                    uncertain = true
                    recordLayoutEvidence(switchCandidate.route.code, "korean_layout_post_switch_unverified")
                    return@withTimeoutOrNull fail("korean_layout_post_switch_unverified")
                }
                layoutSwitchConfirmed = true
                layoutSwitchDispatchPending = false
                recordLayoutEvidence(switchCandidate.route.code)
                before = afterSwitch
                checkpointAt("key_1_preflight", "korean_layout_ready")
            }
        }

        // Unified pre-dispatch sequence acquisition, 4-key certification, and pre-dispatch
        // recovery loop. Any confirmed no-touch DISPLAY_WINDOW_CHANGED or one-side cursor
        // race discards ALL 4 certifications and re-observes + re-certifies fresh using
        // the single shared recovery budget. Never re-enters once attempted/unknown/completed>0.
        var finalSequenceResult: SequencePacket
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!authorityActive()) return@withTimeoutOrNull revokeAndFail(markUncertain = hasDispatchedMutation())
            if (isDeadlineExpired()) return@withTimeoutOrNull failDeadline("sequence_observe", afterDispatch = hasDispatchedMutation())
            val started = clockMs()
            val observed = observeSequence(sequenceLabels)
            observationMs += (clockMs() - started).coerceAtLeast(0L)
            if (!authorityActive()) return@withTimeoutOrNull revokeAndFail(markUncertain = hasDispatchedMutation())
            evidence += DebugKeyboardEvidence.fromObservation(
                observed.data, observed.success, sequenceLabels,
                if (observed.success) null else observed.displayPlanRefusal,
                if (observed.success) null else observed.displayWindowCensus,
                if (observed.success) null else observed.captureWindowChanges,
                if (observed.success) null else observed.visualSourceStage,
                if (observed.success) null else observed.visualSourceCondition,
                if (observed.success) null else observed.cursorProofMismatch,
            )
            if (!observed.success) {
                val obsReason = observed.data["reason_code"]?.jsonPrimitive?.contentOrNull
                val obsAttempted = observed.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true
                val obsUnknown = observed.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true
                if (!evaluateAndClaimRecovery(
                        phase = "sequence_observe",
                        reason = obsReason,
                        actionAttempted = obsAttempted,
                        outcomeUnknown = obsUnknown,
                        visualSourceStage = observed.visualSourceStage,
                        visualSourceCondition = observed.visualSourceCondition,
                        observationOnly = true,
                        recoverableCursorProofRace = observed.cursorProofMismatch?.recoverableReadOnlyCursorRace == true,
                    )) {
                    // A refusal could mean protected content, overlay, or revoked consent.
                    // Do not collect another frame after that refusal.
                    afterFailure = "skipped_refused_observation"
                    if (isDeadlineExpired()) return@withTimeoutOrNull failDeadline("sequence_observe", afterDispatch = hasDispatchedMutation())
                    return@withTimeoutOrNull fail("keyboard_observation_refused")
                }
                checkpointAt("key_1_preflight", "read_only_observation_recovery")
                invalidateObservation()
                val fresh = captureFrameRecoverable("key_1_preflight")
                if (!authorityActive()) return@withTimeoutOrNull revokeAndFail(markUncertain = hasDispatchedMutation())
                if (fresh.reason == "six_key_sequence_deadline_exceeded" || isDeadlineExpired()) {
                    return@withTimeoutOrNull failDeadline("sequence_observe_recovery", afterDispatch = hasDispatchedMutation())
                }
                val recertRefusal = recertifyEmptyEditor(before, fresh)
                    ?: if (NativeKeyboardStructuralCertification.classifyPhysicalLayout(fresh) != PhysicalKeyboardLayout.HANGUL) {
                        "korean_layout_changed_before_sequence"
                    } else null
                if (recertRefusal != null) {
                    afterFailure = "skipped_refused_observation"
                    return@withTimeoutOrNull fail(safeCode(recertRefusal))
                }
                before = fresh
                continue
            }

            val observationId = observed.data["observation_id"]?.jsonPrimitive?.contentOrNull
                ?: return@withTimeoutOrNull fail("observation_id_missing")
            val visualId = (observed.data["visual"] as? JsonObject)?.get("visual_id")?.jsonPrimitive?.contentOrNull
            val candidates = resolveUniqueSequenceCandidates(observed.data, sequenceLabels)
            if (visualId.isNullOrBlank() || candidates == null) {
                val followUp = DebugKeyboardEvidence.inspectAmbiguousCandidates(
                    capture = captureFrame, baseline = first, keyIndex = 0, allowed = allowed,
                )
                afterFailure = followUp.status
                followUpEvidence = followUp.evidence
                extraReads += followUp.reads
                return@withTimeoutOrNull fail("unique_physical_key_not_proven")
            }

            val certifiedFrame = observed.certifiedFrame
            if (certifiedFrame == null) {
                if (observed.visualSourceStage != null || observed.visualSourceCondition != null ||
                    observed.captureWindowChanges != null) {
                    evidence += buildJsonObject {
                        put("source", "certified_observation_lookup_refused")
                        put("read_only_recovery_attempts", recoveryBudget.used)
                        observed.visualSourceStage?.let { put("visual_source_stage", safeCode(it)) }
                        observed.visualSourceCondition?.let { put("visual_source_condition", safeCode(it)) }
                        if (DebugKeyboardEvidence.shouldAttachWindowChanges(VISUAL_SOURCE_REFUSED, observed.visualSourceCondition) &&
                            observed.captureWindowChanges != null) {
                            put("capture_window_changes", DebugKeyboardEvidence.safeWindowChanges(observed.captureWindowChanges))
                        }
                    }
                }
                if (evaluateAndClaimRecovery(
                        phase = "certified_observation_lookup",
                        reason = VISUAL_SOURCE_REFUSED,
                        actionAttempted = false,
                        outcomeUnknown = false,
                        visualSourceStage = observed.visualSourceStage,
                        visualSourceCondition = observed.visualSourceCondition,
                    )) {
                    checkpointAt("key_1_preflight", "read_only_certified_observation_recovery")
                    invalidateObservation()
                    val fresh = captureFrameRecoverable("key_1_preflight")
                    if (!authorityActive()) return@withTimeoutOrNull revokeAndFail(markUncertain = hasDispatchedMutation())
                    if (fresh.reason == "six_key_sequence_deadline_exceeded" || isDeadlineExpired()) {
                        return@withTimeoutOrNull failDeadline("certified_observation_lookup", afterDispatch = hasDispatchedMutation())
                    }
                    val recertRefusal = recertifyEmptyEditor(before, fresh)
                        ?: if (NativeKeyboardStructuralCertification.classifyPhysicalLayout(fresh) != PhysicalKeyboardLayout.HANGUL) {
                            "korean_layout_changed_before_sequence"
                        } else null
                    if (recertRefusal != null) return@withTimeoutOrNull fail(safeCode(recertRefusal))
                    before = fresh
                    continue
                }
                if (isDeadlineExpired()) return@withTimeoutOrNull failDeadline("certified_observation_lookup", afterDispatch = hasDispatchedMutation())
                return@withTimeoutOrNull fail("certified_observation_unavailable")
            }
            if (!DebugOneStopQa.sameEditor(first, certifiedFrame) || certifiedFrame.keyboard != before.keyboard) {
                return@withTimeoutOrNull fail("editor_identity_changed")
            }

            // Strict fail-closed hybrid certification against the EXACT observed certified frame
            // (never stale `before`). Never forges visual_target_confirmed=true.
            val requiredNativeLabels = candidates.filterNot { it.imageOnly }.map { it.label }.distinct()
            val certifiedStepsByLabel = linkedMapOf<String, Pair<PhysicalCandidate, PixelCertificationOutcome>>()
            val nativeStructuralVerified = mutableListOf<String>()
            val imageRoleVerified = mutableListOf<String>()
            val unverifiedLabels = mutableListOf<String>()
            val checkDetails = mutableListOf<JsonObject>()
            var certDisplayFailure: VisualSourceFailure? = null
            var certWindowChanges: JsonObject? = null
            var nonDisplayCertFailure = false
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()
                if (!authorityActive()) return@withTimeoutOrNull revokeAndFail(markUncertain = hasDispatchedMutation())
                if (isDeadlineExpired()) return@withTimeoutOrNull failDeadline("candidate_certification", afterDispatch = hasDispatchedMutation())
                val check = certifyCandidate(visualId, certifiedFrame, candidate, requiredNativeLabels)
                if (!authorityActive()) return@withTimeoutOrNull revokeAndFail(markUncertain = hasDispatchedMutation())
                val crop = check.crop?.takeIf { it.valid }
                val validMethod = if (candidate.imageOnly) {
                    check.method in setOf("space_glyph", "mlkit_ocr", "cache_reused")
                } else {
                    check.method == "native_structural_and_live_surface"
                }
                val certified = crop != null && validMethod
                if (certified) {
                    certifiedStepsByLabel[candidate.label] = candidate to check
                    if (candidate.label in allowedLabels) {
                        if (candidate.imageOnly) imageRoleVerified += candidate.label
                        else nativeStructuralVerified += candidate.label
                    }
                } else {
                    if (candidate.label in allowedLabels) unverifiedLabels += candidate.label
                    val srcFailure = check.visualSourceFailure
                    val isRecoverableDisplayChange = srcFailure != null &&
                        DisplayObservationRecoveryPolicy.isRecoverablePreDispatchRefusal(
                            reason = VISUAL_SOURCE_REFUSED,
                            visualSourceStage = srcFailure.stage.code,
                            visualSourceCondition = srcFailure.condition.code,
                        )
                    if (isRecoverableDisplayChange) {
                        certDisplayFailure = srcFailure
                        certWindowChanges = check.captureWindowChanges ?: srcFailure.windowEvents
                    } else {
                        nonDisplayCertFailure = true
                    }
                }
                if (candidate.label in allowedLabels) {
                    checkDetails += buildJsonObject {
                        put("label", candidate.label)
                        put("image_only", candidate.imageOnly)
                        put("certified", certified)
                        put("method", safeCode(check.method))
                        if (!certified) {
                            put("failure_stage", safeCode(check.failureStage ?: if (!validMethod) "invalid_certification_method" else null))
                            check.visualSourceFailure?.let { sf ->
                                put("visual_source_stage", safeCode(sf.stage.code))
                                put("visual_source_condition", safeCode(sf.condition.code))
                            }
                        }
                        put("proof_age_ms", check.proofAgeMs.coerceAtLeast(-1L))
                        put("crop_width", check.cropWidth.coerceAtLeast(0))
                        put("crop_height", check.cropHeight.coerceAtLeast(0))
                        put("ocr_attempted", check.ocrAttempted)
                        check.ocrLabelMatched?.let { put("ocr_label_matched", it) }
                        put("ocr_confidence_bucket", safeCode(check.ocrConfidenceBucket))
                        put("elapsed_ms", check.elapsedMs.coerceAtLeast(0L))
                    }
                }
                if (!certified && certDisplayFailure != null && !nonDisplayCertFailure) {
                    // Frame proof is already display-invalidated; discard this pass immediately
                    // rather than certifying remaining keys against a dead frame.
                    break
                }
            }
            val imageOnlyCandidates = candidates.filter { it.imageOnly }
            val allCandidatesCertified = unverifiedLabels.isEmpty() && certifiedStepsByLabel.size == sequenceLabels.size
            evidence += buildJsonObject {
                put("source", "autonomous_keyboard_certification_check")
                put("confirmation_mode", "autonomous_debug_hybrid_structural_and_image")
                put("external_visual_review_confirmed", false)
                put("native_keys_semantically_ocr_verified", false)
                put("image_only_keys_pixel_role_verified",
                    imageOnlyCandidates.isNotEmpty() && imageRoleVerified.size == imageOnlyCandidates.size)
                put("all_candidates_certified", allCandidatesCertified)
                put("native_structural_verified_labels", JsonArray(nativeStructuralVerified.map(::JsonPrimitive)))
                put("image_role_verified_labels", JsonArray(imageRoleVerified.map(::JsonPrimitive)))
                put("unverified_labels", JsonArray(unverifiedLabels.map(::JsonPrimitive)))
                put("certification_checks", JsonArray(checkDetails))
                put("touch_authorized_without_certification", false)
                put("read_only_recovery_attempts", recoveryBudget.used)
                certDisplayFailure?.let { sf ->
                    put("visual_source_stage", safeCode(sf.stage.code))
                    put("visual_source_condition", safeCode(sf.condition.code))
                }
                certWindowChanges?.let { changes ->
                    put("capture_window_changes", DebugKeyboardEvidence.safeWindowChanges(changes))
                }
            }
            if (!allCandidatesCertified) {
                if (!nonDisplayCertFailure && certDisplayFailure != null &&
                    evaluateAndClaimRecovery(
                        phase = "candidate_certification",
                        reason = VISUAL_SOURCE_REFUSED,
                        actionAttempted = false,
                        outcomeUnknown = false,
                        visualSourceStage = certDisplayFailure.stage.code,
                        visualSourceCondition = certDisplayFailure.condition.code,
                    )) {
                    // Discard ALL 4 certifications + old observation; re-observe and re-certify fresh.
                    checkpointAt("key_1_preflight", "read_only_certification_recovery")
                    invalidateObservation()
                    val fresh = captureFrameRecoverable("key_1_preflight")
                    if (!authorityActive()) return@withTimeoutOrNull revokeAndFail(markUncertain = hasDispatchedMutation())
                    if (fresh.reason == "six_key_sequence_deadline_exceeded" || isDeadlineExpired()) {
                        return@withTimeoutOrNull failDeadline("candidate_certification", afterDispatch = hasDispatchedMutation())
                    }
                    val recertRefusal = recertifyEmptyEditor(before, fresh)
                        ?: if (NativeKeyboardStructuralCertification.classifyPhysicalLayout(fresh) != PhysicalKeyboardLayout.HANGUL) {
                            "korean_layout_changed_before_sequence"
                        } else null
                    if (recertRefusal != null) return@withTimeoutOrNull fail(safeCode(recertRefusal))
                    before = fresh
                    continue
                }
                if (isDeadlineExpired()) return@withTimeoutOrNull failDeadline("candidate_certification", afterDispatch = hasDispatchedMutation())
                val followUp = DebugKeyboardEvidence.inspectAmbiguousCandidates(
                    capture = captureFrame, baseline = first, keyIndex = 0, allowed = allowed,
                )
                afterFailure = followUp.status
                followUpEvidence = followUp.evidence
                extraReads += followUp.reads
                return@withTimeoutOrNull fail("autonomous_keyboard_certification_unverified")
            }

            if (!authorityActive()) return@withTimeoutOrNull revokeAndFail(markUncertain = hasDispatchedMutation())
            if (isDeadlineExpired()) return@withTimeoutOrNull failDeadline("sequence_pre_dispatch", afterDispatch = hasDispatchedMutation())
            val proofSteps = mutableListOf<AutonomousCertifiedKeyStep>()
            val steps = JsonArray(DebugOneStopQa.SixKey.labels.map { label ->
                val (candidate, check) = certifiedStepsByLabel[label]
                    ?: return@withTimeoutOrNull fail("autonomous_keyboard_certification_unverified")
                val crop = check.crop ?: return@withTimeoutOrNull fail("autonomous_keyboard_certification_unverified")
                proofSteps += AutonomousCertifiedKeyStep(
                    label = label,
                    nodeId = candidate.nodeId,
                    imageOnly = candidate.imageOnly,
                    crop = crop,
                    method = check.method,
                )
                buildJsonObject {
                    put("label", label)
                    put("node_id", candidate.nodeId)
                    put("image_rect", JsonArray(listOf(crop.left, crop.top, crop.right, crop.bottom).map(::JsonPrimitive)))
                }
            })
            val autonomousProof = AutonomousKeyboardSequenceProof(
                nonce = nonceGenerator(),
                sessionId = sessionId,
                sessionEpoch = sessionEpoch,
                observationId = observationId,
                visualId = visualId,
                steps = proofSteps.toList(),
            )
            checkpointAt("key_1_dispatch", "guarded_visual_key_sequence")
            sequenceDispatchPending = true
            val sequenceResult = dispatchSequence(
                buildJsonObject {
                    put("session_id", sessionId)
                    put("observation_id", observationId)
                    put("visual_id", visualId)
                    put("visual_target_confirmed", false)
                    put("text", DebugOneStopQa.SixKey.expected(6))
                    put("reuse_key_map", false)
                    put("steps", steps)
                },
                autonomousProof,
            )

            val attempted = sequenceResult.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true
            val unknown = sequenceResult.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true
            val appVerified = sequenceResult.data["app_result_verified"]?.jsonPrimitive?.booleanOrNull == true
            val completed = (sequenceResult.data["completed_keys"]?.jsonPrimitive?.intOrNull ?: 0)
                .coerceIn(0, DebugOneStopQa.SixKey.labels.size)
            val fallbackSpaces = sequenceResult.data["confirmed_image_fallback_spaces"]?.jsonPrimitive?.intOrNull ?: 0
            val seqReason = sequenceResult.data["reason_code"]?.jsonPrimitive?.contentOrNull
            val obsReason = sequenceResult.data["observation_reason_code"]?.jsonPrimitive?.contentOrNull
            val pendingStepState = sequenceResult.data["pending_step_state"]?.jsonPrimitive?.contentOrNull
            val pendingKeyIndex = sequenceResult.data["pending_key_index"]?.jsonPrimitive?.intOrNull
                ?.takeIf { it in 1..DebugOneStopQa.SixKey.labels.size }
            if (!attempted && !unknown && completed == 0 &&
                pendingStepState != PendingKeyStepState.DISPATCHED_UNCONFIRMED.code) {
                sequenceDispatchPending = false
            }
            val seqSourceFailure = sequenceResult.data["visual_source_failure"] as? JsonObject
            val seqSourceStage = seqSourceFailure?.get("stage")?.jsonPrimitive?.contentOrNull
            val seqSourceCondition = seqSourceFailure?.get("condition")?.jsonPrimitive?.contentOrNull
            val seqWindowChanges = sequenceResult.captureWindowChanges
                ?: extractWindowEvents(seqSourceFailure)
            val (safeInnerDecisions, droppedInnerDecisions) = safeInnerRecoveryDecisions(
                sequenceResult.data["inner_recovery_decisions"] as? JsonArray,
            )
            val (safePostRefusals, droppedPostRefusals) = safePostCheckRefusals(
                sequenceResult.data["keyboard_post_check_refusals"] as? JsonArray,
            )
            val (safeAttemptEvidence, droppedAttemptEvidence) = safeAttemptFailureEvidence(
                sequenceResult.data["attempt_failure_evidence"] as? JsonArray,
            )
            evidence += buildJsonObject {
                put("source", "visual_key_sequence_result")
                put("confirmation_mode", "autonomous_debug_hybrid_structural_and_image")
                put("external_visual_review_confirmed", false)
                put("sequence_succeeded", sequenceResult.success)
                put("completed_keys", completed)
                put("action_attempted", attempted)
                put("outcome_unknown", unknown)
                put("app_result_verified", appVerified)
                put("confirmed_image_fallback_spaces", fallbackSpaces)
                put("read_only_recovery_attempts", recoveryBudget.used)
                put("recovery_budget_elapsed_ms", recoveryBudget.elapsedMs())
                put("recovery_episode_started", recoveryBudget.isEpisodeStarted())
                put("run_elapsed_ms", totalRunElapsedMs())
                pendingStepState?.let { put("pending_step_state", safeCode(it)) }
                pendingKeyIndex?.let { put("pending_key_index", it) }
                if (!sequenceResult.success) {
                    put("native_refusal_reason", safeCode(seqReason))
                    obsReason?.let { put("observation_reason_code", safeCode(it)) }
                    DebugKeyboardEvidence.safeDisplayRefusal(sequenceResult.displayPlanRefusal)?.let {
                        put("display_plan_refusal", it)
                    }
                    seqSourceStage?.let { put("visual_source_stage", safeCode(it)) }
                    seqSourceCondition?.let { put("visual_source_condition", safeCode(it)) }
                    if ((DebugKeyboardEvidence.shouldAttachWindowChanges(seqReason, seqSourceCondition) ||
                            DebugKeyboardEvidence.shouldAttachWindowChanges(obsReason, seqSourceCondition)) &&
                        seqWindowChanges != null) {
                        put("capture_window_changes", DebugKeyboardEvidence.safeWindowChanges(seqWindowChanges))
                    }
                    if ((seqReason == "visual_post_keyboard_context_changed" ||
                            obsReason == "visual_post_keyboard_context_changed") &&
                        sequenceResult.cursorProofMismatch != null) {
                        put("cursor_proof_mismatch",
                            DebugKeyboardEvidence.safeCursorProofMismatch(sequenceResult.cursorProofMismatch))
                    }
                }
                // Preserve bounded per-attempt recovery diagnostics even when the sequence succeeds!
                safeInnerDecisions?.let { put("inner_recovery_decisions", it) }
                if (droppedInnerDecisions > 0) put("inner_recovery_decisions_truncated_middle_count", droppedInnerDecisions)
                safePostRefusals?.let { put("keyboard_post_check_refusals", it) }
                if (droppedPostRefusals > 0) put("keyboard_post_check_refusals_truncated_middle_count", droppedPostRefusals)
                safeAttemptEvidence?.let { put("attempt_failure_evidence", it) }
                if (droppedAttemptEvidence > 0) put("attempt_failure_evidence_truncated_middle_count", droppedAttemptEvidence)
                sequenceResult.data["pixel_failure_stage"]?.jsonPrimitive?.contentOrNull?.let {
                    put("pixel_failure_stage", safeCode(it))
                }
                sequenceResult.data["pixel_failure_key"]?.jsonPrimitive?.intOrNull?.let {
                    put("pixel_failure_key", it)
                }
            }

            if (!sequenceResult.success &&
                evaluateAndClaimRecovery(
                    phase = "sequence_pre_dispatch",
                    reason = seqReason,
                    actionAttempted = attempted,
                    outcomeUnknown = unknown,
                    completedKeys = completed,
                    visualSourceStage = seqSourceStage,
                    visualSourceCondition = seqSourceCondition,
                    recoverableCursorProofRace = sequenceResult.cursorProofMismatch?.recoverableReadOnlyCursorRace == true,
                )) {
                // Confirmed pre-dispatch non-execution: discard ALL 4 certifications + old observation,
                // re-observe fresh frame, re-certify all 4 keys, and build a new proof + nonce.
                checkpointAt("key_1_preflight", "read_only_pre_dispatch_recovery")
                invalidateObservation()
                val fresh = captureFrameRecoverable("key_1_preflight")
                if (!authorityActive()) return@withTimeoutOrNull revokeAndFail(markUncertain = hasDispatchedMutation())
                if (fresh.reason == "six_key_sequence_deadline_exceeded" || isDeadlineExpired()) {
                    return@withTimeoutOrNull failDeadline("sequence_pre_dispatch_recovery", afterDispatch = hasDispatchedMutation())
                }
                val recertRefusal = recertifyEmptyEditor(before, fresh)
                    ?: if (NativeKeyboardStructuralCertification.classifyPhysicalLayout(fresh) != PhysicalKeyboardLayout.HANGUL) {
                        "korean_layout_changed_before_sequence"
                    } else null
                if (recertRefusal != null) return@withTimeoutOrNull fail(safeCode(recertRefusal))
                before = fresh
                continue
            }
            finalSequenceResult = sequenceResult
            break
        }

        val sequenceResult = finalSequenceResult
        val attempted = sequenceResult.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true
        val unknown = sequenceResult.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true
        val appVerified = sequenceResult.data["app_result_verified"]?.jsonPrimitive?.booleanOrNull == true
        val completed = (sequenceResult.data["completed_keys"]?.jsonPrimitive?.intOrNull ?: 0)
            .coerceIn(0, DebugOneStopQa.SixKey.labels.size)
        val seqReason = sequenceResult.data["reason_code"]?.jsonPrimitive?.contentOrNull
        val obsReason = sequenceResult.data["observation_reason_code"]?.jsonPrimitive?.contentOrNull
        val pendingStepState = sequenceResult.data["pending_step_state"]?.jsonPrimitive?.contentOrNull
        val pendingKeyIndex = sequenceResult.data["pending_key_index"]?.jsonPrimitive?.intOrNull
            ?.takeIf { it in 1..DebugOneStopQa.SixKey.labels.size }

        verified = completed
        attempts = when {
            completed > 0 && pendingStepState == PendingKeyStepState.DISPATCHED_UNCONFIRMED.code ->
                (completed + 1).coerceAtMost(DebugOneStopQa.SixKey.labels.size)
            completed > 0 -> completed
            attempted -> 1
            else -> 0
        }
        stage = when (pendingStepState) {
            PendingKeyStepState.FINAL_READ.code -> "six_key_final"
            PendingKeyStepState.DISPATCHED_UNCONFIRMED.code ->
                pendingKeyIndex?.let { "key_${it}_dispatch" } ?: "key_1_dispatch"
            PendingKeyStepState.NOT_DISPATCHED.code ->
                pendingKeyIndex?.let { "key_${it}_preflight" } ?: "key_1_preflight"
            PendingKeyStepState.CONFIRMED.code ->
                if (completed >= DebugOneStopQa.SixKey.labels.size) "six_key_final"
                else pendingKeyIndex?.let { "key_${it}_confirmed" } ?: stage
            else -> stage
        }
        if (!sequenceResult.success || unknown || !attempted || !appVerified ||
            completed != DebugOneStopQa.SixKey.labels.size) {
            val noTouchAtAll = completed == 0 && !attempted && !unknown &&
                pendingStepState != PendingKeyStepState.DISPATCHED_UNCONFIRMED.code
            val authorityRevokedResult = !authorityActive() || seqReason in setOf(
                "control_session_changed_observe_again",
                "screen_share_revoked",
                "keyboard_sequence_window_recheck_consent_changed",
            )
            if (authorityRevokedResult) {
                invalidateObservation()
                uncertain = true
                return@withTimeoutOrNull fail(
                    if (!authorityActive() && seqReason == null) "control_authority_revoked"
                    else if (!authorityActive() && seqReason !in setOf("control_session_changed_observe_again", "screen_share_revoked", "keyboard_sequence_window_recheck_consent_changed")) "control_authority_revoked"
                    else safeCode(seqReason),
                )
            }
            if (seqReason == "six_key_sequence_deadline_exceeded" || isDeadlineExpired()) {
                return@withTimeoutOrNull failDeadline(
                    phase = stage,
                    afterDispatch = hasDispatchedMutation() || !noTouchAtAll || unknown,
                )
            }
            if (noTouchAtAll) {
                // Zero touches dispatched across the entire sequence -> terminal BLOCKED.
            } else if (completed in 1 until DebugOneStopQa.SixKey.labels.size &&
                pendingStepState == PendingKeyStepState.NOT_DISPATCHED.code &&
                !unknown && !uncertain && authorityActive() && !isDeadlineExpired()) {
                if (!mayVerifyPartialPrefixAfter(seqReason, obsReason)) {
                    // Refusal may indicate security/privacy/unowned-window/lock/revocation/unknown cause:
                    // do NOT perform extra observation; stay conservatively UNKNOWN.
                    uncertain = true
                    evidence += buildJsonObject {
                        put("source", "partial_prefix_verification")
                        put("completed_keys", completed)
                        put("pending_step_state", safeCode(pendingStepState))
                        pendingKeyIndex?.let { put("pending_key_index", it) }
                        put("verified", false)
                        put("skipped_unsafe_or_unknown_refusal", true)
                        put("authority_active", authorityActive())
                    }
                } else {
                    // Partial known prefix with explicit NOT_DISPATCHED on a safe allowlisted refusal:
                    // downgrade UNKNOWN -> BLOCKED ONLY after an independent fresh read proves exact
                    // touchContext, keyboard, completeness, same-editor identity, prefix, and caret.
                    val prefixCheck = capturePostconditionReadOnly()
                    val prefixTarget = prefixCheck.inputTarget
                    val prefixVerified = authorityActive() && !isDeadlineExpired() &&
                        prefixCheck.reason == null && !prefixCheck.truncated && !prefixCheck.navigationOnly &&
                        prefixCheck.packageName == "com.android.chrome" &&
                        prefixCheck.touchContext.isNotEmpty() && prefixCheck.touchContext == before.touchContext &&
                        prefixCheck.keyboard == before.keyboard &&
                        prefixTarget != null && prefixTarget.focused && prefixTarget.editable &&
                        prefixTarget.focusable && prefixTarget.enabled && !prefixTarget.password &&
                        DebugOneStopQa.sameEditor(first, prefixCheck) &&
                        KeyboardEditorContinuity.sameFocusedEditor(before, prefixCheck, allowAnonymousResize = true) &&
                        DebugOneStopQa.SixKey.matches(
                            prefixTarget.text, prefixTarget.selectionStart, prefixTarget.selectionEnd, completed,
                        )
                    evidence += buildJsonObject {
                        put("source", "partial_prefix_verification")
                        put("completed_keys", completed)
                        put("pending_step_state", safeCode(pendingStepState))
                        pendingKeyIndex?.let { put("pending_key_index", it) }
                        put("verified", prefixVerified)
                        put("skipped_unsafe_or_unknown_refusal", false)
                        put("touch_context_matched", prefixCheck.touchContext.isNotEmpty() && prefixCheck.touchContext == before.touchContext)
                        put("authority_active", authorityActive())
                        prefixCheck.reason?.let { put("read_reason_code", safeCode(it)) }
                    }
                    if (!prefixVerified) uncertain = true
                }
            } else {
                uncertain = true
            }
            return@withTimeoutOrNull fail(if (seqReason != null) safeCode(seqReason) else "visual_key_sequence_unconfirmed")
        }

        stage = "six_key_final"
        if (isDeadlineExpired()) return@withTimeoutOrNull failDeadline("six_key_final", afterDispatch = true)
        val final = capturePostconditionReadOnly()
        if (!authorityActive()) return@withTimeoutOrNull revokeAndFail(markUncertain = attempts > 0)
        if (final.reason == "six_key_sequence_deadline_exceeded" || isDeadlineExpired()) {
            return@withTimeoutOrNull failDeadline("six_key_final", afterDispatch = true)
        }
        if (final.reason != null || final.truncated || final.navigationOnly ||
            final.packageName != "com.android.chrome") {
            uncertain = attempts > 0
            return@withTimeoutOrNull fail("six_key_final_read_unconfirmed")
        }
        val post = final.inputTarget
        if (post == null || !post.focused || !post.editable || !post.focusable || !post.enabled || post.password ||
            !DebugOneStopQa.sameEditor(first, final) ||
            !KeyboardEditorContinuity.sameFocusedEditor(before, final, allowAnonymousResize = true)) {
            uncertain = attempts > 0
            return@withTimeoutOrNull fail("final_editor_identity_unverified")
        }
        if (!DebugOneStopQa.SixKey.matches(post.text, post.selectionStart, post.selectionEnd, 6)) {
            uncertain = attempts > 0
            return@withTimeoutOrNull fail("six_key_final_read_unconfirmed")
        }
        // Linearization point: commit PASS only if exact active session token & authority hold atomically.
        if (!commitPass()) return@withTimeoutOrNull revokeAndFail(markUncertain = attempts > 0)
        Outcome(
            progress(), null, evidence.toList(), observationMs, extraReads, afterFailure, followUpEvidence,
            initialLayoutCode, finalLayoutCode, layoutSwitchAttempts, layoutSwitchConfirmed,
            recoveryBudget.used,
        )
        } ?: failDeadline(
            phase = stage,
            afterDispatch = hasDispatchedMutation(),
        )
    }

    suspend fun run(
        port: AndroidControlPort, control: GenericControl,
        session: ControlSession, first: ControlFrame, allowed: () -> Boolean,
        ownedToken: ControlSession.ActiveToken? = null,
        checkpoint: (Progress, String) -> Unit,
    ): Outcome {
        val anchorToken = ownedToken ?: ControlSession.ActiveToken(session.id, session.epoch)
        val sharedBudget = SharedQaRecoveryBudget(clockMs = { SystemClock.elapsedRealtime() })
        val runStartedAtMs = SystemClock.elapsedRealtime()
        val deadlineExpired: () -> Boolean = {
            (SystemClock.elapsedRealtime() - runStartedAtMs).coerceAtLeast(0L) >= DEFAULT_TOTAL_SEQUENCE_DEADLINE_MS
        }
        return runWithSeam(
            captureFrame = { port.capture() },
            observeSequence = { labels ->
                port.resetDebugCaptureEvidence()
                val res = control.observe(
                    includeImage = true,
                    imageScope = "keyboard",
                    keyboardSequenceLabels = labels,
                )
                val obsId = res.data["observation_id"]?.jsonPrimitive?.contentOrNull
                val visId = (res.data["visual"] as? JsonObject)?.get("visual_id")?.jsonPrimitive?.contentOrNull
                val lookup = if (res.success && !obsId.isNullOrBlank() && !visId.isNullOrBlank()) {
                    control.certifiedKeyboardObservationDetailed(obsId, visId, session.epoch)
                } else null
                val srcFailure = lookup?.visualSourceFailure
                ObservePacket(
                    success = res.success,
                    data = res.data,
                    certifiedFrame = lookup?.certified?.frame,
                    displayPlanRefusal = if (res.success) null else port.debugDisplayPlanRefusal(),
                    displayWindowCensus = if (res.success) null else port.debugDisplayWindowCensus(),
                    captureWindowChanges = if (res.success) {
                        lookup?.captureWindowChanges ?: srcFailure?.windowEvents
                    } else {
                        port.debugCaptureWindowChangeEvidence()
                    },
                    visualSourceStage = srcFailure?.stage?.code,
                    visualSourceCondition = srcFailure?.condition?.code,
                    cursorProofMismatch = if (res.success) null else port.visualKeyboardContextMismatch(),
                )
            },
            certifyCandidate = { visualId, certifiedFrame, candidate, requiredNativeLabels ->
                port.certifyKeyboardCandidatePixelsDetailed(
                    visualId = visualId,
                    frame = certifiedFrame,
                    nodeId = candidate.nodeId,
                    nodeBoundsRaw = candidate.bounds,
                    imageOnly = candidate.imageOnly,
                    expectedLabel = candidate.label,
                    requiredNativeLabels = requiredNativeLabels,
                )
            },
            dispatchSequence = { payload, autonomousProof ->
                port.resetDebugCaptureEvidence()
                val res = control.visualKeySequenceAutonomous(
                    payload,
                    autonomousProof,
                    recoveryBudget = sharedBudget,
                    deadlineExpired = deadlineExpired,
                )
                val sourceFailure = res.data["visual_source_failure"] as? JsonObject
                val windowChanges = if (res.success) null else {
                    extractWindowEvents(sourceFailure) ?: port.debugCaptureWindowChangeEvidence()
                }
                SequencePacket(
                    success = res.success,
                    data = res.data,
                    captureWindowChanges = windowChanges,
                    cursorProofMismatch = if (res.success) null else port.visualKeyboardContextMismatch(),
                    displayPlanRefusal = if (res.success) null else port.debugDisplayPlanRefusal(),
                )
            },
            sessionId = anchorToken.id,
            sessionEpoch = anchorToken.epoch,
            first = first,
            allowed = allowed,
            checkpoint = checkpoint,
            switchToKoreanLayout = { beforeFrame, candidate, nonce ->
                val ime = beforeFrame.keyboard
                when (candidate.route) {
                    KoreanLayoutSwitchRoute.SAMSUNG_VISUAL_KEY -> {
                        port.resetDebugCaptureEvidence()
                        val observed = control.observe(
                            includeImage = true,
                            imageScope = "keyboard",
                            keyboardKeyExact = "한/영",
                        )
                        val obsId = observed.data["observation_id"]?.jsonPrimitive?.contentOrNull
                        val vis = observed.data["visual"] as? JsonObject
                        val visId = vis?.get("visual_id")?.jsonPrimitive?.contentOrNull
                        val visScope = vis?.get("scope")?.jsonPrimitive?.contentOrNull
                        val nodes = (observed.data["nodes"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                        val matchedNode = nodes.filter { node ->
                            node["enabled"]?.jsonPrimitive?.booleanOrNull == true &&
                                node["source_window_id"]?.jsonPrimitive?.intOrNull == ime?.windowId &&
                                node["keyboard_key"]?.jsonPrimitive?.contentOrNull == "한/영" &&
                                node["visual_key_candidate"]?.jsonPrimitive?.booleanOrNull == true &&
                                node["keyboard_image_candidate"]?.jsonPrimitive?.booleanOrNull != true &&
                                node["bounds"]?.jsonPrimitive?.contentOrNull == candidate.node.bounds
                        }.singleOrNull()
                        val nodeId = matchedNode?.get("node_id")?.jsonPrimitive?.intOrNull
                        val certLookup = if (observed.success && !obsId.isNullOrBlank() && !visId.isNullOrBlank()) {
                            control.certifiedKeyboardObservationDetailed(obsId, visId, session.epoch)
                        } else null
                        val certifiedFrame = certLookup?.certified?.frame
                        val recertRefusal = certifiedFrame?.let {
                            DisplayObservationRecoveryPolicy.recertificationRefusal(beforeFrame, it)
                        }
                        if (!observed.success ||
                            observed.data["security_scan_complete"]?.jsonPrimitive?.booleanOrNull != true ||
                            observed.data["output_truncated"]?.jsonPrimitive?.booleanOrNull != false ||
                            visScope != "keyboard" || obsId.isNullOrBlank() || visId.isNullOrBlank() ||
                            nodeId == null || certifiedFrame == null ||
                            recertRefusal != null ||
                            !DebugOneStopQa.sameEditor(first, certifiedFrame) ||
                            certifiedFrame.keyboard != ime) {
                            val certSrcFailure = certLookup?.visualSourceFailure
                            val reason = when {
                                !observed.success -> observed.data["reason_code"]?.jsonPrimitive?.contentOrNull
                                    ?: "korean_layout_switch_observation_unverified"
                                certSrcFailure != null -> VISUAL_SOURCE_REFUSED
                                else -> recertRefusal ?: "korean_layout_switch_observation_unverified"
                            }
                            LayoutSwitchPacket(
                                success = false, attempted = false, unknown = false,
                                reason = reason,
                                visualSourceStage = certSrcFailure?.stage?.code,
                                visualSourceCondition = certSrcFailure?.condition?.code,
                                displayPlanRefusal = if (observed.success) null else port.debugDisplayPlanRefusal(),
                                displayWindowCensus = if (observed.success) null else port.debugDisplayWindowCensus(),
                                captureWindowChanges = if (!observed.success) {
                                    port.debugCaptureWindowChangeEvidence()
                                } else {
                                    certLookup?.captureWindowChanges ?: certSrcFailure?.windowEvents
                                },
                                cursorProofMismatch = if (observed.success) null else port.visualKeyboardContextMismatch(),
                            )
                        } else {
                            val cert = port.certifyKoreanLayoutSwitchDetailed(
                                visualId = visId,
                                frame = certifiedFrame,
                                nodeId = nodeId,
                                nodeBoundsRaw = candidate.node.bounds,
                            )
                            val crop = cert.crop?.takeIf { it.valid }
                            if (crop == null || cert.method != "native_layout_switch_and_live_surface") {
                                val certFailure = cert.visualSourceFailure
                                LayoutSwitchPacket(
                                    success = false, attempted = false, unknown = false,
                                    reason = if (certFailure != null) VISUAL_SOURCE_REFUSED
                                    else cert.failureStage ?: "korean_layout_switch_certification_unverified",
                                    visualSourceStage = certFailure?.stage?.code,
                                    visualSourceCondition = certFailure?.condition?.code,
                                    captureWindowChanges = cert.captureWindowChanges ?: certFailure?.windowEvents,
                                )
                            } else {
                                val proof = AutonomousKeyboardSequenceProof(
                                    nonce = nonce,
                                    sessionId = session.id,
                                    sessionEpoch = session.epoch,
                                    observationId = obsId,
                                    visualId = visId,
                                    steps = listOf(
                                        AutonomousCertifiedKeyStep(
                                            label = "한/영",
                                            nodeId = nodeId,
                                            imageOnly = false,
                                            crop = crop,
                                            method = cert.method,
                                        ),
                                    ),
                                )
                                val res = control.visualKoreanLayoutSwitchAutonomous(
                                    buildJsonObject {
                                        put("session_id", session.id)
                                        put("observation_id", obsId)
                                        put("visual_id", visId)
                                        put("visual_target_confirmed", false)
                                        put("node_id", nodeId)
                                    },
                                    proof,
                                )
                                val sourceFailure = res.data["visual_source_failure"] as? JsonObject
                                val reason = res.data["reason_code"]?.jsonPrimitive?.contentOrNull
                                LayoutSwitchPacket(
                                    success = res.success &&
                                        res.data["layout_switch_confirmed"]?.jsonPrimitive?.booleanOrNull == true,
                                    attempted = res.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true,
                                    unknown = res.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true,
                                    reason = reason,
                                    visualSourceStage = sourceFailure?.get("stage")?.jsonPrimitive?.contentOrNull,
                                    visualSourceCondition = sourceFailure?.get("condition")?.jsonPrimitive?.contentOrNull,
                                    displayPlanRefusal = if (res.success) null else port.debugDisplayPlanRefusal(),
                                    displayWindowCensus = if (res.success) null else port.debugDisplayWindowCensus(),
                                    captureWindowChanges = if (res.success) null else {
                                        extractWindowEvents(sourceFailure)
                                            ?: port.debugCaptureWindowChangeEvidence()
                                    },
                                    cursorProofMismatch = if (res.success) null else port.visualKeyboardContextMismatch(),
                                )
                            }
                        }
                    }
                    KoreanLayoutSwitchRoute.SEMANTIC_KEY -> {
                        val observed = control.observe(keyboardKeyExact = candidate.node.keyboardKey)
                        val obsId = observed.data["observation_id"]?.jsonPrimitive?.contentOrNull
                        val nodes = (observed.data["nodes"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                        val matchedNode = nodes.filter { node ->
                            node["enabled"]?.jsonPrimitive?.booleanOrNull == true &&
                                node["clickable"]?.jsonPrimitive?.booleanOrNull == true &&
                                node["source_window_id"]?.jsonPrimitive?.intOrNull == ime?.windowId &&
                                node["keyboard_key"]?.jsonPrimitive?.contentOrNull == candidate.node.keyboardKey &&
                                node["bounds"]?.jsonPrimitive?.contentOrNull == candidate.node.bounds
                        }.singleOrNull()
                        val nodeId = matchedNode?.get("node_id")?.jsonPrimitive?.intOrNull
                        if (!observed.success ||
                            observed.data["security_scan_complete"]?.jsonPrimitive?.booleanOrNull != true ||
                            observed.data["output_truncated"]?.jsonPrimitive?.booleanOrNull != false ||
                            obsId.isNullOrBlank() || nodeId == null) {
                            LayoutSwitchPacket(
                                success = false, attempted = false, unknown = false,
                                reason = observed.data["reason_code"]?.jsonPrimitive?.contentOrNull
                                    ?: "korean_layout_switch_observation_unverified",
                            )
                        } else {
                            val res = control.action(buildJsonObject {
                                put("action", "key")
                                put("session_id", session.id)
                                put("observation_id", obsId)
                                put("node_id", nodeId)
                            })
                            LayoutSwitchPacket(
                                success = res.success,
                                attempted = res.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true,
                                unknown = res.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true,
                                reason = res.data["reason_code"]?.jsonPrimitive?.contentOrNull,
                            )
                        }
                    }
                }
            },
            invalidateObservation = { control.invalidate() },
            recoveryBudget = sharedBudget,
            sessionCurrent = { session.isCurrentActive(anchorToken) },
            commitPass = { session.commitIfCurrentActive(anchorToken) { allowed() } == true },
        )
    }
}
