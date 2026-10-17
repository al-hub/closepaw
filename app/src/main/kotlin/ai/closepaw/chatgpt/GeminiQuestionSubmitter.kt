package ai.closepaw.chatgpt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal sealed interface GeminiSubmissionResult {
    data class Sent(val baseline: GeminiUiSnapshot) : GeminiSubmissionResult
    data class NotSent(val reason: String) : GeminiSubmissionResult
    data class Unknown(val reason: String) : GeminiSubmissionResult
}

/** One invocation performs at most one semantic click. The caller owns durable request deduplication. */
internal class GeminiQuestionSubmitter(
    private val ui: GeminiRoundTripUi,
    private val allowed: () -> Boolean,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun submit(text: String, beforeSend: suspend () -> Boolean): GeminiSubmissionResult {
        var clickAttempted = false
        fun no(reason: String) = GeminiSubmissionResult.NotSent(reason)
        try {
            if (text.isBlank() || text.length > 8000) return no("invalid_text")
            if (!allowed()) return no("gemini_question_disabled")
            if (ui.isLocked()) return no("device_locked")
            if (!ui.launch()) return no(ui.launchFailureReason)
            val baseline = ui.snapshot()
            if (!baseline.foreground) return no("target_not_foreground")
            if (!baseline.traversalComplete) return no("traversal_incomplete")
            if (baseline.composerCandidates != 1) return no("composer_ambiguous")
            if (baseline.composerText.isNotBlank()) return no("existing_draft")
            if (baseline.submittedQuestion == text) return no("submission_correlation_ambiguous")
            if (baseline.generating == true) return no("generation_in_progress")
            if (!allowed() || ui.isLocked()) return no("target_changed")
            when (ui.setIfEmpty(text)) {
                DraftWriteResult.EXISTING_DRAFT -> return no("existing_draft")
                DraftWriteResult.TARGET_CHANGED -> return no("target_changed")
                DraftWriteResult.REJECTED -> return no("set_text_unconfirmed")
                DraftWriteResult.WRITTEN -> Unit
            }
            if (!ui.matches(text)) return no("verification_failed")
            // Rendering the send control can lag the confirmed input update. Only observe while waiting.
            var readySnapshot: GeminiUiSnapshot? = null
            for (attempt in 0 until 8) {
                if (!allowed()) return no("gemini_question_disabled")
                if (ui.isLocked()) return no("device_locked")
                val candidate = ui.snapshot()
                // A suspended accessibility read may outlive consent or a lock transition.
                if (!allowed()) return no("gemini_question_disabled")
                if (ui.isLocked()) return no("device_locked")
                if (!candidate.foreground || candidate.targetKey != baseline.targetKey) return no("target_changed")
                if (candidate.submittedQuestion != baseline.submittedQuestion || candidate.replies != baseline.replies) return no("conversation_changed")
                if (!candidate.traversalComplete || candidate.composerCandidates != 1 || candidate.composerText != text) return no("input_changed")
                if (candidate.generating == true) return no("generation_in_progress")
                if (candidate.sendCandidates > 1 || candidate.sendCandidates < 0) return no("send_unavailable")
                if (candidate.sendCandidates == 1 && candidate.sendEnabled) {
                    readySnapshot = candidate
                    break
                }
                if (attempt < 7) pause(150)
            }
            val ready = readySnapshot ?: return no("send_unavailable")
            if (!allowed() || ui.isLocked()) return no("target_changed")
            if (!beforeSend()) return no("submission_not_persisted")
            if (!allowed() || ui.isLocked()) return no("target_changed")
            clickAttempted = true
            return when (ui.sendIfMatches(text, baseline.targetKey)) {
                GeminiSendResult.CLICKED -> GeminiSubmissionResult.Sent(ready)
                GeminiSendResult.TARGET_CHANGED -> no("target_changed")
                GeminiSendResult.INPUT_CHANGED -> no("input_changed")
                GeminiSendResult.REJECTED -> GeminiSubmissionResult.Unknown("send_unconfirmed")
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return if (clickAttempted) GeminiSubmissionResult.Unknown("send_exception") else no("submission_exception")
        }
    }
}
