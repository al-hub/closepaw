package ai.closepaw.chatgpt.control

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class KeyboardLayoutInputTest {
    private class Port : ControlPort {
        var layout = 0
        var calls = 0
        var frozen = false
        var field = ControlNode(1, resourceId = "app:id/input", className = "EditText", bounds = "0 0 100 50",
            editable = true, focused = true, focusable = true, selectionStart = 0, selectionEnd = 0,
            sourceWindowId = 1, sourcePackage = "app")
        private fun keys() = (when (layout) {
            0 -> listOf("a", "Shift", "?123")
            1 -> listOf("A", "Shift", "?123")
            else -> listOf("1", "!", "abc")
        }).mapIndexed { i, label -> ControlNode(i + 2, text = label, keyboardKey = label,
            clickable = true, textEntryKey = true, sourceWindowId = 2, sourcePackage = "ime") }
        override suspend fun capture() = ControlFrame("app", 1, "$layout:${field.text}", listOf(field) + keys(),
            keyboard = ControlKeyboard(2, "ime", "ime/.Input"), inputTargetId = 1, inputTarget = field, touchContext = "geometry")
        override suspend fun apps(filter: String) = JsonArray(emptyList())
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            calls++
            when (node!!.keyboardKey) {
                "Shift" -> if (!frozen) layout = if (layout == 0) 1 else 0
                "?123" -> if (!frozen) layout = 2
                "abc" -> if (!frozen) layout = 0
                else -> {
                    val key = KeyboardKeyPolicy.identify(node.text, "", "", true, true)!!
                    val result = KeyboardKeyPolicy.expectedText(field, key)!!
                    field = field.copy(text = result, empty = false, selectionStart = result.length, selectionEnd = result.length)
                }
            }
            return ControlEffect(true, true, "ack")
        }
    }
    private suspend fun type(port: Port): ai.closepaw.chatgpt.operations.OperationResult {
        val control = GenericControl(port, { true })
        val observed = control.observe().data.getValue("observation_id")
        return control.action(buildJsonObject { put("action", "input"); put("method", "keyboard")
            put("text", "A1!"); put("node_id", 1); put("observation_id", observed) })
    }
    @Test fun observesShiftAndNumericLayoutBeforeTypingOnlyVerifiedKeys() = runBlocking {
        val port = Port(); val result = type(port)
        assertTrue(result.toString(), result.success); assertEquals("A1!", port.field.text)
        assertEquals(5, port.calls); assertEquals(JsonPrimitive(2), result.data["layout_steps"])
    }
    @Test fun acknowledgedButUnchangedLayoutStopsBeforeAnyLiteralOrRetry() = runBlocking {
        val port = Port().apply { frozen = true }; val result = type(port)
        assertFalse(result.success); assertEquals("", port.field.text); assertEquals(1, port.calls)
        assertEquals(JsonPrimitive(true), result.data["outcome_unknown"])
    }
}
