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

    @Test fun firstVisualCharacterToleratesOnlyUniqueOwnedWebEditorResizing() {
        val pkg = "example.otherbrowser"
        val ime = ControlKeyboard(2, "ime", "ime/.Ime")
        val field = ControlNode(6, parent = 5, className = "android.widget.EditText",
            bounds = "10 100 300 150", sourceWindowId = 1, sourcePackage = pkg, editable = true,
            focused = true, focusable = true, selectionStart = 0, selectionEnd = 0, webEditorContainer = 2)
        val key = ControlNode(9, text = "n", keyboardKey = "n", visualKeyCandidate = true,
            sourceWindowId = ime.windowId, sourcePackage = ime.packageName)
        val before = ControlFrame(pkg, 1, "before", listOf(field, key), windowContext = "same",
            keyboard = ime, inputTarget = field, inputTargetId = field.id)
        val resized = field.copy(bounds = "10 100 245 150", text = "n", selectionStart = 1, selectionEnd = 1)
        fun verify(next: ControlNode, additional: List<ControlNode> = emptyList(), win: String = "same"): Boolean {
            val after = before.copy(fingerprint = "after", nodes = listOf(next, key) + additional,
                inputTarget = next, inputTargetId = next.id, windowContext = win)
            return ControlResultPolicy.verify("visual_key", before, after, key, buildJsonObject {}).confirmed
        }
        assertTrue(verify(resized))
        assertFalse(verify(resized.copy(parent = 16)))
        assertFalse(verify(resized.copy(webEditorContainer = 22)))
        assertFalse(verify(resized.copy(bounds = "10 160 245 200")))
        assertFalse(verify(resized.copy(text = "nv")))
        assertFalse(verify(resized.copy(text = "nvidia123")))
        assertFalse(verify(resized.copy(focused = false)))
        assertFalse(verify(resized.copy(nativeEditorId = "a-different-editor")))
        assertFalse(verify(resized.copy(selectionStart = 0)))
        assertFalse(verify(resized, listOf(resized.copy(id = 22))))
        assertFalse(verify(resized, win = "other"))
    }
}
