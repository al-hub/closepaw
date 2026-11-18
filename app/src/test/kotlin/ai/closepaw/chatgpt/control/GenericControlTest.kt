package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.OperationDispatcher
import ai.closepaw.chatgpt.operations.OperationTaskStore
import ai.closepaw.chatgpt.operations.OperationInvocation
import ai.closepaw.chatgpt.operations.OperationReadiness
import ai.closepaw.chatgpt.operations.OperationRegistry
import ai.closepaw.chatgpt.operations.OperationSchemaValidator
import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
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
    @Test fun boundedVisualKeySequenceConfirmsLiteralAndImageOnlySpace() = runTest {
        val port = Port()
        val active = ControlSession().apply { start() }
        port.controlSession = active
        val ime = ControlKeyboard(4, "com.samsung.android.honeyboard",
            "com.samsung.android.honeyboard/.service.HoneyBoardService")
        val field = ControlNode(1, text = "", editable = true, focused = true,
            focusable = true, selectionStart = 0, selectionEnd = 0,
            sourceWindowId = 3, sourcePackage = "example.app")
        fun key(id: Int, label: String, rect: String) = ControlNode(
            id, text = label, keyboardKey = label, bounds = rect,
            sourceWindowId = ime.windowId, sourcePackage = ime.packageName,
            className = "android.view.ViewGroup", parent = 20, visualKeyChildCount = 1,
            visualKeyCandidate = true)
        val keyA = key(2, "a", "54 118 77 158")
        val space = ControlNode(3, description = "스페이스 키", bounds = "50 165 140 205",
            sourceWindowId = ime.windowId, sourcePackage = ime.packageName,
            className = "android.view.ViewGroup", parent = 20, visualKeyChildCount = 1,
            keyboardImageCandidate = true)
        // A Samsung frame can report 83 candidates, including wrapper/label
        // pairs, with Space after the presentation limit. Not scan truncation.
        val earlierKeys = (0 until 38).flatMap { index ->
            val id = 100 + index * 2
            val x = 8 + index % 10 * 18
            val y = 10 + index / 10 * 22
            val label = (index % 10).toString()
            listOf(key(id, label, "$x $y ${x + 15} ${y + 20}"),
                key(id + 1, label, "${x + 2} ${y + 2} ${x + 13} ${y + 18}")
                    .copy(parent = id, className = "android.widget.TextView", visualKeyChildCount = 0))
        }
        val keyALabel = key(8, "a", "56 121 75 154")
            .copy(parent = keyA.id, className = "android.widget.TextView", visualKeyChildCount = 0)
        val nodes = listOf(field) + earlierKeys + listOf(keyA, keyALabel,
            key(4, ",", "26 165 49 205"), key(5, ".", "141 165 165 205"),
            key(6, "ㄴ", "79 118 102 158"), key(7, "ㅇ", "104 118 127 158"), space)
        port.frame = port.frame.copy(nodes = nodes, keyboard = ime,
            inputTargetId = field.id, inputTarget = field, outputLimit = 100)
        assertTrue(KeyboardImageFallbackPolicy.canPropose(port.frame, space))
        port.visualProof = VisualTargetProof(
            ScreenImage(200, 250, "image/jpeg", byteArrayOf(1), ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "test-image", TapBounds(0,0,200,250), "test-window", System.currentTimeMillis(),
            scope = "keyboard", sourceWindowId = 4, sourceImeWindowId = 4,
            sourceDisplayId = 0, rotation = 0, displayGeneration = 1,
            imeBounds = "0 0 200 250", disclosedRegions = listOf(TapBounds(0,0,200,250)))
        port.onWrite = { req ->
            val label = when (req["node_id"]?.jsonPrimitive?.int) {
                keyA.id -> "a"
                space.id -> " "
                else -> error("Unexpected native key")
            }
            val old = checkNotNull(port.frame.inputTarget)
            val updated = old.copy(text = old.text + label, selectionStart = old.text.length + 1,
                selectionEnd = old.text.length + 1, empty = false)
            port.frame = port.frame.copy(inputTarget = updated,
                nodes = port.frame.nodes.map { if (it.id == updated.id) updated else it },
                fingerprint = "after-" + updated.text.length)
        }
        val control = GenericControl(port, { true })
        fun ids(data: JsonObject) = data.getValue("nodes").jsonArray.map { it.jsonObject.getValue("node_id").jsonPrimitive.int }
        val unfiltered = control.observe(filter = "keyboard_sequence_candidate",
            includeImage = true, imageScope = "keyboard")
        assertTrue(unfiltered.success)
        assertEquals(83, unfiltered.data.getValue("matched_node_count").jsonPrimitive.int)
        assertTrue(unfiltered.data.getValue("security_scan_complete").jsonPrimitive.boolean)
        assertTrue(unfiltered.data.getValue("output_truncated").jsonPrimitive.boolean)
        assertFalse(space.id in ids(unfiltered.data))
        val registry = OperationRegistry()
        GenericControlOperations(control, port, Mutex(), readiness = { OperationReadiness() }).register(registry)
        val observeHandler = checkNotNull(registry.get(GenericControlOperations.OBSERVE))
        val requestedInput = buildJsonObject {
            put("session_id", active.id); put("include_image", true); put("image_scope", "keyboard")
            put("keyboard_sequence_labels", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("Space"))))
        }
        assertNull(OperationSchemaValidator.validate(observeHandler.descriptor.inputSchema, requestedInput))
        val wrongRole = JsonObject(requestedInput + ("keyboard_sequence_labels" to JsonArray(listOf(JsonPrimitive("Search")))))
        assertEquals("keyboard_sequence_labels_invalid",
            observeHandler.handler.invoke(OperationInvocation("invalid-role", wrongRole)).summary)
        suspend fun observeRequested() = observeHandler.handler.invoke(OperationInvocation("targeted-observation", requestedInput))
        fun step(nodeId: Int, label: String, rect: List<Int>) = buildJsonObject {
            put("node_id", nodeId); put("label", label)
            put("image_rect", JsonArray(rect.map { JsonPrimitive(it) }))
        }
        fun request(observed: JsonObject) = buildJsonObject {
            put("session_id", active.id); put("observation_id", observed.getValue("observation_id"))
            put("visual_id", observed.getValue("visual").jsonObject.getValue("visual_id"))
            put("visual_target_confirmed", true)
            put("text", "a "); put("steps", JsonArray(listOf(
                step(keyA.id, "a", listOf(57,125,74,150)),
                step(space.id, "Space", listOf(70,172,120,193)))))
        }
        val originalFrame = port.frame
        for (ambiguous in listOf(space.copy(id = 9), keyA.copy(id = 9, bounds = "30 118 53 158"))) {
            port.frame = originalFrame.copy(nodes = nodes + ambiguous)
            val refused = observeRequested()
            assertTrue(refused.success)
            assertTrue(ids(refused.data).isEmpty())
            val blocked = control.visualKeySequence(request(refused.data))
            assertFalse(blocked.success)
            assertFalse(blocked.data.getValue("action_attempted").jsonPrimitive.boolean)
            assertEquals(0, port.writes)
        }
        port.frame = originalFrame
        val observed = observeRequested()
        assertTrue(observed.success)
        assertFalse(observed.data.getValue("output_truncated").jsonPrimitive.boolean)
        assertEquals(listOf(keyA.id, space.id), ids(observed.data))
        val ledger = Files.createTempDirectory("visual-sequence-dispatch").toFile()
        try {
            val scope = this
            val operation = checkNotNull(registry.get(GenericControlOperations.VISUAL_KEY_SEQUENCE)).descriptor
            var consent = true
            val dispatcher = OperationDispatcher(registry, OperationTaskStore(ledger.resolve("tasks.json")), scope,
                operationAllowed = { consent && GenericControlOperations.allowsExecutionWithGenericControl(it) },
                operationEpoch = { if (GenericControlOperations.requiresExecutionEpoch(it))
                    port.session()?.epoch ?: -1L else 0L })
            fun executeArgs(id: String, input: JsonObject) = buildJsonObject {
                put("request_id", id); put("operation_id", operation.id)
                put("operation_version", operation.version); put("schema_hash", operation.schemaHash)
                put("input", input)
            }
            fun accepted(id: String, input: JsonObject): JsonObject =
                dispatcher.execute(executeArgs(id, input)).also {
                    assertEquals("accepted", it.getValue("status").jsonPrimitive.content)
                }
            fun task(accepted: JsonObject): JsonObject {
                testScheduler.runCurrent()
                return dispatcher.getTask(buildJsonObject { put("task_id", accepted.getValue("task_id")) })
            }
            val valid = request(observed.data)
            consent = false
            val denied = dispatcher.execute(executeArgs("disabled", valid))
            assertEquals("permission_denied", denied.getValue("error_code").jsonPrimitive.content)
            consent = true
            port.controlSession = null
            val missing = task(accepted("missing", valid))
            assertEquals("explicit_active_session_required", missing.getValue("summary").jsonPrimitive.content)
            assertEquals(0, port.writes)
            port.controlSession = active
            // A replacement session can have the same numeric epoch, but not the old session ID.
            val queuedWrongOwner = accepted("replacement", valid)
            port.controlSession = ControlSession().apply { start() }
            assertEquals("control_session_id_mismatch", task(queuedWrongOwner).getValue("summary").jsonPrimitive.content)
            assertEquals(0, port.writes)
            port.controlSession = active
            val queuedOldEpoch = accepted("old-epoch", valid)
            active.pause()
            assertEquals("control_session_changed_before_start",
                task(queuedOldEpoch).getValue("summary").jsonPrimitive.content)
            assertEquals(0, port.writes)
            check(active.beginResume()); active.finishResume(active.epoch, true)
            val confirmed = task(accepted("active", request(observeRequested().data)))
            assertEquals("succeeded", confirmed.getValue("state").jsonPrimitive.content)
            val data = confirmed.getValue("result").jsonObject.getValue("data").jsonObject
            assertEquals(2, data.getValue("completed_keys").jsonPrimitive.int)
            assertEquals(1, data.getValue("confirmed_image_fallback_spaces").jsonPrimitive.int)
        } finally { ledger.deleteRecursively() }
        assertEquals("a ", port.frame.inputTarget?.text)
        assertEquals(2, port.frame.inputTarget?.selectionStart)
        assertEquals(2, port.frame.inputTarget?.selectionEnd)
        assertEquals(2, port.writes)

        // Changing any physical key after the verified first effect prevents the next touch.
        port.frame = originalFrame
        val updateEditor = checkNotNull(port.onWrite)
        port.onWrite = { input ->
            updateEditor(input)
            port.frame = port.frame.copy(nodes = port.frame.nodes.map {
                if (it.id == 7) it.copy(bounds = "105 118 127 158") else it
            })
        }
        val changed = request(observeRequested().data)
        val stopped = control.visualKeySequence(changed)
        assertFalse(stopped.success)
        assertEquals("keyboard_sequence_layout_or_target_changed", stopped.summary)
        assertEquals(1, stopped.data.getValue("completed_keys").jsonPrimitive.int)
        assertFalse(control.visualKeySequence(changed).success)
        assertEquals(3, port.writes)
        val suggestion = key(99, "s", "8 0 25 9")
        port.frame = originalFrame.copy(nodes = nodes + suggestion)
        port.onWrite = { input ->
            updateEditor(input)
            port.frame = port.frame.copy(nodes = port.frame.nodes.filterNot { it.id == suggestion.id })
        }
        val samePhysicalKeys = control.visualKeySequence(request(observeRequested().data))
        assertTrue(samePhysicalKeys.summary, samePhysicalKeys.success)
        assertEquals(5, port.writes)

        port.frame = originalFrame
        port.onWrite = updateEditor
        val preceding = port.writes
        var afterFirst = 0
        port.onCapture = {
            // First postcondition initially matches. The next fresh read has an
            // unproven suffix despite the unchanged caret.
            if (port.writes == preceding + 1 && ++afterFirst == 2 && port.frame.inputTarget?.text == "a") {
                val edited = port.frame.inputTarget!!.copy(text = "abcdefghi")
                port.frame = port.frame.copy(inputTarget = edited,
                    nodes = port.frame.nodes.map { if (it.id == edited.id) edited else it })
            }
        }
        val unexpectedValue = control.visualKeySequence(request(observeRequested().data))
        assertFalse(unexpectedValue.success)
        assertTrue(unexpectedValue.data.getValue("outcome_unknown").jsonPrimitive.boolean)
        assertEquals(1, unexpectedValue.data.getValue("completed_keys").jsonPrimitive.int)
        assertEquals(preceding + 1, port.writes)
        port.onCapture = null
        port.frame = originalFrame
        val beforeRetry = port.writes
        var remaining = 1
        port.beforeWrite = {
            if (remaining-- > 0) ControlEffect(false, false, "screen_changed") else null
        }
        val retry = control.visualKeySequence(request(observeRequested().data))
        assertTrue(retry.summary, retry.success)
        assertEquals(beforeRetry + 2, port.writes)
        var refusals = 0
        port.frame = originalFrame
        port.beforeWrite = { refusals++; ControlEffect(false, false, "screen_changed") }
        val blockedRetry = control.visualKeySequence(request(observeRequested().data))
        assertFalse(blockedRetry.success)
        assertFalse(blockedRetry.data.getValue("action_attempted").jsonPrimitive.boolean)
        assertEquals(2, refusals)
        port.beforeWrite = null

        port.frame = originalFrame
        val initialProof = checkNotNull(port.visualProof)
        val beforeMask = port.writes
        port.onWrite = { input ->
            updateEditor(input)
            // Crop/center are still visible, but part of the actual Space key is masked.
            port.visualProof = initialProof.copy(disclosedRegions = listOf(TapBounds(64, 170, 124, 200)))
        }
        val masked = control.visualKeySequence(request(observeRequested().data))
        assertEquals("keyboard_sequence_pixels_unverified", masked.summary)
        assertEquals(beforeMask + 1, port.writes)
        port.visualProof = initialProof
        port.frame = originalFrame
        val beforePause = port.writes
        port.onWrite = { input -> updateEditor(input); active.pause() }
        assertFalse(control.visualKeySequence(request(observeRequested().data)).success)
        assertEquals(beforePause + 1, port.writes)
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
        var onCapture: (() -> Unit)? = null
        var beforeWrite: (() -> ControlEffect?)? = null
        override suspend fun capture(): ControlFrame {
            captures++
            onCapture?.invoke()
            if (writes > 0 && transientPostReads > 0) {
                transientPostReads--; return frame.copy(reason = "active_root_missing")
            }
            return frame
        }
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            beforeWrite?.invoke()?.let { return it }
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
