package ai.closepaw.chatgpt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Text remains invocation-local. Only reason and completion evidence belong in diagnostics. */
internal data class GeminiReplyCollection(
    val state: String,
    val reason: String,
    val answer: String? = null,
    val partial: Boolean = false,
    val completionEvidence: String? = null,
    val submissionObserved: Boolean = false,
)

/** Read-only bounded observation; this class never launches, edits, sends, or stops Gemini. */
internal class GeminiReplyCollector(
    private val ui: GeminiRoundTripUi,
    private val allowed: () -> Boolean = { true },
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val timeoutMs: Long = 60_000,
    private val pollMs: Long = 500,
    private val stableMs: Long = 1_000,
    private val maxAnswerChars: Int = 16_000,
    private val maxPolls: Int = 150,
) {
    init {
        require(timeoutMs > 0 && pollMs > 0 && stableMs > 0 && maxAnswerChars > 0 && maxPolls > 0)
    }

    suspend fun collect(
        question: String,
        baseline: GeminiUiSnapshot,
        isCancelled: () -> Boolean = { false },
    ): GeminiReplyCollection {
        val start = clockMillis()
        val baselineIds = baseline.replies.map { it.id }.toSet()
        val baselineTexts = baseline.replies.map { it.text.trim() }.toSet()
        var answer: String? = null
        var observedGenerating = false
        var submissionObserved = false
        var stableSince = start
        var replyId: String? = null
        var polls = 0
        var traversalComplete = true
        fun finish(reason: String, cancelled: Boolean = false) = GeminiReplyCollection(
            state = if (cancelled) "cancelled" else if (answer != null) "partial" else "failed",
            reason = reason, answer = answer, partial = answer != null,
            submissionObserved = submissionObserved,
        )
        if (question.isBlank() || !baseline.foreground || baseline.targetKey.isBlank()) return finish("invalid_submission_baseline")
        if (!baseline.traversalComplete) return finish("submission_baseline_incomplete")
        // A repeated identical prompt cannot be correlated to a newly submitted turn with this UI contract.
        if (baseline.submittedQuestion == question) return finish("submission_correlation_ambiguous")
        try {
            while (polls < maxPolls) {
                if (isCancelled()) return finish("observer_cancelled", cancelled = true)
                if (!allowed()) return finish("gemini_question_disabled")
                if (ui.isLocked()) return finish("device_locked")
                if ((clockMillis() - start).coerceAtLeast(0) >= timeoutMs) {
                    return finish(if (!traversalComplete && answer != null) "reply_incomplete_traversal" else "reply_timeout")
                }
                val snapshot = ui.snapshot()
                polls++
                if (!snapshot.foreground || snapshot.targetKey != baseline.targetKey) return finish("target_changed")
                // Revalidate after the suspended read: revocation/lock must never return success.
                if (isCancelled()) return finish("observer_cancelled", cancelled = true)
                if (!allowed()) return finish("gemini_question_disabled")
                if (ui.isLocked()) return finish("device_locked")
                if (snapshot.submittedQuestion == question) {
                    submissionObserved = true
                    if (snapshot.generating == true) observedGenerating = true
                    val candidates = snapshot.replies.filter {
                        it.id.isNotBlank() && it.id !in baselineIds && it.text.isNotBlank() &&
                            it.text.trim() != question.trim() && it.text.trim() !in baselineTexts &&
                            (it.textEvidence.isEmpty() || it.textEvidence.none { evidence -> evidence in baseline.observedTextEvidence })
                    }.distinctBy { it.id }
                    if (candidates.size > 1) return finish("reply_ambiguous")
                    val candidate = candidates.singleOrNull()
                    if (candidate != null) {
                        if (replyId != null && replyId != candidate.id) return finish("reply_changed")
                        val text = candidate.text
                        if (text != answer) stableSince = clockMillis()
                        replyId = candidate.id
                        answer = text.take(maxAnswerChars)
                        traversalComplete = snapshot.traversalComplete
                        if (text.length > maxAnswerChars) return finish("reply_length_limit")
                        val stable = (clockMillis() - stableSince).coerceAtLeast(0) >= stableMs
                        val evidence = when {
                            snapshot.generating == true -> null
                            candidate.complete -> "semantic_complete_and_stable"
                            observedGenerating && snapshot.generating == false -> "generation_stopped_and_stable"
                            else -> null
                        }
                        if (evidence == null) stableSince = clockMillis()
                        if (stable && evidence != null && traversalComplete) {
                            return GeminiReplyCollection("completed", "reply_completed", answer, false, evidence,
                                submissionObserved = submissionObserved)
                        }
                    } else {
                        // A disappearing response cannot accumulate stability time across missing frames.
                        stableSince = clockMillis()
                    }
                } else {
                    // A later user turn invalidates the observation; absence may mean submission is pending.
                    if (snapshot.submittedQuestion != null && snapshot.submittedQuestion != baseline.submittedQuestion) {
                        return finish("submitted_question_changed")
                    }
                    stableSince = clockMillis()
                }
                pause(pollMs)
            }
            return finish(if (!traversalComplete && answer != null) "reply_incomplete_traversal" else "reply_poll_limit")
        } catch (cancel: CancellationException) {
            // Structured cancellation propagates. It does not issue a UI stop or imply generation stopped.
            throw cancel
        } catch (_: Exception) {
            return finish("reply_observation_failed")
        }
    }
}
