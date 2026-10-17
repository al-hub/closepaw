package ai.closepaw.chatgpt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GeminiQuestionSubmitterTest {
    private class Ui : GeminiRoundTripUi {
        var text = ""; var sends = 0; var lock = false; var foreground = true
        var baselineQuestion: String? = null; var conversationChanged = false
        var writes = 0; var readyReads = 0; var sendCandidates = 1; var sendEnabled = true
        var targetKey = "target"; var composerCandidates = 1; var traversalComplete = true
        var generating: Boolean? = null; var onReadyRead: (() -> Unit)? = null
        var sendResult = GeminiSendResult.CLICKED
        override suspend fun isLocked() = lock
        override suspend fun launch() = foreground
        override suspend fun inspect() = DraftComposerState(foreground, 1, text)
        override suspend fun setIfEmpty(text: String): DraftWriteResult {
            if (this.text.isNotEmpty()) return DraftWriteResult.EXISTING_DRAFT
            writes++; this.text = text; return DraftWriteResult.WRITTEN
        }
        override suspend fun matches(text: String) = this.text == text
        override suspend fun snapshot(): GeminiUiSnapshot {
            if (text.isNotBlank()) { readyReads++; onReadyRead?.invoke() }
            return GeminiUiSnapshot(foreground, targetKey, composerCandidates, text, sendCandidates, sendEnabled,
                generating = generating, submittedQuestion = if (conversationChanged && text.isNotBlank()) "different" else baselineQuestion,
                traversalComplete = traversalComplete)
        }
        override suspend fun sendIfMatches(text: String, targetKey: String): GeminiSendResult { sends++; return sendResult }
    }
    @Test fun existingDraftIsPreserved() = runBlocking {
        val ui = Ui().apply { text = "mine" }
        val result = GeminiQuestionSubmitter(ui, { true }).submit("new") { true }
        assertEquals(GeminiSubmissionResult.NotSent("existing_draft"), result)
        assertEquals("mine", ui.text); assertEquals(0, ui.sends)
    }
    @Test fun durableMarkerPrecedesSingleSend() = runBlocking {
        val ui = Ui(); var marked = false
        val result = GeminiQuestionSubmitter(ui, { true }).submit("question") {
            assertEquals(0, ui.sends); marked = true; true
        }
        assertTrue(marked); assertTrue(result is GeminiSubmissionResult.Sent); assertEquals(1, ui.sends)
    }
    @Test fun failedDurableMarkerPreventsSend() = runBlocking {
        val ui = Ui()
        assertEquals(GeminiSubmissionResult.NotSent("submission_not_persisted"), GeminiQuestionSubmitter(ui, { true }).submit("question") { false })
        assertEquals(0, ui.sends)
    }
    @Test fun revokedPermissionAfterMarkerPreventsSend() = runBlocking {
        val ui = Ui(); var allowed = true
        GeminiQuestionSubmitter(ui, { allowed }).submit("question") { allowed = false; true }
        assertEquals(0, ui.sends)
    }
    @Test fun rejectedClickIsUnknownAndNeverRetried() = runBlocking {
        val ui = Ui().apply { sendResult = GeminiSendResult.REJECTED }
        assertTrue(GeminiQuestionSubmitter(ui, { true }).submit("question") { true } is GeminiSubmissionResult.Unknown)
        assertEquals(1, ui.sends)
    }
    @Test fun repeatedUncorrelatableQuestionIsRejectedBeforeWrite() = runBlocking {
        val ui = Ui().apply { baselineQuestion = "question" }
        assertEquals(GeminiSubmissionResult.NotSent("submission_correlation_ambiguous"), GeminiQuestionSubmitter(ui, { true }).submit("question") { true })
        assertEquals("", ui.text); assertEquals(0, ui.sends)
    }
    @Test fun sameWindowConversationChangePreventsSend() = runBlocking {
        val ui = Ui().apply { conversationChanged = true }
        assertEquals(GeminiSubmissionResult.NotSent("conversation_changed"), GeminiQuestionSubmitter(ui, { true }).submit("question") { true })
        assertEquals(0, ui.sends)
    }
    @Test fun disabledPermissionDoesNotWrite() = runBlocking {
        val ui = Ui()
        assertTrue(GeminiQuestionSubmitter(ui, { false }).submit("question") { true } is GeminiSubmissionResult.NotSent)
        assertEquals("", ui.text); assertEquals(0, ui.sends)
    }
    @Test fun delayedControlReadinessOnlyObservesAndThenSendsOnce() = runBlocking {
        val ui = Ui().apply { sendCandidates = 0; sendEnabled = false }
        var pauses = 0; var markers = 0
        val result = GeminiQuestionSubmitter(ui, { true }, { ms ->
            assertEquals(150L, ms); pauses++
            ui.sendCandidates = 1
            if (pauses == 2) ui.sendEnabled = true
        }).submit("question") { markers++; true }
        assertTrue(result is GeminiSubmissionResult.Sent)
        assertEquals(2, pauses); assertEquals(3, ui.readyReads)
        assertEquals(1, ui.writes); assertEquals(1, markers); assertEquals(1, ui.sends)
    }
    @Test fun unavailableControlStopsAfterEightReadsWithoutSendingOrMarking() = runBlocking {
        val ui = Ui().apply { sendCandidates = 0 }; var pauses = 0; var markers = 0
        val result = GeminiQuestionSubmitter(ui, { true }, { pauses++ }).submit("question") { markers++; true }
        assertEquals(GeminiSubmissionResult.NotSent("send_unavailable"), result)
        assertEquals(8, ui.readyReads); assertEquals(7, pauses)
        assertEquals(1, ui.writes); assertEquals(0, markers); assertEquals(0, ui.sends)
    }
    @Test fun ambiguousControlsAreRejectedImmediatelyWithoutWaiting() = runBlocking {
        val ui = Ui().apply { sendCandidates = 2 }; var pauses = 0
        val result = GeminiQuestionSubmitter(ui, { true }, { pauses++ }).submit("question") { error("must not mark") }
        assertEquals(GeminiSubmissionResult.NotSent("send_unavailable"), result)
        assertEquals(1, ui.readyReads); assertEquals(0, pauses); assertEquals(0, ui.sends)
    }
    @Test fun targetChangeDuringRenderWaitStopsWithoutSending() = runBlocking {
        val ui = Ui().apply { sendCandidates = 0 }
        val result = GeminiQuestionSubmitter(ui, { true }, { ui.targetKey = "other" }).submit("question") { error("must not mark") }
        assertEquals(GeminiSubmissionResult.NotSent("target_changed"), result)
        assertEquals(2, ui.readyReads); assertEquals(0, ui.sends)
    }
    @Test fun lockDuringRenderWaitStopsBeforeAnotherRead() = runBlocking {
        val ui = Ui().apply { sendCandidates = 0 }
        val result = GeminiQuestionSubmitter(ui, { true }, { ui.lock = true }).submit("question") { error("must not mark") }
        assertEquals(GeminiSubmissionResult.NotSent("device_locked"), result)
        assertEquals(1, ui.readyReads); assertEquals(0, ui.sends)
    }
    @Test fun revokedConsentDuringRenderWaitStopsBeforeAnotherRead() = runBlocking {
        val ui = Ui().apply { sendCandidates = 0 }; var allowed = true
        val result = GeminiQuestionSubmitter(ui, { allowed }, { allowed = false }).submit("question") { error("must not mark") }
        assertEquals(GeminiSubmissionResult.NotSent("gemini_question_disabled"), result)
        assertEquals(1, ui.readyReads); assertEquals(0, ui.sends)
    }
    @Test fun revokedConsentInsideSuspendedSnapshotStopsBeforeMarker() = runBlocking {
        val ui = Ui(); var allowed = true
        ui.onReadyRead = { allowed = false }
        val result = GeminiQuestionSubmitter(ui, { allowed }, { error("must not wait") }).submit("question") { error("must not mark") }
        assertEquals(GeminiSubmissionResult.NotSent("gemini_question_disabled"), result)
        assertEquals(0, ui.sends)
    }
    @Test fun lockInsideSuspendedSnapshotStopsBeforeMarker() = runBlocking {
        val ui = Ui().apply { onReadyRead = { lock = true } }
        val result = GeminiQuestionSubmitter(ui, { true }, { error("must not wait") }).submit("question") { error("must not mark") }
        assertEquals(GeminiSubmissionResult.NotSent("device_locked"), result)
        assertEquals(0, ui.sends)
    }
    @Test fun inputTraversalAndConversationChangesDuringWaitAreRevalidated() = runBlocking {
        val cases: List<Pair<(Ui) -> Unit, String>> = listOf(
            ({ ui: Ui -> ui.text = "edited" }) to "input_changed",
            ({ ui: Ui -> ui.composerCandidates = 2 }) to "input_changed",
            ({ ui: Ui -> ui.traversalComplete = false }) to "input_changed",
            ({ ui: Ui -> ui.conversationChanged = true }) to "conversation_changed",
            ({ ui: Ui -> ui.foreground = false }) to "target_changed",
            ({ ui: Ui -> ui.generating = true }) to "generation_in_progress",
        )
        for ((change, expected) in cases) {
            val ui = Ui().apply { sendCandidates = 0 }
            val result = GeminiQuestionSubmitter(ui, { true }, { change(ui) }).submit("question") { error("must not mark") }
            assertEquals(GeminiSubmissionResult.NotSent(expected), result)
            assertEquals(1, ui.writes); assertEquals(0, ui.sends)
        }
    }

}
