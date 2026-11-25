package ai.closepaw.qa

import ai.closepaw.chatgpt.control.AndroidControlPort
import ai.closepaw.chatgpt.control.AutonomousCertifiedKeyStep
import ai.closepaw.chatgpt.control.AutonomousKeyboardSequenceProof
import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.chatgpt.control.DisplayWindowCensus
import ai.closepaw.chatgpt.control.GenericControl
import ai.closepaw.chatgpt.control.PixelCertificationOutcome
import ai.closepaw.chatgpt.control.TapBounds
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
    )

    internal data class SequencePacket(
        val success: Boolean,
        val data: JsonObject,
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
    ): Outcome {
        var verified = 0
        var attempts = 0
        var uncertain = false
        var stage = "key_1_preflight"
        var observationMs = 0L
        var extraReads = 0
        var afterFailure = "not_required"
        var followUpEvidence: JsonObject? = null
        val evidence = mutableListOf<JsonObject>()
        fun progress() = Progress(verified, attempts, uncertain, stage)
        fun fail(reason: String) = Outcome(
            progress(), reason, evidence.toList(), observationMs,
            extraReads, afterFailure, followUpEvidence,
        )
        fun checkpointAt(next: String, reason: String) {
            stage = next
            checkpoint(progress(), reason)
        }

        currentCoroutineContext().ensureActive()
        if (!allowed()) return fail("control_authority_revoked")
        checkpointAt("key_1_preflight", "fresh_keyboard_sequence_observation")

        val before = captureFrame()
        if (!DebugOneStopQa.sameEditor(first, before)) return fail("editor_identity_changed")
        val field = before.inputTarget
        if (before.reason != null || before.packageName != "com.android.chrome" ||
            before.keyboard?.packageName != "com.samsung.android.honeyboard" || field == null ||
            !DebugOneStopQa.SixKey.matches(field.text, field.selectionStart, field.selectionEnd, 0))
            return fail("editor_checkpoint_changed")

        val started = clockMs()
        val observed = observeSequence(sequenceLabels)
        observationMs += (clockMs() - started).coerceAtLeast(0L)
        evidence += DebugKeyboardEvidence.fromObservation(
            observed.data, observed.success, sequenceLabels,
            if (observed.success) null else observed.displayPlanRefusal,
            if (observed.success) null else observed.displayWindowCensus,
            if (observed.success) null else observed.captureWindowChanges,
        )
        if (!observed.success) {
            // A refusal could mean protected content or revoked consent.
            // Do not collect another frame after that refusal.
            afterFailure = "skipped_refused_observation"
            return fail("keyboard_observation_refused")
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
            ?: return fail("certified_observation_unavailable")
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
            }
            if (candidate.label in allowedLabels) {
                checkDetails += buildJsonObject {
                    put("label", candidate.label)
                    put("image_only", candidate.imageOnly)
                    put("certified", certified)
                    put("method", safeCode(check.method))
                    if (!certified) {
                        put("failure_stage", safeCode(check.failureStage ?: if (!validMethod) "invalid_certification_method" else null))
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
        }
        if (!allCandidatesCertified) {
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
            if (!sequenceResult.success) put("native_refusal_reason", safeCode(seqReason))
            sequenceResult.data["pixel_failure_stage"]?.jsonPrimitive?.contentOrNull?.let {
                put("pixel_failure_stage", safeCode(it))
            }
            sequenceResult.data["pixel_failure_key"]?.jsonPrimitive?.intOrNull?.let {
                put("pixel_failure_key", it)
            }
        }

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
        return Outcome(progress(), null, evidence.toList(), observationMs, extraReads, afterFailure, followUpEvidence)
    }

    suspend fun run(
        port: AndroidControlPort, control: GenericControl,
        session: ControlSession, first: ControlFrame, allowed: () -> Boolean,
        checkpoint: (Progress, String) -> Unit,
    ): Outcome = runWithSeam(
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
            val certified = if (res.success && !obsId.isNullOrBlank() && !visId.isNullOrBlank()) {
                control.certifiedKeyboardObservation(obsId, visId, session.epoch)?.frame
            } else null
            ObservePacket(
                success = res.success,
                data = res.data,
                certifiedFrame = certified,
                displayPlanRefusal = if (res.success) null else port.debugDisplayPlanRefusal(),
                displayWindowCensus = if (res.success) null else port.debugDisplayWindowCensus(),
                captureWindowChanges = if (res.success) null else port.debugCaptureWindowChangeEvidence(),
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
            val res = control.visualKeySequenceAutonomous(payload, autonomousProof)
            SequencePacket(res.success, res.data)
        },
        sessionId = session.id,
        sessionEpoch = session.epoch,
        first = first,
        allowed = allowed,
        checkpoint = checkpoint,
    )
}
