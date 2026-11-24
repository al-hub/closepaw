package ai.closepaw.qa

import ai.closepaw.chatgpt.control.AndroidControlPort
import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.chatgpt.control.GenericControl
import ai.closepaw.chatgpt.control.TapBounds
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/**
 * #147 one-shot physical key state machine. Reuses the production
 * VisualSequenceCandidates observation, on-device pixel-role certification
 * (SpaceBarGlyphPolicy + ML Kit Korean OCR >= 0.92), and GenericControl.visualKeySequence.
 * Never guesses coordinates, picks arbitrarily among duplicates, weakens native
 * guards, or replays UNKNOWN outcomes.
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
        val displayPlanRefusal: String? = null,
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
        certifyCandidate: suspend (String, ControlFrame, PhysicalCandidate) -> TapBounds?,
        dispatchSequence: suspend (JsonObject) -> SequencePacket,
        sessionId: String,
        first: ControlFrame,
        allowed: () -> Boolean,
        checkpoint: (Progress, String) -> Unit,
        clockMs: () -> Long = { SystemClock.elapsedRealtime() },
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

        // Strict fail-closed on-device pixel-role certification for every key.
        // Never set visual_target_confirmed=true unless all 4 crops are verified.
        val certifiedCrops = linkedMapOf<String, Pair<PhysicalCandidate, TapBounds>>()
        val verifiedLabels = mutableListOf<String>()
        val unverifiedLabels = mutableListOf<String>()
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            if (!allowed()) return fail("control_authority_revoked")
            val crop = certifyCandidate(visualId, before, candidate)
            if (crop != null && crop.valid) {
                certifiedCrops[candidate.label] = candidate to crop
                if (candidate.label in allowedLabels) verifiedLabels += candidate.label
            } else {
                if (candidate.label in allowedLabels) unverifiedLabels += candidate.label
            }
        }
        evidence += buildJsonObject {
            put("source", "autonomous_pixel_role_check")
            put("pixel_role_certified", unverifiedLabels.isEmpty() && certifiedCrops.size == sequenceLabels.size)
            put("verified_labels", JsonArray(verifiedLabels.map(::JsonPrimitive)))
            put("unverified_labels", JsonArray(unverifiedLabels.map(::JsonPrimitive)))
            put("touch_authorized_without_pixel_proof", false)
        }
        if (unverifiedLabels.isNotEmpty() || certifiedCrops.size != sequenceLabels.size) {
            val followUp = DebugKeyboardEvidence.inspectAmbiguousCandidates(
                capture = captureFrame, baseline = first, keyIndex = 0, allowed = allowed,
            )
            afterFailure = followUp.status
            followUpEvidence = followUp.evidence
            extraReads += followUp.reads
            return fail("autonomous_pixel_role_unverified")
        }

        if (!allowed()) return fail("control_authority_revoked")
        val steps = JsonArray(DebugOneStopQa.SixKey.labels.map { label ->
            val (candidate, crop) = certifiedCrops[label]
                ?: return fail("autonomous_pixel_role_unverified")
            buildJsonObject {
                put("label", label)
                put("node_id", candidate.nodeId)
                put("image_rect", JsonArray(listOf(crop.left, crop.top, crop.right, crop.bottom).map(::JsonPrimitive)))
            }
        })
        checkpointAt("key_1_dispatch", "guarded_visual_key_sequence")
        val sequenceResult = dispatchSequence(buildJsonObject {
            put("session_id", sessionId)
            put("observation_id", observationId)
            put("visual_id", visualId)
            put("visual_target_confirmed", true)
            put("text", DebugOneStopQa.SixKey.expected(6))
            put("reuse_key_map", false)
            put("steps", steps)
        })

        val attempted = sequenceResult.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true
        val unknown = sequenceResult.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true
        val appVerified = sequenceResult.data["app_result_verified"]?.jsonPrimitive?.booleanOrNull == true
        val completed = (sequenceResult.data["completed_keys"]?.jsonPrimitive?.intOrNull ?: 0)
            .coerceIn(0, DebugOneStopQa.SixKey.labels.size)
        val fallbackSpaces = sequenceResult.data["confirmed_image_fallback_spaces"]?.jsonPrimitive?.intOrNull ?: 0
        val seqReason = sequenceResult.data["reason_code"]?.jsonPrimitive?.contentOrNull
        evidence += buildJsonObject {
            put("source", "visual_key_sequence_result")
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
            val res = control.observe(
                includeImage = true,
                imageScope = "keyboard",
                keyboardSequenceLabels = labels,
            )
            ObservePacket(res.success, res.data, if (res.success) null else port.debugDisplayPlanRefusal())
        },
        certifyCandidate = { visualId, frame, candidate ->
            port.certifyKeyboardCandidatePixels(
                visualId = visualId,
                frame = frame,
                nodeBoundsRaw = candidate.bounds,
                imageOnly = candidate.imageOnly,
                expectedLabel = candidate.label,
            )
        },
        dispatchSequence = { payload ->
            val res = control.visualKeySequence(payload)
            SequencePacket(res.success, res.data)
        },
        sessionId = session.id,
        first = first,
        allowed = allowed,
        checkpoint = checkpoint,
    )
}
