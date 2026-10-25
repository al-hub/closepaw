package ai.closepaw.chatgpt.control

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class KeyboardTextInputTest {
    private class Port : ControlPort {
        var calls = 0
        var mismatchAt = -1
        var field = ControlNode(1, resourceId = "app:id/input", className = "EditText", bounds = "0 0 100 50",
            editable = true, focused = true, focusable = true, selectionStart = 0, selectionEnd = 0,
            sourceWindowId = 1, sourcePackage = "app")
        val keyboard = ControlKeyboard(2, "ime", "ime/.Input")
        val keys = listOf("ㅎ", "ㅏ", "ㄴ", "ㄱ", "ㅡ", "ㄹ", "Space", "A", "1", "!").mapIndexed { index, label ->
            ControlNode(index + 2, text = label, keyboardKey = label, clickable = true, textEntryKey = true,
                sourceWindowId = 2, sourcePackage = "ime")
        }
        override suspend fun capture() = ControlFrame("app", 1, field.text, listOf(field) + keys,
            keyboard = keyboard, inputTargetId = 1, inputTarget = field, touchContext = "stable")
        override suspend fun apps(filter: String) = JsonArray(emptyList())
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            calls++
            val key = KeyboardKeyPolicy.identify(node!!.text, "", "", true, true)!!
            val text = if (calls == mismatchAt) "unexpected" else KeyboardKeyPolicy.expectedText(field, key)!!
            field = field.copy(text = text, empty = false, selectionStart = text.length, selectionEnd = text.length)
            return ControlEffect(true, true, "ack")
        }
    }
    private suspend fun execute(port: Port, text: String): ai.closepaw.chatgpt.operations.OperationResult {
        val control = GenericControl(port, { true })
        val observation = control.observe().data.getValue("observation_id")
        return control.action(buildJsonObject { put("action", "input"); put("method", "keyboard")
            put("node_id", 1); put("text", text); put("observation_id", observation) })
    }
    @Test fun automaticKeyboardRouteVerifiesHangulAndMixedLiteralText() = runBlocking {
        val port = Port(); val result = execute(port, "한글 A1!")
        assertTrue(result.toString(), result.success); assertEquals("한글 A1!", port.field.text)
        assertEquals(10, port.calls); assertEquals(JsonPrimitive(false), result.data["outcome_unknown"])
    }
    @Test fun mismatchedCompositionStopsImmediatelyWithoutRemainingKeys() = runBlocking {
        val port = Port().apply { mismatchAt = 2 }
        val result = execute(port, "한글")
        assertFalse(result.success); assertEquals(2, port.calls)
        assertEquals(JsonPrimitive(true), result.data["outcome_unknown"])
    }
    @Test fun missingKeyRefusesBeforeAnyPartialInput() = runBlocking {
        val port = Port(); assertFalse(execute(port, "한글?").success); assertEquals(0, port.calls)
    }
}
