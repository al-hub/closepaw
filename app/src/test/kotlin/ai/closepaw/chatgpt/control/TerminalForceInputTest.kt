package ai.closepaw.chatgpt.control

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TerminalForceInputTest {
    private fun node(echo: String = "custom> existing") = ControlNode(1,
        className = "com.termux.view.TerminalView", resourceId = "com.termux:id/terminal_view",
        bounds = "0 0 200 300", focused = true, sourceWindowId = 1, sourcePackage = "com.termux",
        terminal = TerminalEvidence(echo, 3, 11))

    private class Port(initial: ControlNode, private val expectedText: String = "x") : ControlPort {
        var frame = ControlFrame("com.termux", 1, "before", listOf(initial))
        var calls = 0
        var after: ControlNode? = initial.copy(terminal = initial.terminal!!.copy(echo = initial.terminal.echo + "x"))
        var acknowledged = true
        var controlSession: ControlSession? = null
        override fun session() = controlSession
        override suspend fun capture() = frame
        override suspend fun apps(filter: String) = JsonArray(emptyList())
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            assertEquals("terminal_input", input.getValue("action").jsonPrimitive.content)
            assertEquals(expectedText, input.getValue("text").jsonPrimitive.content)
            calls++
            this.frame = frame.copy(fingerprint = "after", nodes = listOfNotNull(after))
            return ControlEffect(true, acknowledged, "terminal_input_dispatched")
        }
    }

    private suspend fun request(control: GenericControl, force: Boolean = true, action: String = "terminal_input",
        authorized: Boolean = true) = buildJsonObject {
        put("action", action); put("node_id", 1)
        put("observation_id", control.observe().data.getValue("observation_id"))
        put("text", "x"); put("terminal_authorized", authorized); put("terminal_force_authorized", force)
    }

    @Test fun forceRequiresSeparateExplicitConsentAndNormalTerminalAuthorization() = runBlocking {
        for (force in listOf(false, true)) {
            val port = Port(node()); val control = GenericControl(port, { true })
            val result = control.action(request(control, force))
            assertEquals(force, result.success); assertEquals(if (force) 1 else 0, port.calls)
        }
        val port = Port(node()); val control = GenericControl(port, { true })
        assertFalse(control.action(request(control, authorized = false)).success)
        assertEquals(0, port.calls)
        assertEquals("empty_terminal_prompt_required", TerminalInputPolicy.refusal(node(), "x", true))
    }

    @Test fun forceKeepsSensitiveTargetBindingAndSingleLineGuards() {
        for (target in listOf(node().copy(terminal = null), node().copy(focused = false),
            node().copy(enabled = false), node().copy(password = true), node().copy(sourcePackage = "other"),
            node().copy(resourceId = "other"), node().copy(className = "EditText"),
            node("Password:"), node("token=abc\ncustom>"), node("custom\u202E>"))) {
            assertNotNull(TerminalInputPolicy.refusal(target, "x", true, true))
        }
        for (text in listOf("password=abc", "a\n", "a\r", "a\u2028b", "a\u2029b", "a\u202Eb", "", "x "))
            assertNotNull(TerminalInputPolicy.refusal(node(), text, true, true))
    }

    @Test fun forcePreservesExistingEchoAndNeverReplaysConsumedObservation() = runBlocking {
        for (echo in listOf("custom>", "~ $ existing", "custom> existing")) {
            val port = Port(node(echo)); val control = GenericControl(port, { true })
            val input = request(control)
            assertTrue(control.action(input).success)
            assertEquals(echo + "x", port.frame.nodes.single().terminal!!.echo)
            assertFalse(control.action(input).success); assertEquals(1, port.calls)
        }
    }

    @Test fun uncertainEchoOrChangedBindingNeverConfirmsOrReplays() = runBlocking {
        val initial = node(); val proof = initial.terminal!!
        for (after in listOf(null, initial, initial.copy(terminal = proof.copy(echo = "x")),
            initial.copy(terminal = proof.copy(echo = "custom> exxisting")),
            initial.copy(terminal = proof.copy(echo = proof.echo + "x", generation = 4)),
            initial.copy(terminal = proof.copy(echo = proof.echo + "x", fieldId = 12)),
            initial.copy(terminal = proof.copy(echo = proof.echo + "x"), focused = false))) {
            val port = Port(initial).apply { this.after = after }; val control = GenericControl(port, { true })
            val input = request(control); val result = control.action(input)
            assertFalse(result.success); assertEquals(JsonPrimitive(true), result.data["outcome_unknown"])
            assertFalse(control.action(input).success); assertEquals(1, port.calls)
        }
        val port = Port(initial).apply { acknowledged = false }; val control = GenericControl(port, { true })
        val input = request(control)
        assertFalse(control.action(input).success); assertFalse(control.action(input).success); assertEquals(1, port.calls)
    }

    @Test fun middleInsertionWithoutTrustedCursorEvidenceRemainsUnknownAndIsNotReplayed() = runBlocking {
        val initial = node("~ $ echo ABCD")
        val port = Port(initial, "X").apply {
            after = initial.copy(terminal = initial.terminal!!.copy(echo = "~ $ echo ABCXD"))
        }
        val control = GenericControl(port, { true })
        val input = buildJsonObject {
            put("action", "terminal_input"); put("node_id", 1)
            put("observation_id", control.observe().data.getValue("observation_id"))
            put("terminal_authorized", true); put("terminal_force_authorized", true); put("text", "X")
        }
        val result = control.action(input)
        assertFalse(result.success)
        assertEquals(JsonPrimitive(true), result.data["action_attempted"])
        assertEquals(JsonPrimitive(true), result.data["action_acknowledged"])
        assertEquals(JsonPrimitive(true), result.data["outcome_unknown"])
        assertEquals(JsonPrimitive("terminal_echo_unconfirmed"), result.data["reason_code"])
        assertFalse(control.action(input).success)
        assertEquals(1, port.calls)
    }

    @Test fun changedTargetSessionOrRevokedControlStopsBeforeDispatch() = runBlocking {
        val port = Port(node()); var allowed = true; val control = GenericControl(port, { allowed })
        val input = request(control)
        port.frame = port.frame.copy(fingerprint = "changed-target")
        assertFalse(control.action(input).success); assertEquals(0, port.calls)
        val next = request(control); allowed = false
        assertFalse(control.action(next).success); assertEquals(0, port.calls)

        val session = ControlSession().apply { activateLegacy() }
        val sessionPort = Port(node()).apply { controlSession = session }
        val sessionControl = GenericControl(sessionPort, { session.state == ControlSession.State.ACTIVE })
        val old = request(sessionControl)
        session.pause(); session.beginResume(); session.finishResume(session.epoch, true)
        assertFalse(sessionControl.action(old).success); assertEquals(0, sessionPort.calls)
        val fresh = request(sessionControl); session.end()
        assertFalse(sessionControl.action(fresh).success); assertEquals(0, sessionPort.calls)
    }

    @Test fun forcedDraftInsertionCannotAuthorizeEnterEvenWithSubmitFlag() = runBlocking {
        val port = Port(node()); val control = GenericControl(port, { true })
        assertTrue(control.action(request(control)).success)
        val submit = request(control, action = "terminal_submit")
        assertFalse(control.action(submit).success); assertEquals(1, port.calls)
        val approved = JsonObject(request(control, action = "terminal_submit") + ("submit_authorized" to JsonPrimitive(true)))
        assertFalse(control.action(approved).success); assertEquals(1, port.calls)
        assertNull(TerminalInputPolicy.submission(node().terminal!!, port.frame.nodes.single().terminal!!, "x"))
    }

    @Test fun nativeCursorContextRejectsSelectionAndExistingText() {
        assertFalse(TerminalInputPolicy.nativeCursorSafe(null, 0, 0))
        assertFalse(TerminalInputPolicy.nativeCursorSafe("existing", 0, 0))
        assertFalse(TerminalInputPolicy.nativeCursorSafe("", 1, 1))
        assertFalse(TerminalInputPolicy.nativeCursorSafe("", 0, 1))
        assertFalse(TerminalInputPolicy.nativeCursorSafe("", -1, -1))
        assertTrue(TerminalInputPolicy.nativeCursorSafe("", 0, 0))
    }
}
