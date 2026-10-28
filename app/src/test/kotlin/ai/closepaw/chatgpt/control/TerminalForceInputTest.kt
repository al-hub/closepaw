package ai.closepaw.chatgpt.control

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TerminalForceInputTest {
    private fun node(echo: String) = ControlNode(1,
        className = "com.termux.view.TerminalView", resourceId = "com.termux:id/terminal_view",
        bounds = "0 0 200 300", focused = true, sourceWindowId = 1, sourcePackage = "com.termux",
        terminal = TerminalEvidence(echo, 3, 11))

    private class Port(initial: ControlNode) : ControlPort {
        var frame = ControlFrame("com.termux", 1, "before", listOf(initial))
        var calls = 0
        var after: ControlNode? = initial
        override suspend fun capture() = frame
        override suspend fun apps(filter: String) = JsonArray(emptyList())
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            calls++
            this.frame = frame.copy(fingerprint = "after", nodes = listOfNotNull(after))
            return ControlEffect(true, true, "terminal_input_dispatched")
        }
    }
    private suspend fun request(control: GenericControl, force: Boolean) = buildJsonObject {
        put("action", "terminal_input"); put("node_id", 1)
        put("observation_id", control.observe().data.getValue("observation_id"))
        put("text", "X"); put("terminal_authorized", true); put("terminal_force_authorized", force)
    }

    @Test fun forceIsRefusedBeforeDispatchWhenTerminalSelectionCannotBeProvenAbsent() = runBlocking {
        // The same IME 0/0 can coexist with different terminal screen selections.
        // Neither an empty prompt nor a nonempty draft proves a selection is absent.
        for (echo in listOf("~ $", "~ $ echo ABCD", "custom> existing")) {
            val port = Port(node(echo))
            val control = GenericControl(port, { true })
            val result = control.action(request(control, true))
            assertFalse(result.success)
            assertEquals(JsonPrimitive(false), result.data["action_attempted"])
            assertEquals(JsonPrimitive("terminal_selection_unverified"), result.data["reason_code"])
            assertEquals(0, port.calls)
        }
    }

    @Test fun forceRefusalDoesNotOverrideAuthorizationOrSensitiveTargetGuards() {
        assertEquals("terminal_input_authorization_required", TerminalInputPolicy.refusal(node("~ $"), "X", false, true))
        assertEquals("terminal_target_unverified", TerminalInputPolicy.refusal(node("~ $").copy(password = true), "X", true, true))
        assertEquals("sensitive_input_refused", TerminalInputPolicy.refusal(node("Password:"), "X", true, true))
        assertEquals("terminal_selection_unverified", TerminalInputPolicy.refusal(node("~ $"), "X", true, true))
    }

    @Test fun normalEmptyPromptStillDispatchesAndUnknownIsNotReplayed() = runBlocking {
        val target = node("~ $")
        val port = Port(target)
        val control = GenericControl(port, { true })
        val input = request(control, false)
        val result = control.action(input)
        assertFalse(result.success)
        assertEquals(JsonPrimitive(true), result.data["action_attempted"])
        assertEquals(JsonPrimitive(true), result.data["outcome_unknown"])
        assertFalse(control.action(input).success)
        assertEquals(1, port.calls)
    }

    @Test fun nativeImeBufferRejectsNonemptyAndInvalidSelectionButDoesNotProveTerminalSelection() {
        assertFalse(TerminalInputPolicy.nativeCursorSafe(null, 0, 0))
        assertFalse(TerminalInputPolicy.nativeCursorSafe("existing", 0, 0))
        assertFalse(TerminalInputPolicy.nativeCursorSafe("", 0, 1))
        assertFalse(TerminalInputPolicy.nativeCursorSafe("", -1, -1))
        assertTrue(TerminalInputPolicy.nativeCursorSafe("", 0, 0))
        assertEquals("terminal_selection_unverified", TerminalInputPolicy.refusal(node("~ $"), "X", true, true))
    }
}
