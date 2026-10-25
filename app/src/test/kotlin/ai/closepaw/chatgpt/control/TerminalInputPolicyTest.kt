package ai.closepaw.chatgpt.control
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class TerminalInputPolicyTest {
    private fun node(echo: String = "~ $") = ControlNode(1, className = "com.termux.view.TerminalView", resourceId = "com.termux:id/terminal_view",
        bounds = "0 0 200 300", focused = true, focusable = true, sourceWindowId = 1, sourcePackage = "com.termux", terminal = TerminalEvidence(echo, 3, 11))
    @Test fun wholeSingleLineInputRequiresRealBindingEmptyPromptAndExplicitConsent() {
        assertNull(TerminalInputPolicy.refusal(node(), "printf closepaw_ok", true))
        for (target in listOf(node().copy(terminal = null), node().copy(focused = false), node("Password:"), node("$ existing"), node().copy(password = true), node().copy(sourcePackage = "other")))
            assertNotNull(TerminalInputPolicy.refusal(target, "hello", true))
        for (text in listOf("echo x\n", "a\r", "token=abc", "", "hello ")) assertNotNull(TerminalInputPolicy.refusal(node(), text, true))
        assertNotNull(TerminalInputPolicy.refusal(node(), "hello", false))
    }
    @Test fun echoVerificationDoesNotAcceptAnotherWindowBindingPartialOrUnchangedOutput() {
        val before = node().terminal!!
        assertTrue(TerminalInputPolicy.inserted(before, before.copy(echo = "~ $ 한글 A1!"), "한글 A1!"))
        for (after in listOf(before, before.copy(echo = "~ $ 한글"), before.copy(echo = "~ $ hello", generation = 4)))
            assertFalse(TerminalInputPolicy.inserted(before, after, "hello"))
    }
    @Test fun realControlPathDispatchesOnceAndVerifiesWholeTerminalEcho() = runBlocking {
        class Port : ControlPort {
            var target = node(); var calls = 0
            override suspend fun capture() = ControlFrame("com.termux", 1, target.terminal!!.echo, listOf(target))
            override suspend fun apps(filter: String) = JsonArray(emptyList())
            override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
                calls++; target = target.copy(terminal = target.terminal!!.copy(echo = "~ $ printf closepaw_ok"))
                return ControlEffect(true, true, "terminal_input_dispatched")
            }
        }
        val port = Port(); val control = GenericControl(port, { true })
        val id = control.observe().data.getValue("observation_id")
        val input = buildJsonObject { put("action", "terminal_input"); put("node_id", 1); put("observation_id", id)
            put("terminal_authorized", true); put("text", "printf closepaw_ok") }
        assertTrue(control.action(input).success); assertFalse(control.action(input).success); assertEquals(1, port.calls)
    }
}
