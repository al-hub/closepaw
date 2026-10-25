package ai.closepaw.chatgpt.control

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UnifiedInputEngineTest {
    private class Port : ControlPort {
        var node = ControlNode(1, resourceId = "example:id/input", bounds = "0 0 100 100", className = "EditText",
            editable = true, focused = true, selectionStart = 0, selectionEnd = 0, setText = true, paste = true, focusable = true)
        var frame = ControlFrame("example.app", 1, "initial", listOf(node))
        var writes = 0
        var confirmed = true
        var effect = ControlEffect(true, true, "action_acknowledged")
        override suspend fun capture() = frame
        override suspend fun apps(filter: String) = JsonArray(emptyList())
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            writes++
            if (confirmed && effect.attempted) {
                val plan = UnifiedInputPolicy.plan(node!!, input.getValue("text").jsonPrimitive.content,
                    input["method"]?.jsonPrimitive?.content ?: "auto", input["mode"]?.jsonPrimitive?.content ?: "empty", true)
                this.frame = frame.copy(fingerprint = "changed", nodes = listOf(node.copy(text = plan.resultText)))
            }
            return effect
        }
    }
    private fun input(id: String, text: String = "한글 A1!", extra: Map<String, JsonElement> = emptyMap()) = JsonObject(mapOf(
        "action" to JsonPrimitive("input"), "node_id" to JsonPrimitive(1), "observation_id" to JsonPrimitive(id), "text" to JsonPrimitive(text)) + extra)
    private suspend fun observe(control: GenericControl) = control.observe().data.getValue("observation_id").jsonPrimitive.content
    @Test fun inputVerifiesExactResultAndCannotReuseObservation() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
        val result = control.action(input(id))
        assertTrue(result.success); assertEquals(JsonPrimitive(true), result.data["app_result_verified"])
        assertEquals(JsonPrimitive("direct"), result.data["input_method"])
        assertFalse(control.action(input(id)).success); assertEquals(1, port.writes)
    }
    @Test fun partialInputAndNegativeAcknowledgementNeverTriggerFallback() = runBlocking {
        for (effect in listOf(ControlEffect(true, true, "ack"), ControlEffect(true, false, "unconfirmed"))) {
            val port = Port().apply { confirmed = false; this.effect = effect }
            val control = GenericControl(port, { true }); val id = observe(control)
            val result = control.action(input(id, extra = mapOf("clipboard_consent" to JsonPrimitive(true))))
            assertFalse(result.success); assertEquals(JsonPrimitive(true), result.data["outcome_unknown"])
            assertFalse(control.action(input(id)).success); assertEquals(1, port.writes)
        }
    }
    @Test fun unsupportedDirectReturnsWithoutTryingPaste() = runBlocking {
        val port = Port().apply { frame = frame.copy(nodes = listOf(node.copy(setText = false))) }
        val control = GenericControl(port, { true })
        val result = control.action(input(observe(control), extra = mapOf("method" to JsonPrimitive("direct"))))
        assertFalse(result.success); assertEquals(0, port.writes)
    }
    @Test fun protectedClipboardNativeRefusalCannotTryKeyboard() = runBlocking {
        val port = Port().apply { confirmed = false; frame = frame.copy(nodes = listOf(node.copy(setText = false)))
            effect = ControlEffect(false, false, "clipboard_read_access_unproven") }
        val control = GenericControl(port, { true })
        val result = control.action(input(observe(control), extra = mapOf("clipboard_consent" to JsonPrimitive(true))))
        assertFalse(result.success); assertEquals(JsonPrimitive(false), result.data["action_attempted"])
        assertEquals(JsonPrimitive(false), result.data["outcome_unknown"]); assertEquals(1, port.writes)
    }
    @Test fun changedCaretOrFrameRefusesInputBeforeNativeCall() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
        port.frame = port.frame.copy(fingerprint = "caret-moved")
        assertFalse(control.action(input(id)).success); assertEquals(0, port.writes)
    }
    @Test fun privacyAndLineSeparatorsAreRejectedBeforeNativeCall() = runBlocking {
        for (text in listOf("a\u2028b", "a\u2029b", "token=abc", "password=123", "x@example.org", "010-1234-5678", "sk-12345678901234567890")) {
            val port = Port(); val control = GenericControl(port, { true })
            assertFalse(control.action(input(observe(control), text)).success); assertEquals(0, port.writes)
        }
    }
    @Test fun focusCanFollowUniqueSemanticFieldAcrossImeResizeButNotDuplicates() {
        val node = Port().node.copy(focused = false, sourceWindowId = 1, sourcePackage = "example.app")
        val before = ControlFrame("example.app", 1, "before", listOf(node))
        val moved = node.copy(bounds = "0 0 100 50", focused = true)
        val after = before.copy(fingerprint = "ime", nodes = listOf(moved))
        assertTrue(ControlResultPolicy.verify("focus", before, after, node, buildJsonObject {}).confirmed)
        assertFalse(ControlResultPolicy.verify("focus", before.copy(nodes = listOf(node, node.copy(id = 2))), after, node, buildJsonObject {}).confirmed)
        assertFalse(ControlResultPolicy.verify("focus", before, after.copy(nodes = listOf(moved, moved.copy(id = 2))), node, buildJsonObject {}).confirmed)
    }

    @Test fun imeNodeCannotMasqueradeAsAnApplicationInputPostcondition() {
        val field = Port().node.copy(sourceWindowId = 1, sourcePackage = "example.app")
        val before = ControlFrame("example.app", 1, "before", listOf(field))
        val impostor = field.copy(text = "abc", focused = true, sourceWindowId = 9, sourcePackage = "example.ime", keyboardKey = "abc")
        val after = before.copy(fingerprint = "ime-changed", nodes = listOf(impostor))
        val input = buildJsonObject { put("text", "abc") }
        for (action in listOf("type", "input", "focus"))
            assertFalse(ControlResultPolicy.verify(action, before, after, field, input).confirmed)
    }
}
