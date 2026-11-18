package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GenericControlTest {
    @Test fun terminalMissingOrUndisclosedNodeIsDistinguishedBeforeAnyNativeEffect() = runBlocking {
        for (node in listOf<Int?>(null, 999)) {
            val port = Port().apply { nativeValidation = true }
            val control = GenericControl(port, { true })
            val id = observe(control)
            val request = buildJsonObject {
                put("observation_id", id); put("action", "terminal_input")
                put("text", "123"); put("terminal_authorized", true)
                if (node != null) put("node_id", node)
            }
            val result = control.action(request)
            assertFalse(result.success)
            assertEquals(if (node == null) "node_id_required" else "target_not_in_observation", result.summary)
            assertFalse(result.data.getValue("action_attempted").jsonPrimitive.boolean)
            assertFalse(result.data.getValue("outcome_unknown").jsonPrimitive.boolean)
            assertFalse(control.action(request).success) // Refusal still consumes the observation.
            assertEquals(0, port.writes)
        }
    }
    @Test fun outputBudgetDoesNotInvalidateCompletedSecurityTraversal() = runBlocking {
        val port = Port()
        port.frame = port.frame.copy(nodes = (1..60).map { ControlNode(it, "Button $it", clickable = true) })
        val control = GenericControl(port, { true })
        val observed = control.observe()
        assertTrue(observed.data.getValue("node_actions_allowed").jsonPrimitive.boolean)
        assertTrue(control.action(input(observed.data.getValue("observation_id").jsonPrimitive.content)).success)
    }
    @Test fun exactKeyboardLookupIsNotCrowdedOutAndCannotExposeForeignOrOtherCaseKeys() = runBlocking {
        val port = Port()
        val ime = ControlKeyboard(4, "ime", "ime/.Keyboard")
        val clutter = (1..60).map { ControlNode(it, "generic app match a", resourceId = "app:id/a$it") }
        val key = ControlNode(61, text = "a", keyboardKey = "a", sourceWindowId = 4, sourcePackage = "ime")
        val rejected = listOf(key.copy(id = 62, text = "A", keyboardKey = "A"),
            key.copy(id = 63, sourcePackage = "foreign"), key.copy(id = 64, sourceWindowId = 8))
        port.frame = port.frame.copy(nodes = clutter + key + rejected, outputLimit = 100, keyboard = ime)
        val control = GenericControl(port, { true })
        val old = control.observe(filter = "a")
        assertTrue(old.data.getValue("output_truncated").jsonPrimitive.boolean)
        assertFalse(old.data.getValue("nodes").jsonArray.any { it.jsonObject["node_id"]?.jsonPrimitive?.intOrNull == 61 })
        val exact = control.observe(keyboardKeyExact = "a")
        assertEquals(listOf(61), exact.data.getValue("nodes").jsonArray.map { it.jsonObject.getValue("node_id").jsonPrimitive.int })
        assertTrue(exact.data.getValue("node_actions_allowed").jsonPrimitive.boolean)
        assertFalse(control.observe(filter = "a", keyboardKeyExact = "a").success)
        port.frame = port.frame.copy(keyboard = ime.copy(readAllowed = false))
        assertTrue(control.observe(keyboardKeyExact = "a").data.getValue("nodes").jsonArray.isEmpty())
        assertEquals(0, port.writes)
    }

    /** #252 E252-1: observed image-only space is not a native text-entry key.
     * A separate fresh visual proof must authorize it; do not guess or dispatch.
     */
    @Test fun imageOnlySpaceIsNotImplicitlyDispatchedByNativeKeyboardSequence() = runBlocking {
        val port = Port()
        val editor = ControlNode(1, text = "", editable = true, focused = true,
            focusable = true, selectionStart = 0, selectionEnd = 0,
            sourceWindowId = 3, sourcePackage = "example.app")
        val imageOnlySpace = ControlNode(2, clickable = true, keyboardImageCandidate = true,
            bounds = "10 10 120 60", sourceWindowId = 4, sourcePackage = "ime")
        port.frame = port.frame.copy(
            nodes = listOf(editor, imageOnlySpace),
            keyboard = ControlKeyboard(4, "ime", "ime/.Keyboard"),
            inputTargetId = editor.id, inputTarget = editor)
        val control = GenericControl(port, { true })
        val result = control.action(input(observe(control), "input",
            mapOf("text" to JsonPrimitive(" "), "method" to JsonPrimitive("keyboard"))))
        assertFalse(result.success)
        assertEquals("keyboard_keys_not_uniquely_observed",
            result.data["reason_code"]?.jsonPrimitive?.content)
        assertFalse(result.data.getValue("action_attempted").jsonPrimitive.boolean)
        assertFalse(result.data.getValue("outcome_unknown").jsonPrimitive.boolean)
        assertEquals(0, port.writes)
    }

    /** A native sequence must not replay or continue when the first attempted key
     * was acknowledged but the exact editor state never changed.
     */
    @Test fun nativeKeyboardSequenceStopsAfterAnUnconfirmedKeyWithoutReplay() = runBlocking {
        val port = Port()
        val editor = ControlNode(1, text = "", editable = true, focused = true,
            focusable = true, selectionStart = 0, selectionEnd = 0,
            sourceWindowId = 3, sourcePackage = "example.app")
        val keyA = ControlNode(2, text = "a", keyboardKey = "a", textEntryKey = true,
            clickable = true, sourceWindowId = 4, sourcePackage = "ime")
        val keyB = ControlNode(3, text = "b", keyboardKey = "b", textEntryKey = true,
            clickable = true, sourceWindowId = 4, sourcePackage = "ime")
        port.frame = port.frame.copy(nodes = listOf(editor, keyA, keyB),
            keyboard = ControlKeyboard(4, "ime", "ime/.Keyboard"),
            inputTargetId = editor.id, inputTarget = editor)
        val control = GenericControl(port, { true })
        val result = control.action(input(observe(control), "input",
            mapOf("text" to JsonPrimitive("ab"), "method" to JsonPrimitive("keyboard"))))
        assertFalse(result.success)
        assertTrue(result.data.getValue("outcome_unknown").jsonPrimitive.boolean)
        assertEquals(0, result.data.getValue("completed_keys").jsonPrimitive.int)
        assertEquals(1, port.writes) // Never send "b" or retry "a".
    }


    /** E252-2: the original image and each of its role/rect pairs must be
     * explicitly approved; all subsequent native effects still need a newly
     * captured image and a confirmed edit result.
     */
    @Test fun boundedVisualKeySequenceRunsTwoIndependentlyConfirmedLiterals() = runBlocking {
        val port = Port()
        val active = ControlSession().apply { start() }
        port.controlSession = active
        val field = ControlNode(1, text = "", editable = true, focused = true,
            focusable = true, selectionStart = 0, selectionEnd = 0,
            sourceWindowId = 3, sourcePackage = "example.app")
        val keyA = ControlNode(2, text = "a", keyboardKey = "a",
            bounds = "10 10 30 30", sourceWindowId = 4, sourcePackage = "ime",
            visualKeyCandidate = true)
        val keyB = keyA.copy(id = 3, text = "b", keyboardKey = "b",
            bounds = "40 10 60 30")
        port.frame = port.frame.copy(nodes = listOf(field, keyA, keyB),
            keyboard = ControlKeyboard(4, "ime", "ime/.Keyboard"),
            inputTargetId = field.id, inputTarget = field)
        port.visualProof = VisualTargetProof(
            ScreenImage(100, 100, "image/jpeg", byteArrayOf(1), ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "test-image", TapBounds(0,0,100,100), "test-window", System.currentTimeMillis(),
            scope = "keyboard", sourceWindowId = 4, sourceImeWindowId = 4,
            sourceDisplayId = 0, rotation = 0, displayGeneration = 1,
            disclosedRegions = listOf(TapBounds(0,0,100,100)))
        port.onWrite = { req ->
            val label = if (req["node_id"]?.jsonPrimitive?.int == keyA.id) "a" else "b"
            val old = checkNotNull(port.frame.inputTarget)
            val updated = old.copy(text = old.text + label, selectionStart = old.text.length + 1,
                selectionEnd = old.text.length + 1, empty = false)
            port.frame = port.frame.copy(inputTarget = updated,
                nodes = port.frame.nodes.map { if (it.id == updated.id) updated else it },
                fingerprint = "after-" + updated.text.length)
        }
        val control = GenericControl(port, { true })
        val observed = control.observe(filter = "keyboard_sequence_candidate",
            includeImage = true, imageScope = "keyboard")
        assertTrue(observed.success)
        val id = observed.data.getValue("observation_id").jsonPrimitive.content
        val proofId = observed.data.getValue("visual").jsonObject.getValue("visual_id").jsonPrimitive.content
        fun step(nodeId: Int, label: String, left: Int, right: Int) = buildJsonObject {
            put("node_id", nodeId); put("label", label)
            put("image_rect", JsonArray(listOf(left,14,right,24).map { JsonPrimitive(it) }))
        }
        val request = buildJsonObject {
            put("session_id", active.id); put("observation_id", id)
            put("visual_id", proofId); put("visual_target_confirmed", true)
            put("text", "ab"); put("steps", JsonArray(listOf(step(2,"a",14,24),step(3,"b",44,54))))
        }
        val result = control.visualKeySequence(request)
        assertTrue(result.summary + ": " + result.data, result.success)
        assertEquals(2, result.data.getValue("completed_keys").jsonPrimitive.int)
        assertEquals("ab", port.frame.inputTarget?.text)
        assertEquals(2, port.writes)
    }

    @Test fun visualSequenceWithoutConfirmedImageOrActiveSessionCannotWrite() = runBlocking {
        val port = Port()
        val control = GenericControl(port, { true })
        val request = buildJsonObject {
            put("observation_id", observe(control))
            put("visual_id", "missing")
            put("visual_target_confirmed", true)
            put("text", "ab")
            put("steps", JsonArray(emptyList()))
        }
        val result = control.visualKeySequence(request)
        assertFalse(result.success)
        assertEquals("explicit_active_session_required", result.data["reason_code"]?.jsonPrimitive?.content)
        assertEquals(0, port.writes)
    }

    @Test fun unverifiedPopupReturnsBoundedDiagnosticsWithoutUnderlyingTargets() = runBlocking {
        val port = Port(); val diagnostics = buildJsonObject { put("window_count", 3); put("selection_reason", "unlinked_application_window") }
        port.frame = port.frame.copy(reason = "unlinked_application_window", windowDiagnostics = diagnostics)
        val control = GenericControl(port, { true }); val result = control.observe()
        assertFalse(result.success); assertEquals(diagnostics, result.data["window_diagnostics"])
        assertFalse(result.data.containsKey("nodes")); assertFalse(result.data.containsKey("observation_id"))
        assertFalse(control.action(input("old-host-observation")).success); assertEquals(0, port.writes)
    }
    @Test fun transientPostActionWindowRaceReobservesWithoutRepeatingEffect() = runBlocking {
        val port = Port().apply { transientPostReads = 1 }
        val control = GenericControl(port, { true }); val result = control.action(input(observe(control)))
        assertTrue(result.success); assertEquals(1, port.writes)
    }
    @Test fun unresolvedPostActionRaceStaysUnknownWithoutReplay() = runBlocking {
        val port = Port().apply { transientPostReads = 10 }
        val control = GenericControl(port, { true }); val result = control.action(input(observe(control)))
        assertFalse(result.success); assertTrue(result.data.getValue("outcome_unknown").jsonPrimitive.boolean)
        assertEquals(1, port.writes)
    }
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
    @Test fun tapCannotUseMissingOrChangedWindowContext() = runBlocking {
        for (context in listOf("", "rotated-or-overlay-added")) {
            val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
            port.frame = port.frame.copy(touchContext = context)
            val result = control.action(input(id, "tap"))
            assertFalse(result.success); assertEquals(0, port.writes)
            assertEquals(if (context.isEmpty()) "touch_window_unavailable" else "touch_window_changed",
                result.data["reason_code"]?.jsonPrimitive?.content)
        }
    }
    @Test fun tapRequiresAnExplicitClickableNodeAndCompleteUnchangedScreen() = runBlocking {
        val cases = listOf<(ControlFrame) -> ControlFrame>(
            { it.copy(nodes = listOf(ControlNode(1, clickable = false))) },
            { it.copy(nodes = listOf(ControlNode(1, clickable = true, enabled = false))) },
            { it.copy(nodes = listOf(ControlNode(1, clickable = true, password = true))) },
            { it.copy(truncated = true) })
        for (change in cases) {
            val port = Port(); port.frame = change(port.frame)
            val control = GenericControl(port, { true }); val id = observe(control)
            assertFalse(control.action(input(id, "tap")).success); assertEquals(0, port.writes)
        }
        val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
        port.frame = port.frame.copy(fingerprint = "changed-under-same-window")
        assertFalse(control.action(input(id, "tap")).success); assertEquals(0, port.writes)
    }
    @Test fun nativeBoundaryValidationAvoidsDuplicatePreActionCapture() = runBlocking {
        val port = Port().apply { nativeValidation = true }
        val control = GenericControl(port, { true })
        val id = observe(control)
        assertTrue(control.action(input(id)).success)
        assertEquals("Observe + one post capture; native perform owns fresh validation", 2, port.captures)
        assertEquals(1, port.writes)
    }
    @Test fun nativeBoundaryRefusalNeverProducesAnotherEffect() = runBlocking {
        val port = Port().apply {
            nativeValidation = true
            effect = ControlEffect(false, false, "screen_changed")
        }
        val control = GenericControl(port, { true })
        val result = control.action(input(observe(control)))
        assertFalse(result.success)
        assertFalse(result.data.getValue("action_attempted").jsonPrimitive.boolean)
        assertEquals(1, port.captures)
    }
    private class Port : ControlPort {
        var frame = ControlFrame("example.app", 3, "screen-a", listOf(ControlNode(1, "Model", clickable = true)), touchContext = "window-a")
        var writes = 0
        var changeOnWrite = true
        var captures = 0
        var controlSession: ControlSession? = null
        override fun session() = controlSession
        var nativeValidation = false
        override val validatesAtNativeBoundary get() = nativeValidation
        var transientPostReads = 0
        var effect = ControlEffect(true, true, "action_acknowledged")
        var visualProof: VisualTargetProof? = null
        var onWrite: ((JsonObject) -> Unit)? = null
        override suspend fun visualKeyboard(frame: ControlFrame): VisualTargetProof? = visualProof?.copy(
            id = java.util.UUID.randomUUID().toString(), at = System.currentTimeMillis())
        override suspend fun capture(): ControlFrame {
            captures++
            if (writes > 0 && transientPostReads > 0) {
                transientPostReads--; return frame.copy(reason = "active_root_missing")
            }
            return frame
        }
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            writes++
            if (onWrite != null) { onWrite?.invoke(input); return effect }
            if (changeOnWrite) this.frame = this.frame.copy(fingerprint = "post-$writes",
                packageName = if (input["action"]?.jsonPrimitive?.content == "open_app") input.getValue("package_name").jsonPrimitive.content else frame.packageName)
            return effect
        }
        override suspend fun apps(filter: String) = JsonArray(emptyList())
    }
    private fun input(id: String, action: String = "click", extra: Map<String, JsonElement> = emptyMap()) =
        JsonObject(mapOf("observation_id" to JsonPrimitive(id), "action" to JsonPrimitive(action), "node_id" to JsonPrimitive(1)) + extra)
    private suspend fun observe(control: GenericControl) = control.observe().data.getValue("observation_id").jsonPrimitive.content
    @Test fun resumedSessionCannotReplayAnOldObservation() = runBlocking {
        val session = ControlSession().apply { activateLegacy() }
        val port = Port().apply { controlSession = session }
        val control = GenericControl(port, { true }); val old = observe(control)
        session.pause(); session.beginResume(); session.finishResume(session.epoch, true)
        val result = control.action(input(old))
        assertFalse(result.success); assertEquals(0, port.writes)
        assertEquals("control_session_changed_observe_again", result.summary)
        assertTrue(control.action(input(observe(control))).success)
        assertEquals(1, port.writes)
    }
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
        val port = Port().apply { changeOnWrite = false }; val control = GenericControl(port, { true }); val id = observe(control)
        val result = control.action(input(id))
        assertFalse(result.success); assertFalse(result.data.getValue("app_result_verified").jsonPrimitive.boolean)
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
        val port = Port().apply { changeOnWrite = false }; val control = GenericControl(port, { true }); var id = observe(control)
        repeat(3) {
            val result = control.action(input(id)); assertFalse(result.success)
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

    @Test fun outputLimitAllowsOnlyExposedIdsAfterCompleteSecurityTraversal() = runBlocking {
        val port = Port(); port.frame = port.frame.copy(nodes = (0..50).map { ControlNode(it, clickable = true) })
        val control = GenericControl(port, { true }); val observed = control.observe()
        assertEquals(JsonPrimitive(false), observed.data["truncated"])
        assertEquals(JsonPrimitive(true), observed.data["output_truncated"])
        assertEquals(JsonPrimitive(true), observed.data["security_scan_complete"])
        assertTrue(control.action(input(observed.data.getValue("observation_id").jsonPrimitive.content)).success)
        val next = observe(control)
        assertFalse(control.action(input(next, extra = mapOf("node_id" to JsonPrimitive(50)))).success)
        assertEquals(1, port.writes)
    }
    @Test fun filterCanFindLaterVisibleTargetsButCannotSkipSecurityFailure() = runBlocking {
        val port = Port(); port.frame = port.frame.copy(nodes = (0..100).map { ControlNode(it, "Item $it", clickable = true) })
        val control = GenericControl(port, { true }); val observed = control.observe("Item 100")
        assertEquals(1, observed.data.getValue("nodes").jsonArray.size)
        assertTrue(control.action(input(observed.data.getValue("observation_id").jsonPrimitive.content,
            extra = mapOf("node_id" to JsonPrimitive(100)))).success)
        port.frame = port.frame.copy(truncated = true)
        val partial = control.observe("Item 100")
        assertFalse(partial.data.getValue("node_actions_allowed").jsonPrimitive.boolean)
    }
    @Test fun configurationChangeInvalidatesEvenGlobalNavigation() = runBlocking {
        for (action in listOf("home", "open_app", "click")) {
            val port = Port(); val control = GenericControl(port, { true }); val id = observe(control)
            port.frame = port.frame.copy(configurationRevision = 1)
            val result = control.action(input(id, action, mapOf("package_name" to JsonPrimitive("example.target"))))
            assertEquals("configuration_revision_changed", result.data["reason_code"]?.jsonPrimitive?.content)
            assertEquals(0, port.writes)
        }
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
        val port = Port().apply { changeOnWrite = false }; val control = GenericControl(port, { true }); val id = observe(control)
        val result = control.action(input(id))
        assertEquals("outcome_unconfirmed", result.data["feedback"]?.jsonPrimitive?.content)
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

    @Test fun guardedActionReportsPrivacySafeMonotonicSegments() = runBlocking {
        val port = Port()
        val control = GenericControl(port, { true })
        val result = control.action(input(observe(control)))
        assertTrue(result.success)
        assertEquals("monotonic_process", result.data.getValue("timing_clock_domain").jsonPrimitive.content)
        val timing = result.data.getValue("timing_ms").jsonObject
        for (segment in listOf("validation", "native_action", "post_observation", "total")) {
            assertTrue("$segment must not be negative", timing.getValue(segment).jsonPrimitive.long >= 0L)
        }
        assertFalse(result.data.toString().contains("screen-a"))
        assertEquals(1, port.writes)
    }
}
