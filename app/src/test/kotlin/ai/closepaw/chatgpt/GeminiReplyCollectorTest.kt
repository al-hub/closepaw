package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class GeminiReplyCollectorTest {
    private val question = "새 질문"
    private val baseline = GeminiUiSnapshot(true, "native:1", replies = listOf(GeminiReply("old", "이전 답변", true)))
    private fun observation(text: String = "새 답변", generating: Boolean? = false, complete: Boolean = false,
                            submitted: String? = question, traversal: Boolean = true) =
        baseline.copy(submittedQuestion = submitted, generating = generating,
            replies = baseline.replies + GeminiReply("new", text, complete), traversalComplete = traversal)
    private class FakeUi(val frames: List<GeminiUiSnapshot>) : GeminiRoundTripUi {
        var polls = 0; var locked = false; var writes = 0
        override suspend fun snapshot() = frames[(polls++).coerceAtMost(frames.lastIndex)]
        override suspend fun isLocked() = locked
        override suspend fun launch(): Boolean { writes++; error("collector cannot launch") }
        override suspend fun inspect(): DraftComposerState = error("collector cannot inspect composer")
        override suspend fun setIfEmpty(text: String): DraftWriteResult { writes++; error("collector cannot write") }
        override suspend fun matches(text: String) = false
        override suspend fun sendIfMatches(text: String, targetKey: String): GeminiSendResult { writes++; error("collector cannot send") }
    }
    private suspend fun collect(frames: List<GeminiUiSnapshot>, maxChars: Int = 100,
                                allowed: () -> Boolean = { true }, cancelled: () -> Boolean = { false },
                                setup: (FakeUi) -> Unit = {}): Pair<GeminiReplyCollection, FakeUi> {
        val ui = FakeUi(frames); setup(ui); var now = 0L
        val collector = GeminiReplyCollector(ui, allowed, { now }, { now += it },
            timeoutMs = 1000, pollMs = 100, stableMs = 200, maxAnswerChars = maxChars, maxPolls = 15)
        return collector.collect(question, baseline, cancelled) to ui
    }
    @Test fun unparsedOldBodyCannotBecomeFreshReplyUnderNewQuestion() = runBlocking {
        val oldHash = GeminiUiTextEvidence.hash("old body")
        val before = baseline.copy(replies = emptyList(), observedTextEvidence = setOf(oldHash))
        val oldFrame = before.copy(submittedQuestion = question,
            replies = listOf(GeminiReply("correlated", "old body", true, setOf(oldHash))))
        var now = 0L
        val stale = GeminiReplyCollector(FakeUi(listOf(oldFrame)), clockMillis = { now },
            pause = { now += it }, timeoutMs = 600, pollMs = 100, stableMs = 200).collect(question, before)
        assertThat(stale.answer).isNull()
        val freshFrame = oldFrame.copy(replies = listOf(GeminiReply("correlated", "fresh body", true,
            setOf(GeminiUiTextEvidence.hash("fresh body")))))
        now = 0L
        val fresh = GeminiReplyCollector(FakeUi(listOf(oldFrame, freshFrame)), clockMillis = { now },
            pause = { now += it }, timeoutMs = 1000, pollMs = 100, stableMs = 200).collect(question, before)
        assertThat(fresh.state).isEqualTo("completed")
        assertThat(fresh.answer).isEqualTo("fresh body")
    }
    @Test fun mixedOldAndFreshParagraphsCannotMasqueradeAsOneNewReply() = runBlocking {
        val oldHash = GeminiUiTextEvidence.hash("old paragraph")
        val before = baseline.copy(replies = emptyList(), observedTextEvidence = setOf(oldHash))
        val mixed = before.copy(submittedQuestion = question, replies = listOf(GeminiReply(
            "mixed", "old paragraph\nfresh paragraph", true,
            setOf(oldHash, GeminiUiTextEvidence.hash("fresh paragraph")))))
        var now = 0L
        val result = GeminiReplyCollector(FakeUi(listOf(mixed)), clockMillis = { now },
            pause = { now += it }, timeoutMs = 600, pollMs = 100, stableMs = 200).collect(question, before)
        assertThat(result.answer).isNull()
        assertThat(result.state).isEqualTo("failed")
    }
    @Test fun generationMustBeObservedThenExplicitlyStoppedAndStable() = runBlocking {
        val (result, ui) = collect(listOf(observation(generating = true), observation()))
        assertThat(result.state).isEqualTo("completed")
        assertThat(result.answer).isEqualTo("새 답변")
        assertThat(result.completionEvidence).isEqualTo("generation_stopped_and_stable")
        assertThat(ui.writes).isEqualTo(0)
    }
    @Test fun semanticCompletionMarkerAlsoRequiresStableText() = runBlocking {
        val (result, ui) = collect(listOf(observation(complete = true)))
        assertThat(result.state).isEqualTo("completed")
        assertThat(result.completionEvidence).isEqualTo("semantic_complete_and_stable")
        assertThat(ui.polls).isAtLeast(3)
    }
    @Test fun mereTextPauseNeverProvesCompletion() = runBlocking {
        val (result, _) = collect(listOf(observation()))
        assertThat(result.state).isEqualTo("partial")
        assertThat(result.reason).isEqualTo("reply_timeout")
        assertThat(result.completionEvidence).isNull()
    }
    @Test fun generatingUnknownAfterObservedGenerationIsNotStopped() = runBlocking {
        val (result, _) = collect(listOf(observation(generating = true), observation(generating = null)))
        assertThat(result.state).isEqualTo("partial")
    }
    @Test fun previousReplyAndPromptAreNeverReturnedAsAnswer() = runBlocking {
        val (result, _) = collect(listOf(baseline.copy(submittedQuestion = question, generating = false,
            replies = baseline.replies + GeminiReply("prompt", question, true))))
        assertThat(result.answer).isNull(); assertThat(result.state).isEqualTo("failed")
    }
    @Test fun changedOldReplyCannotMasqueradeAsNewAnswer() = runBlocking {
        val (result, _) = collect(listOf(baseline.copy(submittedQuestion = question,
            replies = listOf(GeminiReply("old", "변경된 이전 답변", true)))))
        assertThat(result.answer).isNull()
    }
    @Test fun sameOldTextUnderNewIdStillCannotMasqueradeAsFreshAnswer() = runBlocking {
        val (result, _) = collect(listOf(observation("이전 답변", complete = true)))
        assertThat(result.answer).isNull()
    }
    @Test fun exactSubmittedQuestionCorrelationRequired() = runBlocking {
        for (submitted in listOf(null, "다른 질문")) {
            val (result, _) = collect(listOf(observation(complete = true, submitted = submitted)))
            assertThat(result.answer).isNull(); assertThat(result.state).isEqualTo("failed")
        }
    }
    @Test fun multipleFreshReplyRegionsAreAmbiguous() = runBlocking {
        val (result, _) = collect(listOf(observation(complete = true).let { it.copy(replies = it.replies + GeminiReply("other", "다른 응답", true)) }))
        assertThat(result.answer).isNull(); assertThat(result.reason).isEqualTo("reply_ambiguous")
    }
    @Test fun longAndClippedAnswersRemainPartial() = runBlocking {
        val (long, _) = collect(listOf(observation("123456789", complete = true)), maxChars = 5)
        assertThat(long.answer).isEqualTo("12345"); assertThat(long.partial).isTrue()
        assertThat(long.reason).isEqualTo("reply_length_limit")
        val (clipped, _) = collect(listOf(observation(complete = true, traversal = false)))
        assertThat(clipped.state).isEqualTo("partial")
        assertThat(clipped.reason).isEqualTo("reply_incomplete_traversal")
    }
    @Test fun targetSwitchPreservesObservedPartialAndNeverResends() = runBlocking {
        val (result, ui) = collect(listOf(observation(generating = true), observation().copy(targetKey = "other")))
        assertThat(result.state).isEqualTo("partial"); assertThat(result.answer).isEqualTo("새 답변")
        assertThat(result.reason).isEqualTo("target_changed"); assertThat(ui.writes).isEqualTo(0)
    }
    @Test fun lockAndPermissionRevocationFailClosed() = runBlocking {
        val (locked, ui) = collect(listOf(observation(complete = true)), setup = { it.locked = true })
        assertThat(locked.reason).isEqualTo("device_locked"); assertThat(ui.polls).isEqualTo(0)
        var checks = 0
        val (revoked, _) = collect(listOf(observation(generating = true)), allowed = { ++checks <= 2 })
        assertThat(revoked.reason).isEqualTo("gemini_question_disabled"); assertThat(revoked.partial).isTrue()
    }
    @Test fun cancellationStopsObserverWithoutClaimingGenerationStopped() = runBlocking {
        var checks = 0
        val (result, _) = collect(listOf(observation(generating = true)), cancelled = { ++checks > 2 })
        assertThat(result.state).isEqualTo("cancelled"); assertThat(result.reason).isEqualTo("observer_cancelled")
        assertThat(result.answer).isEqualTo("새 답변"); assertThat(result.completionEvidence).isNull()
    }
    @Test fun boundedPollCountEvenWithFrozenClock() = runBlocking {
        val ui = FakeUi(listOf(observation()))
        val result = GeminiReplyCollector(ui, clockMillis = { 0 }, pause = {}, maxPolls = 3).collect(question, baseline)
        assertThat(ui.polls).isEqualTo(3); assertThat(result.reason).isEqualTo("reply_poll_limit")
    }
    @Test fun completionMarkerWhileGeneratingCannotDeclareSuccess() = runBlocking {
        val (result, _) = collect(listOf(observation(generating = true, complete = true)))
        assertThat(result.state).isEqualTo("partial")
        assertThat(result.completionEvidence).isNull()
    }
    @Test fun repeatedIdenticalPromptWithoutTurnIdentifierIsAmbiguous() = runBlocking {
        val ui = FakeUi(listOf(observation(complete = true)))
        val result = GeminiReplyCollector(ui).collect(question, baseline.copy(submittedQuestion = question))
        assertThat(result.reason).isEqualTo("submission_correlation_ambiguous")
        assertThat(ui.polls).isEqualTo(0)
    }
    @Test fun replacingResponseRegionCannotMergeDifferentAnswers() = runBlocking {
        val (result, _) = collect(listOf(observation(generating = true),
            observation(complete = true).copy(replies = listOf(GeminiReply("replacement", "다른 답변", true)))))
        assertThat(result.reason).isEqualTo("reply_changed")
        assertThat(result.answer).isEqualTo("새 답변")
    }
    @Test fun structuredCancellationIsRethrownAndNeverClaimsStoppedGeneration() = runBlocking {
        val ui = FakeUi(listOf(observation(generating = true)))
        var caught = false
        try {
            GeminiReplyCollector(ui, pause = { throw CancellationException("cancel") }).collect(question, baseline)
        } catch (_: CancellationException) { caught = true }
        assertThat(caught).isTrue(); assertThat(ui.writes).isEqualTo(0)
    }

    @Test fun incompleteBaselineCannotProveFreshReply() = runBlocking {
        val ui = FakeUi(listOf(observation(complete = true)))
        val result = GeminiReplyCollector(ui).collect(question, baseline.copy(traversalComplete = false))
        assertThat(result.reason).isEqualTo("submission_baseline_incomplete")
        assertThat(ui.polls).isEqualTo(0)
    }

    @Test fun clickWithoutQuestionObservationDoesNotConfirmSubmission() = runBlocking {
        val (result, _) = collect(listOf(baseline))
        assertThat(result.submissionObserved).isFalse()
        assertThat(result.answer).isNull()
    }
    @Test fun exactQuestionObservationConfirmsSubmissionEvenWithoutAnswer() = runBlocking {
        val (result, _) = collect(listOf(baseline.copy(submittedQuestion = question)))
        assertThat(result.submissionObserved).isTrue()
        assertThat(result.answer).isNull()
        assertThat(result.state).isEqualTo("failed")
    }
    @Test fun submissionEvidenceSurvivesTargetSwitchAndCancellation() = runBlocking {
        val (switched, _) = collect(listOf(observation(generating = true), baseline.copy(foreground = false)))
        assertThat(switched.submissionObserved).isTrue()
        var checks = 0
        val (cancelled, _) = collect(listOf(observation(generating = true)), cancelled = { ++checks > 2 })
        assertThat(cancelled.submissionObserved).isTrue()
        assertThat(cancelled.state).isEqualTo("cancelled")
    }

    @Test fun defaultCompletionSettlesWithinFourHundredMillis() = runBlocking {
        val ui = FakeUi(listOf(observation(complete = true))); var now = 0L
        val result = GeminiReplyCollector(ui, clockMillis = { now }, pause = { now += it }).collect(question, baseline)
        assertThat(result.state).isEqualTo("completed")
        assertThat(result.collectionMs).isEqualTo(400L)
        assertThat(result.completionObservedMs).isEqualTo(0L)
    }
    @Test fun unverifiedScrollableCoverageCannotReturnFullSuccess() = runBlocking {
        val (result, _) = collect(listOf(observation(complete = true).copy(replyCoverageComplete = false)))
        assertThat(result.state).isEqualTo("partial")
        assertThat(result.reason).isEqualTo("reply_scroll_region_unverified")
    }

}
