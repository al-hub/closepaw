package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class KeyboardCursorProofTest {
    private val field = ControlNode(1, resourceId = "app:id/editor", enabled = true, editable = true,
        focused = true, focusable = true, bounds = "0 0 100 50", sourcePackage = "app", sourceWindowId = 3)

    @Test fun onlyEmptyNativeTextWithKnownZeroOffsetAndCollapsedZeroSelectionProvesEmpty() {
        assertTrue(KeyboardCursorProof.provesEmpty(0, 0, 0, 0))
        for ((length, start, end, offset) in listOf(
            listOf(1, 0, 0, 0), listOf(0, -1, -1, 0), listOf(0, 0, 1, 0), listOf(0, 1, 1, 0),
            listOf(0, 0, 0, -1), listOf(0, 0, 0, 1))) {
            assertFalse(KeyboardCursorProof.provesEmpty(length, start, end, offset))
        }
    }

    @Test fun noReadForNonemptyUnboundSensitiveOrAlreadyProvenFields() {
        assertTrue(KeyboardCursorProof.needsProof(field))
        for (unsafe in listOf(field.copy(text = "draft"), field.copy(empty = false), field.copy(password = true),
            field.copy(resourceId = ""), field.copy(sourceWindowId = -1), field.copy(sourcePackage = ""),
            field.copy(focused = false), field.copy(selectionStart = 0), field.copy(selectionStart = 0, selectionEnd = 0))) {
            assertFalse(KeyboardCursorProof.needsProof(unsafe))
        }
    }

    @Test fun aPreviouslyBoundCaretCannotBeReusedOnAnotherGeneration() {
        val proven = field.copy(selectionStart = 0, selectionEnd = 0, keyboardCursorGeneration = 5)
        assertNull(KeyboardTargetPolicy.refusal(proven, proven))
        assertNotNull(KeyboardTargetPolicy.refusal(proven, proven.copy(keyboardCursorGeneration = 6)))
        assertNotNull(KeyboardTargetPolicy.refusal(proven, proven.copy(keyboardCursorGeneration = null)))
    }
}
