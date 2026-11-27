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
import ai.closepaw.chatgpt.control.KoreanLayoutSwitchCandidate
import ai.closepaw.chatgpt.control.KoreanLayoutSwitchRoute
import ai.closepaw.chatgpt.control.NativeKeyboardStructuralCertification
import ai.closepaw.chatgpt.control.PhysicalKeyboardLayout
import ai.closepaw.chatgpt.control.PixelCertificationOutcome
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
    )

    private fun safeCode(value: String?): String =
        value?.takeIf { it.length in 1..80 && it.matches(Regex("[a-z0-9_]+")) }
            ?: "unavailable"

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
    ): Outcome {
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
        val evidence = mutableListOf<JsonObject>()
        fun progress() = Progress(verified, attempts, uncertain, stage)
        fun fail(reason: String) = Outcome(
            progress(), reason, evidence.toList(), observationMs,
            extraReads, afterFailure, followUpEvidence,
            initialLayoutCode, finalLayoutCode, layoutSwitchAttempts, layoutSwitchConfirmed,
            recoveryBudget.used,
        )
        fun checkpointAt(next: String, reason: String) {
            stage = next
            checkpoint(progress(), reason)
        }
        suspend fun captureFrameRecoverable(stageName: String): ControlFrame {
            var frame = captureFrame()
            while (frame.reason != null &&
                DisplayObservationRecoveryPolicy.isRecoverableRecaptureReason(frame.reason) &&
                allowed() && recoveryBudget.tryClaim()
            ) {
                checkpointAt(stageName, "read_only_recapture_recovery")
                invalidateObservation()
                frame = captureFrame()
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

        currentCoroutineContext().ensureActive()
        if (!allowed()) return fail("control_authority_revoked")
        checkpointAt("key_1_preflight", "fresh_keyboard_sequence_observation")

        var before = captureFrame()
        if (!DebugOneStopQa.sameEditor(first, before)) return fail("editor_identity_changed")
        val field = before.inputTarget
        if (before.reason != null || before.packageName != "com.android.chrome" ||
            before.keyboard?.packageName != "com.samsung.android.honeyboard" || field == null ||
            !DebugOneStopQa.SixKey.matches(field.text, field.selectionStart, field.selectionEnd, 0))
            return fail("editor_checkpoint_changed")

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
                return fail("korean_layout_switch_unsupported_page")
            }
            PhysicalKeyboardLayout.UNVERIFIED -> {
                recordLayoutEvidence("none", "korean_layout_unverified")
                return fail("korean_layout_unverified")
            }
            PhysicalKeyboardLayout.LATIN -> {
                var switchCandidate = NativeKeyboardStructuralCertification.findUniqueKoreanLayoutSwitchCandidate(before)
                    ?: run {
                        recordLayoutEvidence("none", "korean_layout_switch_unavailable")
                        return fail("korean_layout_switch_unavailable")
                    }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!allowed()) {
                        recordLayoutEvidence(switchCandidate.route.code, "control_authority_revoked")
                        return fail("control_authority_revoked")
                    }
                    checkpointAt("layout_readiness_switch_dispatch", "guarded_korean_layout_switch")
                    val switched = switchToKoreanLayout(before, switchCandidate, nonceGenerator())
                    if (switched.attempted) layoutSwitchAttempts = 1
                    if (switched.success && switched.attempted && !switched.unknown) break
                    val refusal = safeCode(switched.reason ?: "korean_layout_switch_unconfirmed")
                    recordLayoutEvidence(switchCandidate.route.code, refusal, switched)
                    if (switched.attempted || switched.unknown) {
                        uncertain = true
                        return fail(refusal)
                    }
                    if (!DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
                            reason = switched.reason,
                            recoveriesUsed = recoveryBudget.used,
                            actionAttempted = switched.attempted,
                            outcomeUnknown = switched.unknown,
                            authorityActive = allowed(),
                            visualSourceStage = switched.visualSourceStage,
                            visualSourceCondition = switched.visualSourceCondition,
                            recoverableCursorProofRace = switched.cursorProofMismatch?.recoverableReadOnlyCursorRace == true,
                        ) || !recoveryBudget.tryClaim()) {
                        return fail(refusal)
                    }
                    // Confirmed pre-dispatch non-execution: discard stale observation, acquire fresh
                    // observation, re-authenticate target/editor/IME/layout, and re-resolve switch key.
                    checkpointAt("layout_readiness_recovery", "read_only_capture_recovery")
                    invalidateObservation()
                    val fresh = captureFrameRecoverable("layout_readiness_recovery")
                    if (!allowed()) {
                        recordLayoutEvidence(switchCandidate.route.code, "control_authority_revoked")
                        return fail("control_authority_revoked")
                    }
                    val recertRefusal = recertifyEmptyEditor(before, fresh)
                    if (recertRefusal != null) {
                        val safe = safeCode(recertRefusal)
                        recordLayoutEvidence(switchCandidate.route.code, safe)
                        return fail(safe)
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
                        return fail(layoutRefusal)
                    }
                    val freshCandidate = NativeKeyboardStructuralCertification.findUniqueKoreanLayoutSwitchCandidate(fresh)
                    if (freshCandidate == null) {
                        recordLayoutEvidence("none", "korean_layout_switch_unavailable")
                        return fail("korean_layout_switch_unavailable")
                    }
                    before = fresh
                    switchCandidate = freshCandidate
                }
                if (!allowed()) {
                    uncertain = true
                    recordLayoutEvidence(switchCandidate.route.code, "control_authority_revoked")
                    return fail("control_authority_revoked")
                }
                val afterSwitch = captureFrame()
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
                    return fail("korean_layout_post_switch_unverified")
                }
                layoutSwitchConfirmed = true
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
            if (!allowed()) return fail("control_authority_revoked")
            val started = clockMs()
            val observed = observeSequence(sequenceLabels)
            observationMs += (clockMs() - started).coerceAtLeast(0L)
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
                if (!DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
                        reason = obsReason,
                        recoveriesUsed = recoveryBudget.used,
                        actionAttempted = obsAttempted,
                        outcomeUnknown = obsUnknown,
                        authorityActive = allowed(),
                        visualSourceStage = observed.visualSourceStage,
                        visualSourceCondition = observed.visualSourceCondition,
                        observationOnly = true,
                        recoverableCursorProofRace = observed.cursorProofMismatch?.recoverableReadOnlyCursorRace == true,
                    ) || !recoveryBudget.tryClaim()) {
                    // A refusal could mean protected content, overlay, or revoked consent.
                    // Do not collect another frame after that refusal.
                    afterFailure = "skipped_refused_observation"
                    return fail("keyboard_observation_refused")
                }
                checkpointAt("key_1_preflight", "read_only_observation_recovery")
                invalidateObservation()
                val fresh = captureFrameRecoverable("key_1_preflight")
                if (!allowed()) return fail("control_authority_revoked")
                val recertRefusal = recertifyEmptyEditor(before, fresh)
                    ?: if (NativeKeyboardStructuralCertification.classifyPhysicalLayout(fresh) != PhysicalKeyboardLayout.HANGUL) {
                        "korean_layout_changed_before_sequence"
                    } else null
                if (recertRefusal != null) {
                    afterFailure = "skipped_refused_observation"
                    return fail(safeCode(recertRefusal))
                }
                before = fresh
                continue
            }

            val observationId = observed.data["observation_id"]?.jsonPrimitive?.contentOrNull
                ?: return fail("observation_id_missing")
            val visualId = (observed.data["visual"] as? JsonObject)?.get("visual_id")?.jsonPrimitive?.contentOrNull
            val candidates = resolveUniqueSequenceCandidates(observed.data, sequenceLabels)
            if (visualId.isNullOrBlank() || candidates == null) {
                val followUp = DebugKeyboardEvidence.inspectAmbiguousCandidates(
                    capture = captureFrame, baseline = first, keyIndex = 0, allowed = allowed,
                )
                afterFailure = followUp.status
                followUpEvidence = followUp.evidence
                extraReads += followUp.reads
                return fail("unique_physical_key_not_proven")
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
                if (DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
                        reason = VISUAL_SOURCE_REFUSED,
                        recoveriesUsed = recoveryBudget.used,
                        actionAttempted = false,
                        outcomeUnknown = false,
                        authorityActive = allowed(),
                        visualSourceStage = observed.visualSourceStage,
                        visualSourceCondition = observed.visualSourceCondition,
                    ) && recoveryBudget.tryClaim()) {
                    checkpointAt("key_1_preflight", "read_only_certified_observation_recovery")
                    invalidateObservation()
                    val fresh = captureFrameRecoverable("key_1_preflight")
                    if (!allowed()) return fail("control_authority_revoked")
                    val recertRefusal = recertifyEmptyEditor(before, fresh)
                        ?: if (NativeKeyboardStructuralCertification.classifyPhysicalLayout(fresh) != PhysicalKeyboardLayout.HANGUL) {
                            "korean_layout_changed_before_sequence"
                        } else null
                    if (recertRefusal != null) return fail(safeCode(recertRefusal))
                    before = fresh
                    continue
                }
                return fail("certified_observation_unavailable")
            }
            if (!DebugOneStopQa.sameEditor(first, certifiedFrame) || certifiedFrame.keyboard != before.keyboard) {
                return fail("editor_identity_changed")
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
                if (!allowed()) return fail("control_authority_revoked")
                val check = certifyCandidate(visualId, certifiedFrame, candidate, requiredNativeLabels)
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
                    DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
                        reason = VISUAL_SOURCE_REFUSED,
                        recoveriesUsed = recoveryBudget.used,
                        actionAttempted = false,
                        outcomeUnknown = false,
                        authorityActive = allowed(),
                        visualSourceStage = certDisplayFailure.stage.code,
                        visualSourceCondition = certDisplayFailure.condition.code,
                    ) && recoveryBudget.tryClaim()) {
                    // Discard ALL 4 certifications + old observation; re-observe and re-certify fresh.
                    checkpointAt("key_1_preflight", "read_only_certification_recovery")
                    invalidateObservation()
                    val fresh = captureFrameRecoverable("key_1_preflight")
                    if (!allowed()) return fail("control_authority_revoked")
                    val recertRefusal = recertifyEmptyEditor(before, fresh)
                        ?: if (NativeKeyboardStructuralCertification.classifyPhysicalLayout(fresh) != PhysicalKeyboardLayout.HANGUL) {
                            "korean_layout_changed_before_sequence"
                        } else null
                    if (recertRefusal != null) return fail(safeCode(recertRefusal))
                    before = fresh
                    continue
                }
                val followUp = DebugKeyboardEvidence.inspectAmbiguousCandidates(
                    capture = captureFrame, baseline = first, keyIndex = 0, allowed = allowed,
                )
                afterFailure = followUp.status
                followUpEvidence = followUp.evidence
                extraReads += followUp.reads
                return fail("autonomous_keyboard_certification_unverified")
            }

            if (!allowed()) return fail("control_authority_revoked")
            val proofSteps = mutableListOf<AutonomousCertifiedKeyStep>()
            val steps = JsonArray(DebugOneStopQa.SixKey.labels.map { label ->
                val (candidate, check) = certifiedStepsByLabel[label]
                    ?: return fail("autonomous_keyboard_certification_unverified")
                val crop = check.crop ?: return fail("autonomous_keyboard_certification_unverified")
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
            val seqSourceFailure = sequenceResult.data["visual_source_failure"] as? JsonObject
            val seqSourceStage = seqSourceFailure?.get("stage")?.jsonPrimitive?.contentOrNull
            val seqSourceCondition = seqSourceFailure?.get("condition")?.jsonPrimitive?.contentOrNull
            val seqWindowChanges = sequenceResult.captureWindowChanges
                ?: (seqSourceFailure?.get("window_events") as? JsonObject)
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
                if (!sequenceResult.success) {
                    put("native_refusal_reason", safeCode(seqReason))
                    seqSourceStage?.let { put("visual_source_stage", safeCode(it)) }
                    seqSourceCondition?.let { put("visual_source_condition", safeCode(it)) }
                    if (DebugKeyboardEvidence.shouldAttachWindowChanges(seqReason, seqSourceCondition) &&
                        seqWindowChanges != null) {
                        put("capture_window_changes", DebugKeyboardEvidence.safeWindowChanges(seqWindowChanges))
                    }
                    if (seqReason == "visual_post_keyboard_context_changed" &&
                        sequenceResult.cursorProofMismatch != null) {
                        put("cursor_proof_mismatch",
                            DebugKeyboardEvidence.safeCursorProofMismatch(sequenceResult.cursorProofMismatch))
                    }
                }
                sequenceResult.data["pixel_failure_stage"]?.jsonPrimitive?.contentOrNull?.let {
                    put("pixel_failure_stage", safeCode(it))
                }
                sequenceResult.data["pixel_failure_key"]?.jsonPrimitive?.intOrNull?.let {
                    put("pixel_failure_key", it)
                }
            }

            if (!sequenceResult.success && !attempted && !unknown && completed == 0 &&
                DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
                    reason = seqReason,
                    recoveriesUsed = recoveryBudget.used,
                    actionAttempted = false,
                    outcomeUnknown = false,
                    authorityActive = allowed(),
                    visualSourceStage = seqSourceStage,
                    visualSourceCondition = seqSourceCondition,
                    recoverableCursorProofRace = sequenceResult.cursorProofMismatch?.recoverableReadOnlyCursorRace == true,
                ) && recoveryBudget.tryClaim()) {
                // Confirmed pre-dispatch non-execution: discard ALL 4 certifications + old observation,
                // re-observe fresh frame, re-certify all 4 keys, and build a new proof + nonce.
                checkpointAt("key_1_preflight", "read_only_pre_dispatch_recovery")
                invalidateObservation()
                val fresh = captureFrameRecoverable("key_1_preflight")
                if (!allowed()) return fail("control_authority_revoked")
                val recertRefusal = recertifyEmptyEditor(before, fresh)
                    ?: if (NativeKeyboardStructuralCertification.classifyPhysicalLayout(fresh) != PhysicalKeyboardLayout.HANGUL) {
                        "korean_layout_changed_before_sequence"
                    } else null
                if (recertRefusal != null) return fail(safeCode(recertRefusal))
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

        attempts = if (attempted) maxOf(completed, 1) else 0
        verified = completed
        uncertain = uncertain || unknown || (attempted && !sequenceResult.success)
        if (!sequenceResult.success || unknown || !attempted || !appVerified ||
            completed != DebugOneStopQa.SixKey.labels.size) {
            return fail(if (seqReason != null) safeCode(seqReason) else "visual_key_sequence_unconfirmed")
        }

        stage = "six_key_final"
        val final = captureFrame()
        val post = final.inputTarget
        if (!DebugOneStopQa.sameEditor(first, final)) {
            uncertain = attempts > 0
            return fail("final_editor_identity_unverified")
        }
        if (final.reason != null || final.packageName != "com.android.chrome" || post == null ||
            !DebugOneStopQa.SixKey.matches(post.text, post.selectionStart, post.selectionEnd, 6)) {
            uncertain = attempts > 0
            return fail("six_key_final_read_unconfirmed")
        }
        return Outcome(
            progress(), null, evidence.toList(), observationMs, extraReads, afterFailure, followUpEvidence,
            initialLayoutCode, finalLayoutCode, layoutSwitchAttempts, layoutSwitchConfirmed,
            recoveryBudget.used,
        )
    }

    suspend fun run(
        port: AndroidControlPort, control: GenericControl,
        session: ControlSession, first: ControlFrame, allowed: () -> Boolean,
        checkpoint: (Progress, String) -> Unit,
    ): Outcome {
        val sharedBudget = SharedQaRecoveryBudget(clockMs = { SystemClock.elapsedRealtime() })
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
                    payload, autonomousProof, recoveryBudget = sharedBudget,
                )
                val sourceFailure = res.data["visual_source_failure"] as? JsonObject
                val windowChanges = if (res.success) null else {
                    (sourceFailure?.get("window_events") as? JsonObject) ?: port.debugCaptureWindowChangeEvidence()
                }
                SequencePacket(
                    success = res.success,
                    data = res.data,
                    captureWindowChanges = windowChanges,
                    cursorProofMismatch = if (res.success) null else port.visualKeyboardContextMismatch(),
                )
            },
            sessionId = session.id,
            sessionEpoch = session.epoch,
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
                                        (sourceFailure?.get("window_events") as? JsonObject)
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
        )
    }
}
