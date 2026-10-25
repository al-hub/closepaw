package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class UnifiedInputPolicyTest {
    private fun field(text: String = "") = ControlNode(1, text = text, editable = true,
        empty = text.isEmpty(), focused = true, selectionStart = text.length, selectionEnd = text.length,
        setText = true, paste = true)
    @Test fun autoPrefersDirectAndPreservesExistingTextAtKnownCaret() {
        val plan = UnifiedInputPolicy.plan(field("old"), " 새A1!", "auto", "insert", false)
        assertEquals("direct", plan.method); assertEquals("old 새A1!", plan.resultText); assertNull(plan.refusal)
    }
    @Test fun replacementCannotSilentlyDestroyAnExistingDraft() {
        assertEquals("existing_text_requires_insert", UnifiedInputPolicy.plan(field("draft"), "new", "direct", "empty", false).refusal)
    }
    @Test fun clipboardNeedsExplicitDisclosureAndRealPasteSupport() {
        val node = field().copy(setText = false)
        assertEquals("clipboard_consent_required", UnifiedInputPolicy.plan(node, "abc", "auto", "empty", false).refusal)
        assertEquals("clipboard", UnifiedInputPolicy.plan(node, "abc", "auto", "empty", true).method)
        assertEquals("keyboard_observation_required", UnifiedInputPolicy.plan(node.copy(paste = false), "abc", "auto", "empty", true).refusal)
    }
    @Test fun unknownSelectionAndTruncatedTextNeverReplaceContent() {
        assertNotNull(UnifiedInputPolicy.plan(field("old").copy(selectionStart = -1), "new", "auto", "insert", true).refusal)
        assertNotNull(UnifiedInputPolicy.plan(field("x".repeat(2001)), "new", "direct", "insert", true).refusal)
    }
    @Test fun protectedUnfocusedAndMultilineInputFailBeforeAnyEffect() {
        for (node in listOf(field().copy(password = true), field().copy(focused = false), field().copy(enabled = false)))
            assertNotNull(UnifiedInputPolicy.plan(node, "abc", "auto", "empty", true).refusal)
        for (text in listOf("echo safe\n", "a\r", "a\u0000", "", "x".repeat(2001)))
            assertNotNull(UnifiedInputPolicy.plan(field(), text, "auto", "empty", true).refusal)
    }
    @Test fun explicitUnsupportedMethodDoesNotFallBack() {
        assertEquals("direct_input_unsupported", UnifiedInputPolicy.plan(field().copy(setText = false), "abc", "direct", "empty", true).refusal)
    }
    @Test fun selectionInsertionPreservesBothSides() {
        val plan = UnifiedInputPolicy.plan(field("abcd").copy(selectionStart = 1, selectionEnd = 3), "Z", "direct", "insert", false)
        assertEquals("aZd", plan.resultText)
    }
    @Test fun oversizedCombinedValueAndBadModesAreRejected() {
        assertNotNull(UnifiedInputPolicy.plan(field("a".repeat(1999)), "abc", "direct", "insert", false).refusal)
        assertNotNull(UnifiedInputPolicy.plan(field(), "abc", "magic", "empty", false).refusal)
        assertNotNull(UnifiedInputPolicy.plan(field(), "abc", "auto", "replace", false).refusal)
    }
}
