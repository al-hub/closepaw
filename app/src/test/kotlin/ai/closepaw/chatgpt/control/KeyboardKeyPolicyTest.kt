package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class KeyboardKeyPolicyTest {
    private fun key(label: String) = KeyboardKeyPolicy.identify(label, "", "android.widget.Button", true, true)
    @Test fun onlyActualSemanticButtonsWithUnambiguousLabelsAreKeys() {
        assertNull(KeyboardKeyPolicy.identify("a", "", "android.widget.TextView", true, false))
        assertNull(KeyboardKeyPolicy.identify("a", "", "android.widget.Button", false))
        assertNull(key("suggested word")); assertNull(key("token=secret")); assertNull(key("\u2028"))
        for (label in listOf("a", "ㄱ", "1", "!")) assertEquals(KeyboardKeyPolicy.Kind.CHARACTER, key(label)?.kind)
    }
    @Test fun submissionAndLayoutAreDistinctFromLiteralText() {
        assertEquals(KeyboardKeyPolicy.Kind.SUBMIT, key("Enter")?.kind)
        assertEquals(KeyboardKeyPolicy.Kind.SUBMIT, key("보내기")?.kind)
        assertEquals(KeyboardKeyPolicy.Kind.LAYOUT, key("Shift")?.kind)
        assertEquals(KeyboardKeyPolicy.Kind.LAYOUT, key("한/영")?.kind)
        assertEquals(KeyboardKeyPolicy.Kind.SPACE, key("공백")?.kind)
    }
    @Test fun exactLiteralPredictionPreservesBothSidesAndDeletesCodePoint() {
        val field = ControlNode(1, text = "A😀B", editable = true, focused = true, selectionStart = 3, selectionEnd = 3)
        assertEquals("AB", KeyboardKeyPolicy.expectedText(field, key("Backspace")!!))
        assertEquals("A😀!B", KeyboardKeyPolicy.expectedText(field, key("!")!!))
        assertNull(KeyboardKeyPolicy.expectedText(field.copy(editable = false), key("a")!!))
        assertNull(KeyboardKeyPolicy.expectedText(field, key("Enter")!!))
    }

    @Test fun unknownReturnSendAndEmojiGlyphsCannotBypassSubmissionApproval() {
        for (label in listOf("↲", "⤶", "⮐", "➤", "▶", "→", "😀")) assertNull(key(label))
        for (label in listOf("↵", "⏎", "↩", "✓", "✔")) assertEquals(KeyboardKeyPolicy.Kind.SUBMIT, key(label)?.kind)
    }
}
