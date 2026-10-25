package ai.closepaw.chatgpt.control
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class TerminalInputPolicyTest {
    private fun node(echo: String = "~ $") = ControlNode(1, className = "com.termux.view.TerminalView", resourceId = "com.termux:id/terminal_view",
        bounds = "0 0 200 300", focused = true, focusable = true, sourceWindowId = 1, sourcePackage = "com.termux", terminal = TerminalEvidence(echo, 3, 11))
    @Test fun onlyDocumentedNullClassTerminalModesAreAccepted() {
        assertTrue(TerminalInputPolicy.inputTypeAllowed(0)); assertTrue(TerminalInputPolicy.inputTypeAllowed(0x00080090))
        for (type in listOf(0x81,0x91,0x80091,0x12,0x01)) assertFalse(TerminalInputPolicy.inputTypeAllowed(type))
    }
    @Test fun wholeSingleLineInputRequiresRealBindingEmptyPromptAndExplicitConsent() {
        assertNull(TerminalInputPolicy.refusal(node(), "printf closepaw_ok", true))
        assertNull(TerminalInputPolicy.refusal(node().copy(className="android.view.View"),"hello",true))
        assertNotNull(TerminalInputPolicy.refusal(node().copy(className="android.widget.EditText"),"hello",true))
        for (target in listOf(node().copy(terminal = null), node().copy(focused = false), node("Password:"), node("$ existing"), node().copy(password = true), node().copy(sourcePackage = "other")))
            assertNotNull(TerminalInputPolicy.refusal(target, "hello", true))
        for (text in listOf("echo x\n", "a\r", "token=abc", "a\u202Eb", "", "hello ")) assertNotNull(TerminalInputPolicy.refusal(node(), text, true))
        assertNotNull(TerminalInputPolicy.refusal(node(), "hello", false))
    }
    @Test fun echoVerificationDoesNotAcceptAnotherWindowBindingPartialOrUnchangedOutput() {
        val before = node().terminal!!
        assertTrue(TerminalInputPolicy.inserted(before, before.copy(echo = "~ $ 한글 A1!"), "한글 A1!"))
        for (after in listOf(before, before.copy(echo = "~ $ 한글"), before.copy(echo = "~ $ hello", generation = 4)))
            assertFalse(TerminalInputPolicy.inserted(before, after, "hello"))
    }
    @Test fun submissionIsSeparateAndRequiresExactPendingCommandAndNewOutput() {
        val pending=node("~ $ printf fixture_ok")
        val issued=TerminalInputPolicy.submission(node().terminal!!,pending.terminal!!,"printf fixture_ok")
        assertNotNull(issued)
        assertNull(TerminalInputPolicy.submitRefusal(pending,"printf fixture_ok",true,true,issued))
        assertNotNull(TerminalInputPolicy.submitRefusal(pending,"printf fixture_ok",true,true))
        assertNotNull(TerminalInputPolicy.submitRefusal(pending,"printf other",true,true))
        assertNotNull(TerminalInputPolicy.submitRefusal(pending,"printf fixture_ok",true,false))
        val before=pending.terminal!!; val after=before.copy(echo=before.echo+"\nfixture_ok\n~ $")
        assertTrue(TerminalInputPolicy.submitted(before,after,"printf fixture_ok","fixture_ok"))
        assertFalse(TerminalInputPolicy.submitted(before,before,"printf fixture_ok","fixture_ok"))
        assertFalse(TerminalInputPolicy.submitted(before,after.copy(generation=4),"printf fixture_ok","fixture_ok"))
    }
    @Test fun dollarInPendingCommandCannotBeMistakenForAnApprovedPrompt() = runBlocking {
        val attack=node("~ $ touch /tmp/unapproved; # $ printf ok")
        val issued=TerminalInputPolicy.submission(node().terminal!!,node("~ $ printf ok").terminal!!,"printf ok")
        assertNotNull(TerminalInputPolicy.submitRefusal(attack,"printf ok",true,true,issued))
        assertFalse(TerminalInputPolicy.emptyPrompt("~ $ touch /tmp/unapproved; # $"))
        assertNull(TerminalInputPolicy.submission(attack.terminal!!,attack.terminal!!.copy(echo=attack.terminal!!.echo+" x"),"x"))
        var calls=0
        val port=object:ControlPort {
            override suspend fun capture()=ControlFrame("com.termux",1,"same",listOf(attack))
            override suspend fun apps(filter:String)=JsonArray(emptyList())
            override suspend fun perform(frame:ControlFrame,input:JsonObject,node:ControlNode?):ControlEffect { calls++; return ControlEffect(true,true,"unexpected") }
        }
        val control=GenericControl(port,{true});val observed=control.observe()
        val result=control.action(buildJsonObject { put("action","terminal_submit");put("node_id",1);put("observation_id",observed.data.getValue("observation_id"));put("text","printf ok");put("terminal_authorized",true);put("submit_authorized",true) })
        assertFalse(result.success);assertEquals(0,calls);assertEquals(JsonPrimitive(false),result.data["action_attempted"])
    }
    @Test fun realControlPathDispatchesOnceAndVerifiesWholeTerminalEcho() = runBlocking {
        class Port : ControlPort {
            var target = node(); var calls = 0
            override suspend fun capture() = ControlFrame("com.termux", 1, target.terminal!!.echo, listOf(target))
            override suspend fun apps(filter: String) = JsonArray(emptyList())
            override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
                calls++; target = target.copy(terminal = target.terminal!!.copy(echo =
                    if(input["action"]?.jsonPrimitive?.content == "terminal_submit") "~ $ printf closepaw_ok\nclosepaw_ok\n~ $" else "~ $ printf closepaw_ok"))
                return ControlEffect(true, true, "terminal_input_dispatched")
            }
        }
        val port = Port(); val control = GenericControl(port, { true })
        val id = control.observe().data.getValue("observation_id")
        val input = buildJsonObject { put("action", "terminal_input"); put("node_id", 1); put("observation_id", id)
            put("terminal_authorized", true); put("text", "printf closepaw_ok") }
        assertTrue(control.action(input).success); assertFalse(control.action(input).success); assertEquals(1, port.calls)
        val pending=control.observe()
        val submit=buildJsonObject { put("action","terminal_submit");put("node_id",1);put("observation_id",pending.data.getValue("observation_id"));put("text","printf closepaw_ok");put("expected_output","closepaw_ok");put("terminal_authorized",true);put("submit_authorized",true) }
        val submitted=control.action(submit)
        assertTrue(submitted.toString(),submitted.success);assertEquals(2,port.calls)
        assertFalse(control.action(submit).success);assertEquals(2,port.calls)
    }
}
