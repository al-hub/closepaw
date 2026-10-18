package ai.closepaw.chatgpt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GeminiPagedReplyCollectorTest {
    private val question = "new question"
    private val baseline = GeminiUiSnapshot(true, "native:1")
    private fun frame(text: String, start: Boolean = false, end: Boolean = false, back: Boolean = true, forward: Boolean = true) =
        baseline.copy(composerCandidates = 1, submittedQuestion = if (start) question else null,
            replyPage = GeminiReplyPage("region", 1, text, start, end, forward, back), generating = false)
    private class Ui(val frames: List<GeminiUiSnapshot>, val canScroll: Boolean = true) : GeminiRoundTripUi {
        var position = 0; var scrolls = mutableListOf<Boolean>(); var locked = false
        override suspend fun snapshot() = frames[position]
        override suspend fun scrollReply(targetKey: String, containerKey: String, forward: Boolean): Boolean {
            scrolls.add(forward)
            if (!canScroll) return false
            position = (position + if (forward) 1 else -1).coerceIn(0, frames.lastIndex)
            return true
        }
        override suspend fun isLocked() = locked
        override suspend fun launch() = error("must not launch")
        override suspend fun inspect(): DraftComposerState = error("must not inspect")
        override suspend fun setIfEmpty(text: String): DraftWriteResult = error("must not write")
        override suspend fun matches(text: String) = error("must not edit")
        override suspend fun sendIfMatches(text: String, targetKey: String): GeminiSendResult = error("must never send")
    }
    private suspend fun collect(ui: Ui, allowed: () -> Boolean = { true }, cancelled: () -> Boolean = { false }, max: Int = 16000): GeminiReplyCollection {
        var now = 0L
        return GeminiPagedReplyCollector(ui, allowed, { now }, { now += it }, 3000, 100, 200, max, 8)
            .collect(question, baseline, ui.snapshot(), cancelled)
    }
    @Test fun collectsThreeOverlappingViewportsUntilVerifiedFooter() = runBlocking {
        val ui = Ui(listOf(frame("first paragraph\nshared paragraph one", true),
            frame("shared paragraph one\nshared paragraph two"), frame("shared paragraph two\nlast paragraph", end = true)))
        val result = collect(ui)
        assertEquals("completed", result.state)
        assertEquals("first paragraph\nshared paragraph one\nshared paragraph two\nlast paragraph", result.answer)
        assertEquals(3, result.pagesCollected); assertEquals(listOf(true, true), ui.scrolls)
        assertTrue(result.submissionObserved); assertFalse(result.partial)
    }
    @Test fun rewindsFromTailToQuestionThenCollectsInOrder() = runBlocking {
        val ui = Ui(listOf(frame("first\nshared paragraph", true), frame("shared paragraph\nlast", end = true)))
        ui.position = 1
        val result = collect(ui)
        assertEquals("completed", result.state); assertEquals("first\nshared paragraph\nlast", result.answer)
        assertEquals(listOf(false, true), ui.scrolls)
    }
    @Test fun missingOverlapReturnsPartialAndNeverClaimsWholeAnswer() = runBlocking {
        val result = collect(Ui(listOf(frame("first", true), frame("unrelated last", end = true))))
        assertEquals("reply_scroll_gap", result.reason); assertTrue(result.partial); assertEquals("first", result.answer)
    }
    @Test fun actionSuccessWithoutMovementIsBoundedAndPartial() = runBlocking {
        val ui = Ui(listOf(frame("first", true)))
        val result = collect(ui)
        assertEquals("reply_scroll_stalled", result.reason); assertTrue(result.partial); assertEquals(1, ui.scrolls.size)
    }
    @Test fun rejectedScrollAndMissingFooterReturnPartial() = runBlocking {
        assertEquals("reply_scroll_unavailable", collect(Ui(listOf(frame("first", true)), false)).reason)
        assertEquals("reply_timeout", collect(Ui(listOf(frame("first", true, forward = false)))).reason)
    }
    @Test fun generationStillRunningCannotProduceSuccess() = runBlocking {
        val result = collect(Ui(listOf(frame("first", true, true).copy(generating = true))))
        assertEquals("reply_timeout", result.reason); assertNull(result.completionEvidence)
    }
    @Test fun targetChangeLockRevocationAndCancellationStopPaging() = runBlocking {
        val start = frame("first\nshared paragraph", true)
        val end = frame("shared paragraph\nlast", end = true)
        val changed = collect(Ui(listOf(start, end.copy(targetKey = "other"))))
        assertEquals("target_changed", changed.reason); assertTrue(changed.partial)
        val locked = Ui(listOf(start)); locked.locked = true
        assertEquals("device_locked", collect(locked).reason); assertTrue(locked.scrolls.isEmpty())
        assertEquals("gemini_question_disabled", collect(Ui(listOf(start)), allowed = { false }).reason)
        assertEquals("cancelled", collect(Ui(listOf(start)), cancelled = { true }).state)
    }
    @Test fun newTurnOrScrollRegionChangeCannotBeMerged() = runBlocking {
        val start = frame("first\nshared paragraph", true)
        val other = frame("shared paragraph\nlast", end = true)
        assertEquals("submitted_question_changed", collect(Ui(listOf(start, other.copy(submittedQuestion = "another question")))).reason)
        assertEquals("reply_scroll_region_changed", collect(Ui(listOf(start, other.copy(replyPage = other.replyPage!!.copy(containerKey = "other"))))).reason)
    }
    @Test fun initiallyEmptyAnswerWithNoForwardActionWaitsForBodyAndFooter() = runBlocking {
        val delegate = Ui(listOf(frame("", true, forward = false)))
        var reads = 0; var now = 0L
        val ui = object : GeminiRoundTripUi by delegate {
            override suspend fun snapshot(): GeminiUiSnapshot {
                reads++
                return when {
                    reads < 3 -> frame("", true, forward = false)
                    reads < 6 -> frame("delayed answer", true, forward = false)
                    else -> frame("delayed answer", true, true, forward = false)
                }
            }
        }
        val result = GeminiPagedReplyCollector(ui, { true }, { now }, { now += it }, 2000, 100, 200, 16000)
            .collect(question, baseline, ui.snapshot(), { false })
        assertEquals("completed", result.state); assertEquals("delayed answer", result.answer)
        assertTrue(now >= 500); assertEquals(1, result.pagesCollected)
        assertTrue(delegate.scrolls.isEmpty())
    }
    @Test fun emptyAnswerNeverFailsEarlyOrClaimsCoverage() = runBlocking {
        val result = collect(Ui(listOf(frame("", true, forward = false))))
        assertEquals("reply_timeout", result.reason); assertNull(result.answer)
        assertEquals(0, result.pagesCollected); assertTrue(result.submissionObserved)
        assertEquals(3000L, result.collectionMs)
    }
    @Test fun temporarilyMissingStartupRegionCanRecoverBeforeAnyBodyIsAccepted() = runBlocking {
        val delegate = Ui(listOf(frame("", true, forward = false)))
        var reads = 0; var now = 0L
        val ui = object : GeminiRoundTripUi by delegate {
            override suspend fun snapshot(): GeminiUiSnapshot = when (++reads) {
                1 -> frame("", true, forward = false)
                2 -> baseline.copy(submittedQuestion = question)
                else -> frame("finished answer", true, true, forward = false)
            }
        }
        val result = GeminiPagedReplyCollector(ui, { true }, { now }, { now += it }, 2000, 100, 200, 16000)
            .collect(question, baseline, ui.snapshot(), { false })
        assertEquals("completed", result.state); assertEquals("finished answer", result.answer)
    }
    @Test fun unchangedFinalBodyWaitingForFooterDoesNotInflatePageCount() = runBlocking {
        val delegate = Ui(listOf(frame("first\nshared paragraph", true), frame("shared paragraph\nlast", forward = false)))
        var now = 0L
        val ui = object : GeminiRoundTripUi by delegate {
            override suspend fun snapshot(): GeminiUiSnapshot {
                val observed = delegate.snapshot()
                return if (delegate.position == 1 && now >= 1400) observed.copy(replyPage = observed.replyPage!!.copy(endVisible = true)) else observed
            }
        }
        val result = GeminiPagedReplyCollector(ui, { true }, { now }, { now += it }, 3000, 100, 200, 16000)
            .collect(question, baseline, ui.snapshot(), { false })
        assertEquals("completed", result.state); assertEquals(2, result.pagesCollected)
        assertEquals("first\nshared paragraph\nlast", result.answer)
    }

    @Test fun baselineParagraphCannotMasqueradeAsNewPagedReply() = runBlocking {
        val hash = GeminiUiTextEvidence.hash("old paragraph")
        val initial = frame("old paragraph", true, true).let { it.copy(replyPage = it.replyPage!!.copy(textEvidence = setOf(hash))) }
        val ui = Ui(listOf(initial)); var now = 0L
        val result = GeminiPagedReplyCollector(ui, { true }, { now }, { now += it }, 1000, 100, 200, 16000)
            .collect(question, baseline.copy(observedTextEvidence = setOf(hash)), initial, { false })
        assertEquals("reply_prior_text_overlap", result.reason); assertNull(result.answer)
        assertTrue(ui.scrolls.isEmpty())
    }

    @Test fun answerLengthLimitIsExplicitAndPartial() = runBlocking {
        val result = collect(Ui(listOf(frame("123456789", true, true))), max = 5)
        assertEquals("reply_length_limit", result.reason); assertEquals("12345", result.answer); assertTrue(result.partial)
    }
    @Test fun repeatProseIsPreservedAndTinyCoincidentalOverlapIsRejected() {
        assertEquals("first\nrepeat paragraph\nrepeat paragraph\nlast", GeminiPagedReplyCollector.joinOverlapping(
            "first\nrepeat paragraph", "repeat paragraph\nrepeat paragraph\nlast"))
        assertNull(GeminiPagedReplyCollector.joinOverlapping("first a", "another page"))
    }
}
