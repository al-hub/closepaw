package ai.closepaw.qa

import ai.closepaw.chatgpt.control.AndroidControlPort
import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.chatgpt.control.GenericControl
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/**
 * #147 one-shot physical key state machine. Kept separate from QA result
 * serialization so the suspend run() method stays below the JVM 64KB limit.
 * Uses exactly the production GenericControl observation/action and native
 * safety validation. No retry, raw coordinates or text injection.
 */
internal object DebugSixKeyExecution {
    internal data class Progress(
        val verified: Int, val attempts: Int, val uncertain: Boolean, val stage: String,
    )
    internal data class Outcome(
        val progress: Progress, val failure: String?,
        val evidence: List<JsonObject>, val observationElapsedMs: Long,
        val followUpNativeReads: Int, val followUpStatus: String,
        val followUpEvidence: JsonObject?,
    )

    suspend fun run(
        port: AndroidControlPort, control: GenericControl,
        session: ControlSession, first: ControlFrame, allowed: () -> Boolean,
        checkpoint: (Progress, String) -> Unit,
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
        fun fail(reason: String) = Outcome(progress(), reason, evidence.toList(), observationMs,
            extraReads, afterFailure, followUpEvidence)
        fun checkpointAt(next: String, reason: String) {
            stage = next
            checkpoint(progress(), reason)
        }

        for ((index, label) in DebugOneStopQa.SixKey.labels.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!allowed()) return fail("control_authority_revoked")
            checkpointAt("key_${index + 1}_preflight", "fresh_keyboard_observation")
            val before = port.capture()
            if (!DebugOneStopQa.sameEditor(first, before)) return fail("editor_identity_changed")
            val field = before.inputTarget
            if (before.reason != null || before.packageName != "com.android.chrome" ||
                before.keyboard?.packageName != "com.samsung.android.honeyboard" || field == null ||
                !DebugOneStopQa.SixKey.matches(
                    field.text, field.selectionStart, field.selectionEnd, index))
                return fail("editor_checkpoint_changed")

            val started = SystemClock.elapsedRealtime()
            val observed = control.observe(includeImage = true, imageScope = "keyboard",
                keyboardKeyExact = label)
            observationMs += SystemClock.elapsedRealtime() - started
            evidence += DebugKeyboardEvidence.fromObservation(observed.data, observed.success, listOf(label))
            if (!observed.success) {
                // A refusal could mean protected content or revoked consent.
                // Do not collect another frame after that refusal.
                afterFailure = "skipped_refused_observation"
                return fail("keyboard_observation_refused")
            }
            val observationId = observed.data["observation_id"]?.jsonPrimitive?.content
                ?: return fail("observation_id_missing")
            val matches = (observed.data["nodes"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.filter {
                    it["keyboard_key"]?.jsonPrimitive?.content == label &&
                        it["enabled"]?.jsonPrimitive?.booleanOrNull == true
                }.orEmpty()
            if (matches.size != 1) {
                val followUp = DebugKeyboardEvidence.inspectAmbiguousCandidates(
                    capture = { port.capture() }, baseline = first, keyIndex = index, allowed = allowed)
                afterFailure = followUp.status
                followUpEvidence = followUp.evidence
                extraReads += followUp.reads
                return fail("unique_physical_key_not_proven")
            }
            val nodeId = matches.single()["node_id"]?.jsonPrimitive?.intOrNull
                ?: return fail("physical_key_id_missing")
            checkpointAt("key_${index + 1}_dispatch", "one_guarded_key")
            val action = control.action(buildJsonObject {
                put("action", "key")
                put("session_id", session.id)
                put("observation_id", observationId)
                put("node_id", nodeId)
            })
            val attempted = action.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true
            val unknown = action.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true
            if (attempted) attempts++
            uncertain = uncertain || unknown
            if (!DebugOneStopQa.SixKey.mayContinue(attempted, unknown, action.success))
                return fail(action.data["reason_code"]?.jsonPrimitive?.content ?: "key_effect_unconfirmed")

            val after = port.capture()
            if (!DebugOneStopQa.sameEditor(first, after)) {
                uncertain = true
                return fail("editor_identity_after_touch_unverified")
            }
            val post = after.inputTarget
            if (after.reason != null || after.packageName != "com.android.chrome" || post == null ||
                !DebugOneStopQa.SixKey.matches(post.text, post.selectionStart, post.selectionEnd, index + 1)) {
                uncertain = true
                return fail("independent_key_postcondition_failed")
            }
            verified++
            checkpointAt("key_${index + 1}_confirmed", "independent_editor_confirmed")
        }

        stage = "six_key_final"
        val final = port.capture()
        val field = final.inputTarget
        if (!DebugOneStopQa.sameEditor(first, final)) {
            uncertain = attempts > 0
            return fail("final_editor_identity_unverified")
        }
        if (final.reason != null || final.packageName != "com.android.chrome" || field == null ||
            !DebugOneStopQa.SixKey.matches(field.text, field.selectionStart, field.selectionEnd, 6)) {
            uncertain = attempts > 0
            return fail("six_key_final_read_unconfirmed")
        }
        return Outcome(progress(), null, evidence.toList(), observationMs, extraReads, afterFailure, followUpEvidence)
    }
}
