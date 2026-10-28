package ai.closepaw.chatgpt.control

import kotlinx.coroutines.runBlocking
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import android.text.InputType
import io.mockk.every
import io.mockk.mock
import io.mockk.clearMocks
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TerminalForceInputTest {
    private fun node(echo: String = "custom> existing") = ControlNode(1,
        className = "com.termux.view.TerminalView", resourceId = "com.termux:id/terminal_view",
        bounds = "0 0 200 300", focused = true, sourceWindowId = 1, sourcePackage = "com.termux",
        terminal = TerminalEvidence(echo, 3, 11))

    private class Port(initial: ControlNode) : ControlPort {
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
            assertEquals("x", input.getValue("text").jsonPrimitive.content)
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

    @Test fun captureRespectsCursorAndSelectionSafety() {
        val mockService = mock<AgentService>()
        val mockInputMethod = mock<GuardedAccessibilityInputMethod>()
        val mockConnection = mock<InputConnection>()

        // Common setup for mockInputMethod
        every { mockInputMethod.generation } returns 1L
        every { mockInputMethod.currentInputStarted } returns true
        every { mockInputMethod.currentInputEditorInfo } returns EditorInfo().apply {
            packageName = "com.termux"
            fieldId = 1
            inputType = InputType.TYPE_CLASS_TEXT
        }
        every { mockService.inputMethod } returns mockInputMethod
        every { mockService.packageManager } returns mockk() // Mock PackageManager
        // Mock getResourcesForApplication to avoid NullPointerException for resource name lookup
        every { mockService.packageManager.getResourcesForApplication(any()) } returns mockk()
        every { mockService.packageManager.getResourcesForApplication(any()).getResourceName(any()) } returns "com.termux:id/terminal_view"


        // Case 1: Existing text (unsafe)
        every { mockInputMethod.currentInputConnection } returns mockConnection
        every { mockConnection.getSurroundingText(any(), any(), any()) }
            .returns(ExtractedText().apply { text = "existing text" })
        assertNull(TerminalInputBinding.capture(mockService))

        // Case 2: Selection exists (unsafe)
        clearMocks(mockConnection) // Clear previous stubbing
        every { mockInputMethod.currentInputConnection } returns mockConnection
        every { mockConnection.getSurroundingText(any(), any(), any()) }
            .returns(ExtractedText().apply { text = ""; selectionStart = 1; selectionEnd = 2 })
        assertNull(TerminalInputBinding.capture(mockService))

        // Case 3: No text, no selection (safe)
        clearMocks(mockConnection) // Clear previous stubbing
        every { mockInputMethod.currentInputConnection } returns mockConnection
        every { mockConnection.getSurroundingText(any(), any(), any()) }
            .returns(ExtractedText().apply { text = ""; selectionStart = 0; selectionEnd = 0 })
        assertNotNull(TerminalInputBinding.capture(mockService))
    }
}
