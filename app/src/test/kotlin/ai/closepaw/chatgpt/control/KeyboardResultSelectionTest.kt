package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class KeyboardResultSelectionTest {
    private fun verify(action: String, original: String, start: Int, end: Int, label: String,
        actual: String, actualStart: Int, actualEnd: Int): Boolean {
        val field = ControlNode(1, text = original, resourceId = "app:id/input", className = "EditText",
            bounds = "0 0 100 50", sourceWindowId = 1, sourcePackage = "app", editable = true,
            focused = true, focusable = true, selectionStart = start, selectionEnd = end)
        val key = ControlNode(2, text = label, keyboardKey = label, clickable = true, textEntryKey = true,
            visualKeyCandidate = true, sourceWindowId = 2, sourcePackage = "ime")
        val before = ControlFrame("app", 1, "before", listOf(field, key),
            keyboard = ControlKeyboard(2, "ime", "ime/.Keyboard"), inputTargetId = 1, inputTarget = field)
        val changed = field.copy(text = actual, selectionStart = actualStart, selectionEnd = actualEnd)
        val after = before.copy(fingerprint = "after", nodes = listOf(changed, key), inputTarget = changed)
        return ControlResultPolicy.verify(action, before, after, key, buildJsonObject {}).confirmed
    }

    @Test fun exactLiteralTextWithMovedSelectedOrUnknownCaretStaysUnconfirmed() {
        for (action in listOf("key", "visual_key")) {
            assertTrue(verify(action, "AB", 1, 1, "x", "AxB", 2, 2))
            for ((start, end) in listOf(0 to 0, 1 to 1, 2 to 3, -1 to -1))
                assertFalse("$action selection $start..$end", verify(action, "AB", 1, 1, "x", "AxB", start, end))
        }
    }

    @Test fun replacementSpaceBackspaceAndHangulRequireTheirOwnExpectedCollapsedCaret() {
        for (action in listOf("key", "visual_key")) {
            assertTrue(verify(action, "ABCD", 1, 3, "x", "AxD", 2, 2))
            assertTrue(verify(action, "AB", 1, 1, "Space", "A B", 2, 2))
            assertTrue(verify(action, "A😀B", 3, 3, "Backspace", "AB", 1, 1))
            assertTrue(verify(action, "ABCD", 1, 3, "Backspace", "AD", 1, 1))
            assertTrue(verify(action, "ㄱ", 1, 1, "ㅏ", "가", 1, 1))
            assertFalse(verify(action, "ㄱ", 1, 1, "ㅏ", "가", 0, 0))
        }
    }
}
