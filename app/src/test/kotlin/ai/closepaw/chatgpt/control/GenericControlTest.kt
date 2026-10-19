package ai.closepaw.chatgpt.control

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GenericControlTest {
    @Test fun explicitNodeTapIsSingleUseAndDoesNotClaimModelSelection() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
        val result = control.action(input(id, "tap"))
        assertTrue("An explicit observed-node tap must reach the guarded native adapter", result.success)
        assertFalse(result.data.getValue("app_result_verified").jsonPrimitive.boolean)
        assertFalse(control.action(input(id, "tap")).success)
        assertEquals(1, port.writes)
    }
    @Test fun cancelledTapIsUnknownAndMustNeverReplay() = runBlocking {
        val port = Port(); port.effect = ControlEffect(true, false, "gesture_cancelled")
        val control = GenericControl(port, { true }); val id = observe(control)
        val result = control.action(input(id, "tap"))
        assertFalse(result.success)
        assertTrue(result.data.getValue("outcome_unknown").jsonPrimitive.boolean)
        assertFalse(control.action(input(id, "tap")).success)
        assertEquals(1, port.writes)
    }
    private class Port : ControlPort {
        var frame = ControlFrame("example.app", 3, "screen-a", listOf(ControlNode(1, "Model", clickable = true)))
        var writes = 0
        var effect = ControlEffect(true, true, "action_acknowledged")
        override suspend fun capture() = frame
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect { writes++; return effect }
        override suspend fun apps(filter: String) = JsonArray(emptyList())
    }
    private fun input(id: String, action: String = "click", extra: Map<String, JsonElement> = emptyMap()) =
        JsonObject(mapOf("observation_id" to JsonPrimitive(id), "action" to JsonPrimitive(action), "node_id" to JsonPrimitive(1)) + extra)
    private suspend fun observe(control: GenericControl) = control.observe().data.getValue("observation_id").jsonPrimitive.content
    @Test fun changedScreenDoesNotClick() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
        port.frame = port.frame.copy(fingerprint = "screen-b")
        assertFalse(control.action(input(id)).success); assertEquals(0, port.writes)
    }
    @Test fun observationCanOnlyBeConsumedOnce() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
        assertTrue(control.action(input(id)).success)
        assertFalse(control.action(input(id)).success); assertEquals(1, port.writes)
    }
    @Test fun wrongObservationIsRejected() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); observe(control)
        assertFalse(control.action(input("other")).success); assertEquals(0, port.writes)
    }
    @Test fun expirationPreventsWrite() = runBlocking {
        var now = 0L; val port = Port(); val control = GenericControl(port, { true }, { now }); val id = observe(control)
        now = 60_001; assertFalse(control.action(input(id)).success); assertEquals(0, port.writes)
    }
    @Test fun revokedConsentPreventsWrite() = runBlocking {
        var allowed = true; val port = Port(); val control = GenericControl(port, { allowed }); val id = observe(control)
        allowed = false; assertFalse(control.action(input(id)).success); assertEquals(0, port.writes)
    }
    @Test fun blockedOrLockedScreenCannotLeakNodes() = runBlocking {
        for (reason in listOf("device_locked", "blocked_app", "authentication_boundary", "accessibility_permission_missing")) {
            val port = Port(); port.frame = port.frame.copy(reason = reason)
            val result = GenericControl(port, { true }).observe()
            assertFalse(result.success); assertFalse(result.data.containsKey("nodes"))
        }
    }
    @Test fun truncatedScreenCannotWrite() = runBlocking {
        val port = Port(); port.frame = port.frame.copy(truncated = true)
        val control = GenericControl(port, { true }); val id = observe(control)
        assertFalse(control.action(input(id)).success); assertEquals(0, port.writes)
    }
    @Test fun missingTargetDoesNotFallBackToCoordinates() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
        assertFalse(control.action(input(id, extra = mapOf("node_id" to JsonPrimitive(99)))).success); assertEquals(0, port.writes)
    }
    @Test fun nonemptyDraftIsPreserved() = runBlocking {
        val port = Port(); port.frame = port.frame.copy(nodes = listOf(ControlNode(1, editable = true, empty = false)))
        val control = GenericControl(port, { true }); val id = observe(control)
        assertFalse(control.action(input(id, "type", mapOf("text" to JsonPrimitive("new")))).success); assertEquals(0, port.writes)
    }
    @Test fun unknownEffectIsNeverRetriedAndIsExplicit() = runBlocking {
        val port = Port(); port.effect = ControlEffect(true, false, "action_unconfirmed")
        val control = GenericControl(port, { true }); val id = observe(control)
        val result = control.action(input(id))
        assertFalse(result.success); assertTrue(result.data.getValue("outcome_unknown").jsonPrimitive.boolean)
        control.action(input(id)); assertEquals(1, port.writes)
    }
    @Test fun windowAndPackageChangesAreRejected() = runBlocking {
        for (changed in listOf(ControlFrame("other.app", 3, "screen-a", emptyList()), ControlFrame("example.app", 4, "screen-a", emptyList()))) {
            val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
            port.frame = changed
            assertFalse(control.action(input(id)).success); assertEquals(0, port.writes)
        }
    }
    @Test fun refreshedObservationInvalidatesOldIds() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val old = observe(control); observe(control)
        assertFalse(control.action(input(old)).success); assertEquals(0, port.writes)
    }
    @Test fun acknowledgementDoesNotClaimRequestedModelWasSelected() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
        val result = control.action(input(id))
        assertTrue(result.success); assertFalse(result.data.getValue("app_result_verified").jsonPrimitive.boolean)
        assertFalse(result.data.getValue("screen_changed").jsonPrimitive.boolean)
        assertTrue(result.data.getValue("observation").jsonObject.containsKey("observation_id"))
    }
    @Test fun unsupportedActionCannotInvokePort() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
        assertFalse(control.action(input(id, "swipe")).success); assertEquals(0, port.writes)
    }
    @Test fun duplicateLabelsRequireAnExplicitNodeId() = runBlocking {
        val port = Port(); port.frame = port.frame.copy(nodes = listOf(ControlNode(1, "Model", clickable = true), ControlNode(2, "Model", clickable = true)))
        val control = GenericControl(port, { true }); val id = observe(control)
        val ambiguous = JsonObject(input(id).filterKeys { it != "node_id" })
        assertFalse(control.action(ambiguous).success); assertEquals(0, port.writes)
    }

    @Test fun longPressOnlyUsesAnExplicitLongClickTarget() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
        assertFalse(control.action(input(id, "long_press")).success); assertEquals(0, port.writes)
        port.frame = port.frame.copy(nodes = listOf(ControlNode(1, longClickable = true)))
        val fresh = observe(control)
        assertTrue(control.action(input(fresh, "long_press")).success); assertEquals(1, port.writes)
    }

    @Test fun dedicatedHandoffInvalidatesGenericTargets() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
        control.invalidate()
        assertFalse(control.action(input(id)).success); assertEquals(0, port.writes)
    }
    @Test fun noProgressLimitStopsRepeatedActions() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); var id = observe(control)
        repeat(3) {
            val result = control.action(input(id)); assertTrue(result.success)
            id = result.data.getValue("observation").jsonObject.getValue("observation_id").jsonPrimitive.content
        }
        assertFalse(control.action(input(id)).success); assertEquals(3, port.writes)
    }

    @Test fun truncatedObservationAllowsHomeAndAppLaunchWithoutNodeTargeting() = runBlocking {
        for (action in listOf("home", "open_app")) {
            val port = Port(); port.frame = port.frame.copy(truncated = true)
            val control = GenericControl(port, { true }); val id = observe(control)
            val result = control.action(input(id, action, mapOf("package_name" to JsonPrimitive("example.target"))))
            assertTrue("Bounded $action must not depend on node completeness", result.success)
            assertEquals(1, port.writes)
        }
    }
    @Test fun truncatedObservationStillRejectsBackAndAllNodeWrites() = runBlocking {
        for (action in listOf("back", "click", "long_press", "type", "scroll")) {
            val port = Port(); port.frame = port.frame.copy(truncated = true, nodes = listOf(ControlNode(1, clickable = true,
                editable = true, scrollable = true, longClickable = true)))
            val control = GenericControl(port, { true }); val id = observe(control)
            val result = control.action(input(id, action, mapOf("text" to JsonPrimitive("hello"), "direction" to JsonPrimitive("down"))))
            assertFalse(result.success); assertEquals(0, port.writes)
        }
    }
    @Test fun truncatedNavigationStillChecksTheForegroundIdentity() = runBlocking {
        val port = Port(); port.frame = port.frame.copy(truncated = true)
        val control = GenericControl(port, { true }); val id = observe(control)
        port.frame = port.frame.copy(packageName = "other.app")
        val result = control.action(input(id, "home"))
        assertEquals("screen_changed", result.data["reason_code"]?.jsonPrimitive?.content); assertEquals(0, port.writes)
    }
    @Test fun truncatedObservationAdvertisesOnlyNonNodeNavigation() = runBlocking {
        val port = Port(); port.frame = port.frame.copy(truncated = true)
        val result = GenericControl(port, { true }).observe()
        assertEquals(JsonArray(listOf(JsonPrimitive("home"), JsonPrimitive("open_app"))), result.data["navigation_actions"])
        assertEquals(JsonPrimitive(false), result.data["node_actions_allowed"])
    }

    @Test fun outputNodeLimitKeepsNodeWritesBlockedButAllowsHome() = runBlocking {
        val port = Port(); port.frame = port.frame.copy(nodes = (0..50).map { ControlNode(it, clickable = true) })
        val control = GenericControl(port, { true }); val observed = control.observe()
        assertEquals(JsonPrimitive(true), observed.data["truncated"])
        assertEquals(JsonPrimitive(false), observed.data["node_actions_allowed"])
        assertTrue(control.action(input(observed.data.getValue("observation_id").jsonPrimitive.content, "home")).success)
        val next = observe(control)
        assertFalse(control.action(input(next)).success); assertEquals(1, port.writes)
    }

    @Test fun globalNavigationCanStartFromAnUpdatingClient() = runBlocking {
        for (action in listOf("home", "open_app")) {
            val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
            port.frame = port.frame.copy(fingerprint = "ordinary-content-refreshed")
            val result = control.action(input(id, action, mapOf("package_name" to JsonPrimitive("example.target"))))
            assertTrue("Global navigation must not target changing message content", result.success)
            assertEquals(1, port.writes)
            assertEquals(JsonPrimitive("foreground_app_window"), result.data["validation_scope"])
        }
    }

    @Test fun unchangedAcknowledgementHasExplicitFeedbackAndTiming() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
        val result = control.action(input(id))
        assertEquals("acknowledged_no_visible_change", result.data["feedback"]?.jsonPrimitive?.content)
        assertNotNull(result.data["timing_ms"])
        assertFalse(result.data.getValue("app_result_verified").jsonPrimitive.boolean)
    }
    @Test fun expandRequiresExplicitNativeSupportAndCannotBypassPartialFrame() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true })
        val unsupported = control.action(input(observe(control), "expand"))
        assertEquals("native_action_unavailable", unsupported.data["reason_code"]?.jsonPrimitive?.content)
        assertEquals(0, port.writes)
        port.frame = port.frame.copy(nodes = listOf(ControlNode(1, expandable = true)))
        assertTrue(control.action(input(observe(control), "expand")).success)
        assertEquals(1, port.writes)
        port.frame = port.frame.copy(truncated = true)
        assertFalse(control.action(input(observe(control), "expand")).success)
        assertEquals(1, port.writes)
    }
}
