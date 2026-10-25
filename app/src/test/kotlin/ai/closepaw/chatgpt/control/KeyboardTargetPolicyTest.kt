package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class KeyboardTargetPolicyTest {
    private val field = ControlNode(1, "draft", resourceId = "app:id/input", bounds = "0 0 200 100", className = "EditText",
        enabled = true, focused = true, focusable = true, editable = true, sourceWindowId = 2, sourcePackage = "app.test",
        selectionStart = 5, selectionEnd = 5)
    @Test fun finalBoundaryRejectsFocusCaretTextGeometryAndAuthenticationRaces() {
        assertNull(KeyboardTargetPolicy.refusal(field, field))
        for (live in listOf(null, field.copy(focused = false), field.copy(selectionStart = 0), field.copy(text = "new"),
            field.copy(bounds = "0 0 100 100"), field.copy(password = true), field.copy(enabled = false),
            field.copy(sourceWindowId = 3), field.copy(sourcePackage = "other.app"), field.copy(resourceId = "other"),
            field.copy(editable = false), field.copy(className = "OtherView")))
            assertNotNull(KeyboardTargetPolicy.refusal(field, live))
    }
    @Test fun unreadableTerminalCanvasIsNotProofOfSafePrompt() {
        val terminal = field.copy(editable = false, text = "")
        assertNotNull(KeyboardTargetPolicy.refusal(terminal, terminal))
    }
}
