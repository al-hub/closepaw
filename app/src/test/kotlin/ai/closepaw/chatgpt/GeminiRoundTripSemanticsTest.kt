package ai.closepaw.chatgpt

import org.junit.Assert.*
import org.junit.Test

class GeminiRoundTripSemanticsTest {
    @Test fun genericGoogleSearchIsNeverSend() {
        assertFalse(GeminiRoundTripSemantics.isSend("search_button", "Search"))
        assertFalse(GeminiRoundTripSemantics.isSend("", "검색"))
    }
    @Test fun requiresExactSendRole() {
        assertTrue(GeminiRoundTripSemantics.isSend("assistant_robin_send_button", ""))
        assertTrue(GeminiRoundTripSemantics.isSend("", "메시지 보내기"))
        assertFalse(GeminiRoundTripSemantics.isSend("", "Send feedback"))
    }
    @Test fun menuAndQueryCannotBecomeAnswer() {
        assertFalse(GeminiRoundTripSemantics.isReply("model_picker"))
        assertFalse(GeminiRoundTripSemantics.isReply("user_query"))
        assertFalse(GeminiRoundTripSemantics.isReply("text"))
        assertTrue(GeminiRoundTripSemantics.isReply("assistant_robin_response_text"))
    }
    @Test fun genericCopyDoesNotProveCompletion() {
        assertFalse(GeminiRoundTripSemantics.isCopy("", "Copy"))
        assertTrue(GeminiRoundTripSemantics.isCopy("copy_response_button", ""))
    }
}
