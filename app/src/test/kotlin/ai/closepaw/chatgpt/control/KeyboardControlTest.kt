package ai.closepaw.chatgpt.control

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class KeyboardControlTest {
    private class Port : ControlPort {
        val field = ControlNode(1, text = "A", resourceId = "example:id/input", className = "EditText", focused = true,
            focusable = true, editable = true, selectionStart = 1, selectionEnd = 1, sourceWindowId = 2, sourcePackage = "example.app")
        val key = ControlNode(2, text = "a", className = "android.widget.Button", clickable = true, sourceWindowId = 3,
            sourcePackage = "example.ime", keyboardKey = "a", textEntryKey = true)
        var frame = ControlFrame("example.app", 2, "initial", listOf(field, key), touchContext = "geometry",
            keyboard = ControlKeyboard(3, "example.ime", "example.ime/.Keyboard"), inputTargetId = 1, inputTarget = field)
        var calls = 0
        var effect = ControlEffect(true, true, "gesture_completed")
        var actual = "Aa"
        override suspend fun capture() = frame
        override suspend fun apps(filter: String) = JsonArray(emptyList())
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            calls++
            this.frame = frame.copy(fingerprint = "post", inputTarget = field.copy(text = actual))
            return effect
        }
    }
    private suspend fun observation(c: GenericControl) = c.observe().data.getValue("observation_id").jsonPrimitive.content
    private fun input(id: String, submit: Boolean = false) = buildJsonObject {
        put("action", "key"); put("node_id", 2); put("observation_id", id); put("submit_authorized", submit)
    }
    @Test fun keyNeedsCurrentInputMethodAndFocusedOwnedApplicationTarget() = runBlocking {
        for (change in listOf<(ControlFrame) -> ControlFrame>(
            { it.copy(keyboard = null) },
            { it.copy(keyboard = it.keyboard?.copy(readAllowed = false)) },
            { it.copy(keyboard = it.keyboard?.copy(interactAllowed = false)) }, { it.copy(inputTargetId = null) },
            { it.copy(inputTarget = it.inputTarget?.copy(focused = false)) },
            { it.copy(inputTarget = it.inputTarget?.copy(sourcePackage = "other.app")) },
            { it.copy(touchContext = "") }, { it.copy(truncated = true) })) {
            val port = Port(); port.frame = change(port.frame); val c = GenericControl(port, { true })
            assertFalse(c.action(input(observation(c))).success); assertEquals(0, port.calls)
        }
    }
    @Test fun keyCannotBeDispatchedAsOrdinaryClickOrTap() = runBlocking {
        for (action in listOf("click", "tap", "long_press", "focus")) {
            val port = Port(); val c = GenericControl(port, { true })
            val i = JsonObject(input(observation(c)) + ("action" to JsonPrimitive(action)))
            assertFalse(c.action(i).success); assertEquals(0, port.calls)
        }
    }
    @Test fun changedImeRotationOrKeyLayoutRejectsConsumedObservation() = runBlocking {
        for (change in listOf<(ControlFrame) -> ControlFrame>(
            { it.copy(keyboard = ControlKeyboard(4, "other.ime", "other.ime/.Keyboard")) },
            { it.copy(touchContext = "rotated") }, { it.copy(fingerprint = "shifted-layout") })) {
            val port = Port(); val c = GenericControl(port, { true }); val id = observation(c)
            port.frame = change(port.frame)
            assertFalse(c.action(input(id)).success); assertEquals(0, port.calls)
        }
    }
    @Test fun literalRequiresExactPostconditionAndNeverReplaysCancelledGesture() = runBlocking {
        for (confirmed in listOf(true, false)) {
            val port = Port().apply { if (!confirmed) { actual = "other"; effect = ControlEffect(true, false, "gesture_cancelled") } }
            val c = GenericControl(port, { true }); val id = observation(c); val result = c.action(input(id))
            assertEquals(confirmed, result.success)
            assertEquals(JsonPrimitive(!confirmed), result.data["outcome_unknown"])
            assertFalse(c.action(input(id)).success); assertEquals(1, port.calls)
        }
    }
    @Test fun enterAndItsGlyphRequireSeparateAuthorizationAndDoNotClaimCommandSuccess() = runBlocking {
        for (label in listOf("Enter", "⏎", "보내기")) {
            val port = Port(); port.frame = port.frame.copy(nodes = listOf(port.field, port.key.copy(text = label, keyboardKey = label)))
            val c = GenericControl(port, { true })
            assertFalse(c.action(input(observation(c))).success); assertEquals(0, port.calls)
            val result = c.action(input(observation(c), true))
            assertFalse(result.success); assertEquals(JsonPrimitive(true), result.data["outcome_unknown"])
            assertEquals(JsonPrimitive(false), result.data["app_result_verified"]); assertEquals(1, port.calls)
        }
    }
    @Test fun terminalAndComposedTextStayUnknownInsteadOfFalseLiteralPass() = runBlocking {
        for (terminal in listOf(true, false)) {
            val port = Port().apply { actual = "가" }
            val field = port.field.copy(editable = !terminal)
            port.frame = port.frame.copy(inputTarget = field, nodes = listOf(field, port.key))
            val c = GenericControl(port, { true }); val result = c.action(input(observation(c)))
            assertFalse(result.success); assertEquals(if (terminal) 0 else 1, port.calls)
            if (!terminal) assertEquals(JsonPrimitive(true), result.data["outcome_unknown"])
        }
    }
}
