package ai.closepaw.chatgpt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GeminiQuestionSubmitterTest {
    private class Ui : GeminiRoundTripUi {
        var text = ""; var sends = 0; var lock = false; var foreground = true
        var baselineQuestion: String? = null; var conversationChanged = false
        var sendResult = GeminiSendResult.CLICKED
        override suspend fun isLocked() = lock
        override suspend fun launch() = foreground
        override suspend fun inspect() = DraftComposerState(foreground, 1, text)
        override suspend fun setIfEmpty(text: String): DraftWriteResult {
            if (this.text.isNotEmpty()) return DraftWriteResult.EXISTING_DRAFT
            this.text = text; return DraftWriteResult.WRITTEN
        }
        override suspend fun matches(text: String) = this.text == text
        override suspend fun snapshot() = GeminiUiSnapshot(foreground, "target", 1, text, 1, true, submittedQuestion = if (conversationChanged && text.isNotBlank()) "different" else baselineQuestion)
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
}
