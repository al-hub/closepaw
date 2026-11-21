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


    private class JamoCandidateFixture(suggestionStrip: Boolean = false, secondPhysicalKey: Boolean = false,
        samsung: Boolean = false) {
        private val ime = if (samsung) ControlKeyboard(4, "com.samsung.android.honeyboard",
            "com.samsung.android.honeyboard/.service.HoneyBoardService") else ControlKeyboard(4, "ime", "ime/.Keyboard")
        private val session = ControlSession().apply { start() }
        private val editor = ControlNode(1, sourceWindowId = 3, sourcePackage = "example.app",
            editable = true, focusable = true, focused = true, selectionStart = 0, selectionEnd = 0)
        private fun node(id: Int, bounds: String, parent: Int = -1) = ControlNode(id, bounds = bounds,
            parent = parent, sourceWindowId = ime.windowId, sourcePackage = ime.packageName)
        private fun key(id: Int, label: String, bounds: String, parent: Int) =
            node(id, bounds, parent).copy(text = label, keyboardKey = label, visualKeyCandidate = true,
                className = "android.view.ViewGroup", visualKeyChildCount = 1)
        private fun caption(id: Int, label: String, bounds: String) =
            key(id, label, bounds, 116).copy(className = "android.widget.TextView",
                clickable = true, visualKeyChildCount = 0)

        private val consonant = key(163, "ㄱ", "451 2154 569 2309", 134)
        private val vowel = key(192, "ㅏ", "1081 2354 1199 2509", 176)
        private val space = node(210, "800 2550 1250 2705", 200).copy(
            description = "스페이스 키", className = "android.view.ViewGroup",
            visualKeyChildCount = 1, keyboardImageCandidate = true)
        val expectedIds = listOf(consonant.id, vowel.id)
        val port = Port().apply {
            controlSession = session
            val nodes = buildList {
                add(editor)
                if (suggestionStrip) {
                    add(node(116, "128 1798 1300 1975"))
                    add(caption(115, "ㅇ", "142 1823 488 1928"))
                    add(caption(118, "ㅈ", "498 1823 847 1928"))
                    // The observed clickable suggestion is outside the key rows, but
                    // is surfaced with the same candidate metadata as the physical key.
                    add(caption(117, "ㄱ", "858 1823 1252 1928"))
                }
                add(node(134, "160 2135 1280 2325"))
                add(key(160, "ㅈ", "171 2154 289 2309", 134))
                add(key(161, "ㄷ", "311 2154 429 2309", 134))
                add(consonant)
                add(consonant.copy(id = 164, bounds = "463 2178 557 2285", parent = consonant.id,
                    className = "android.widget.TextView", visualKeyChildCount = 0))
                add(key(165, if (secondPhysicalKey) "ㄱ" else "ㅅ", "591 2154 709 2309", 134))
                add(key(166, "ㅛ", "731 2154 849 2309", 134))
                add(node(176, "160 2335 1280 2525"))
                add(key(189, "ㅗ", "801 2354 919 2509", 176))
                add(key(190, "ㅓ", "941 2354 1059 2509", 176))
                add(vowel)
                add(key(209, ",", "661 2550 779 2705", 200))
                add(space)
                add(key(211, ".", "1261 2550 1379 2705", 200))
            }
            frame = frame.copy(nodes = nodes, keyboard = ime, inputTarget = editor, inputTargetId = editor.id)
            visualProof = VisualTargetProof(
                ScreenImage(360, 780, "image/jpeg", byteArrayOf(1), ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
                "test-image", TapBounds(0, 0, 1440, 3120), "test-window", System.currentTimeMillis(),
                scope = "keyboard", sourceWindowId = ime.windowId, sourceImeWindowId = ime.windowId,
                sourceDisplayId = 0, rotation = 0, displayGeneration = 1, imeBounds = "0 1798 1440 3120",
                disclosedRegions = listOf(TapBounds(0, 1798, 1440, 3120)))
        }
        private val control = GenericControl(port, { true })
        fun wrapRecommendationCaptions(topology: WrappedStrip) {
            val captions = listOf(115, 118, 117).map { id -> port.frame.nodes.single { it.id == id } }
            val rects = listOf("14 1823 408 1928", "436 1823 830 1928", "858 1823 1252 1928")
            val inners = listOf("14 1812 408 1938", "436 1812 830 1938", "858 1812 1252 1938")
            val outers = listOf("0 1798 422 1952", "422 1798 844 1952", "844 1798 1266 1952")
            val root = 300
            val dedicated = 301
            val container = node(dedicated, "0 1798 1267 1952", 304)
            val ancestor = when (topology) {
                WrappedStrip.UNKNOWN -> 399 // The observed path is incomplete.
                WrappedStrip.DEDICATED -> dedicated
                WrappedStrip.SHARED_ROOT -> root
            }
            val wrappers = captions.flatMapIndexed { i, caption ->
                val outer = 310 + 2 * i
                listOf(node(outer, outers[i], ancestor), node(outer + 1, inners[i], outer),
                    caption.copy(bounds = rects[i], parent = outer + 1))
            }
            // Relative ownership and visible geometry follow the Galaxy report;
            // run-local node identifiers and unneeded intermediate ancestors do not.
            val raw = port.frame.nodes.filterNot { it.id in setOf(115, 116, 117, 118) }.map {
                if (it.id in setOf(134, 176)) it.copy(parent = 304) else it
            } + listOf(node(root, "0 127 1440 3120"), node(304, "0 1798 1440 3120", root),
                node(200, "240 2554 1409 2709", 304), node(302, "31 1981 1409 2109", 304),
                key(330, "ㅂ", "31 1981 149 2109", 302), key(331, "ㅁ", "171 1981 289 2109", 302),
                key(332, "ㄴ", "311 1981 429 2109", 302), key(333, "ㅊ", "451 1981 569 2109", 302)) +
                listOfNotNull(container.takeIf { topology == WrappedStrip.DEDICATED }) + wrappers
            port.frame = port.frame.copy(nodes = raw.filterNot { it.id in setOf(300, 301, 304, 310, 311, 312, 313, 314, 315) },
                keyboardSuggestionGeometry = raw.filter { it.sourceWindowId == ime.windowId }.map {
                    ImeGeometryNode(it.id, it.parent, checkNotNull(KeyboardImageFallbackPolicy.bounds(it.bounds)), when (it.id) {
                        115, 118, 117 -> ImeGeometryNode.Kind.CAPTION
                        311, 313, 315 -> ImeGeometryNode.Kind.WRAPPER
                        else -> ImeGeometryNode.Kind.OTHER
                    }, true)
                })
        }
        fun longClickableCenterRecommendation(topology: WrappedStrip) {
            wrapRecommendationCaptions(topology)
            // The other two captions have structural evidence but no literal key role.
            port.frame = port.frame.copy(nodes = port.frame.nodes.filterNot { it.id in setOf(115, 117) }.map {
                if (it.id == 118) it.copy(text = "ㄱ", keyboardKey = "ㄱ", longClickable = true) else it
            })
        }
        suspend fun observeJamo(withSpace: Boolean = false): JsonObject =
            observeLabels(if (withSpace) listOf("ㄱ", "ㅏ", "Space") else listOf("ㄱ", "ㅏ"))

        suspend fun observeLabels(labels: List<String>): JsonObject {
            val observed = control.observe(includeImage = true, imageScope = "keyboard",
                keyboardSequenceLabels = labels)
            assertTrue(observed.summary, observed.success)
            return observed.data
        }
        fun ids(observed: JsonObject): List<Int> =
            observed.getValue("nodes").jsonArray.map { it.jsonObject.getValue("node_id").jsonPrimitive.int }

        suspend fun requestJamo(observed: JsonObject, withSpace: Boolean = false,
            screenSpaceFirstRect: Boolean = false) = control.visualKeySequence(buildJsonObject {
            put("session_id", session.id)
            put("observation_id", observed.getValue("observation_id"))
            put("visual_id", observed.getValue("visual").jsonObject.getValue("visual_id"))
            put("visual_target_confirmed", true)
            put("text", if (withSpace) "가 " else "가")
            put("steps", buildJsonArray {
                val firstRect = if (screenSpaceFirstRect) listOf(451, 2154, 569, 2309)
                    else listOf(118, 545, 137, 568)
                val steps = listOf(Triple(consonant, "ㄱ", firstRect),
                    Triple(vowel, "ㅏ", listOf(275, 595, 295, 615))) +
                    if (withSpace) listOf(Triple(space, "Space", listOf(225, 645, 285, 665))) else emptyList()
                for ((key, label, rect) in steps) add(buildJsonObject {
                    put("node_id", key.id)
                    put("label", label)
                    put("image_rect", JsonArray(rect.map { JsonPrimitive(it) }))
                })
            })
        })
    }

    private enum class WrappedStrip { UNKNOWN, DEDICATED, SHARED_ROOT }

    private suspend fun assertNoWrappedJamoTouch(fixture: JamoCandidateFixture, situation: String,
        reason: String = "keyboard_sequence_candidate_undisclosed") {
        val observed = fixture.observeJamo(withSpace = true)
        assertTrue(situation, fixture.ids(observed).isEmpty())
        val stopped = fixture.requestJamo(observed, withSpace = true)
        assertFalse(situation, stopped.success)
        assertEquals(situation, reason, stopped.summary)
        assertEquals(situation, 0, stopped.data.getValue("completed_keys").jsonPrimitive.int)
        assertFalse(situation, stopped.data.getValue("action_attempted").jsonPrimitive.boolean)
        assertFalse(situation, stopped.data.getValue("outcome_unknown").jsonPrimitive.boolean)
        assertEquals(situation, 0, fixture.port.writes)
        assertEquals(situation, "", fixture.port.frame.inputTarget?.text)
        assertEquals(situation, 0, fixture.port.frame.inputTarget?.selectionStart)
        assertEquals(situation, 0, fixture.port.frame.inputTarget?.selectionEnd)
    }

    @Test fun unavailableWrappedGeometryStillBlocksOnlyConsonantBeforeAnyTouch() = runBlocking {
        for (samsung in listOf(true, false)) {
            val fixture = JamoCandidateFixture(suggestionStrip = true, samsung = samsung)
            fixture.wrapRecommendationCaptions(if (samsung) WrappedStrip.UNKNOWN else WrappedStrip.DEDICATED)
            assertTrue(fixture.ids(fixture.observeLabels(listOf("ㄱ"))).isEmpty())
            assertEquals(listOf(192), fixture.ids(fixture.observeLabels(listOf("ㅏ"))))
            assertEquals(listOf(210), fixture.ids(fixture.observeLabels(listOf("Space"))))
            assertNoWrappedJamoTouch(fixture, "unknown path or unobserved IME")
        }
    }

    @Test fun confirmedWrappedStripKeepsThePhysicalKeyAndImageSpaceThroughRecommendationRefresh() = runBlocking {
        val fixture = JamoCandidateFixture(suggestionStrip = true, samsung = true)
        fixture.wrapRecommendationCaptions(WrappedStrip.DEDICATED)
        val port = fixture.port
        // The left recommendation is partly masked. No masked pixel is
        // promoted and the independently certified physical keys stay usable.
        port.visualProof = checkNotNull(port.visualProof).copy(disclosedRegions = listOf(
            TapBounds(110, 1798, 1440, 1952), TapBounds(0, 1952, 1440, 3120)))
        assertHangulSequenceWithSuggestionRefresh(fixture)
    }

    @Test fun confirmedLongClickableRecommendationIsNotAPhysicalKeyAtAnyStep() = runBlocking {
        val fixture = JamoCandidateFixture(suggestionStrip = true, samsung = true)
        fixture.longClickableCenterRecommendation(WrappedStrip.DEDICATED)
        val port = fixture.port
        port.visualProof = checkNotNull(port.visualProof).copy(disclosedRegions = listOf(
            TapBounds(140, 1798, 1300, 1952), TapBounds(0, 1952, 1440, 3120)))
        assertEquals(fixture.expectedIds + 210, fixture.ids(fixture.observeJamo(withSpace = true)))
        for (label in listOf("ㄱ", "ㅏ")) {
            val frame = port.frame.copy(visual = port.visualProof, nodes = port.frame.nodes.map {
                if (it.id == 118) it.copy(text = label, keyboardKey = label) else it
            })
            assertFalse(VisualSequenceCandidates.layoutNodes(frame).any { it.id == 118 })
        }
        assertHangulSequenceWithSuggestionRefresh(fixture, recommendationId = 118)
    }

    @Test fun longClickableRecommendationWithoutDedicatedEvidenceNeverUnblocksTheCollision() = runBlocking {
        for (topology in listOf(WrappedStrip.UNKNOWN, WrappedStrip.SHARED_ROOT)) {
            val fixture = JamoCandidateFixture(suggestionStrip = true, samsung = true)
            fixture.longClickableCenterRecommendation(topology)
            val frame = fixture.port.frame.copy(visual = fixture.port.visualProof)
            assertTrue(VisualSequenceCandidates.layoutNodes(frame).any { it.id == 118 })
            assertNoWrappedJamoTouch(fixture, topology.name)
        }
        val legacy = JamoCandidateFixture(suggestionStrip = true, samsung = true)
        legacy.port.frame = legacy.port.frame.copy(nodes = legacy.port.frame.nodes.map {
            if (it.id == 117) it.copy(longClickable = true) else it
        })
        assertNoWrappedJamoTouch(legacy, "same-parent strip is not enough for long-click exclusion")
    }

    @Test fun confirmedLongClickableRecommendationDoesNotAuthorizeDuplicateOrMaskedPhysicalKeys() = runBlocking {
        for (duplicate in listOf(true, false)) {
            val fixture = JamoCandidateFixture(suggestionStrip = true, secondPhysicalKey = duplicate, samsung = true)
            fixture.longClickableCenterRecommendation(WrappedStrip.DEDICATED)
            val port = fixture.port
            if (!duplicate) port.visualProof = checkNotNull(port.visualProof).copy(disclosedRegions = listOf(
                TapBounds(0, 1798, 560, 3120), TapBounds(580, 1798, 1440, 3120)))
            assertNoWrappedJamoTouch(fixture, if (duplicate) "duplicate physical keys" else "masked physical key")
        }
    }

    @Test fun wrappedRecommendationAmbiguityCannotPromoteAnUnprovenTarget() = runBlocking {
        val cases = listOf("shared-ime-root", "recommendation-only", "duplicate-physical-key",
            "overlapping-strip", "foreign-ancestor", "cyclic-path", "physical-under-strip", "overlapping-wrappers",
            "masked-physical", "wrong-image-owner")
        for (scenario in cases) {
            val fixture = JamoCandidateFixture(suggestionStrip = true, samsung = true,
                secondPhysicalKey = scenario == "duplicate-physical-key")
            fixture.wrapRecommendationCaptions(
                if (scenario == "shared-ime-root") WrappedStrip.SHARED_ROOT else WrappedStrip.DEDICATED)
            val port = fixture.port
            val tree = port.frame.keyboardSuggestionGeometry
            val nodes = port.frame.nodes
            port.frame = port.frame.copy(
                nodes = if (scenario == "recommendation-only") nodes.filterNot { it.id in 163..164 } else nodes,
                keyboardSuggestionGeometry = when (scenario) {
                    "recommendation-only" -> tree.filterNot { it.index in 163..164 }
                    "overlapping-strip" -> tree.map { if (it.index == 301) it.copy(bounds = TapBounds(0, 1798, 1267, 2220)) else it }
                    "foreign-ancestor" -> tree.filterNot { it.index == 301 }
                    "cyclic-path" -> tree.map { if (it.index == 301) it.copy(parent = 310) else it }
                    "physical-under-strip" -> tree.map { if (it.index == 134) it.copy(parent = 301) else it }
                    "overlapping-wrappers" -> tree.map { if (it.index == 310) it.copy(bounds = TapBounds(0, 1798, 430, 1952)) else it }
                    else -> tree
                })
            if (scenario == "masked-physical") port.visualProof = checkNotNull(port.visualProof).copy(disclosedRegions = listOf(
                TapBounds(0, 1798, 1440, 2110), TapBounds(0, 2110, 560, 3120), TapBounds(580, 2110, 1440, 3120)))
            if (scenario == "wrong-image-owner") port.visualProof = checkNotNull(port.visualProof).copy(sourceImeWindowId = 99)
            // A wrong owner can reject before disclosing the image-based sequence,
            // or at its owner precheck if the image metadata is still present.
            assertNoWrappedJamoTouch(fixture, scenario, if (scenario == "wrong-image-owner")
                "keyboard_visual_owner_unverified" else "keyboard_sequence_candidate_undisclosed")
        }
    }

    @Test fun wrappedStripDoesNotRemoveAWideKeyCorroboratedByItsPhysicalRow() = runBlocking {
        val fixture = JamoCandidateFixture(suggestionStrip = true, samsung = true)
        fixture.wrapRecommendationCaptions(WrappedStrip.DEDICATED)
        val frame = fixture.port.frame
        val large = TapBounds(854, 2154, 1220, 2309)
        fixture.port.frame = frame.copy(nodes = frame.nodes.filterNot { it.id == 164 }.map {
                if (it.id == 163) it.copy(bounds = "854 2154 1220 2309", clickable = true,
                    visualKeyChildCount = 0) else it },
            keyboardSuggestionGeometry = frame.keyboardSuggestionGeometry.filterNot { it.index == 164 }.map {
                if (it.index == 163) it.copy(bounds = large) else it })
        assertEquals(fixture.expectedIds, fixture.ids(fixture.observeJamo()))
        assertEquals(0, fixture.port.writes)
    }

    @Test fun visibleCropCannotAuthorizeAPhysicalKeyWithMaskedBoundary() = runBlocking {
        for (suggestionStrip in listOf(false, true)) {
            val fixture = JamoCandidateFixture(suggestionStrip = suggestionStrip)
            val port = fixture.port
            val expected = fixture.expectedIds + 210
            assertEquals(expected, fixture.ids(fixture.observeJamo(withSpace = true)))
            val proof = checkNotNull(port.visualProof).copy(disclosedRegions = listOf(
                TapBounds(0, 1798, 1440, 2100), TapBounds(0, 2100, 560, 3120), TapBounds(580, 2100, 1440, 3120)))
            assertNotNull(proof.map(TapBounds(118, 545, 137, 568)))
            assertFalse(OwnedPixelPolicy.contains(proof.disclosedRegions, TapBounds(451, 2154, 569, 2309)))
            port.visualProof = proof
            val observed = fixture.observeJamo(withSpace = true)
            // A collision needs enough pixels to corroborate the physical key
            // already at selection time. Without a collision, the disclosed key
            // still cannot pass the independent dispatch pixel check.
            assertEquals(if (suggestionStrip) emptyList<Int>() else expected, fixture.ids(observed))
            val stopped = fixture.requestJamo(observed, withSpace = true)
            assertFalse(stopped.success)
            val reason = if (suggestionStrip) "keyboard_sequence_candidate_undisclosed"
                else "keyboard_sequence_pixels_unverified"
            assertEquals(reason, stopped.summary)
            assertEquals(0, stopped.data.getValue("completed_keys").jsonPrimitive.int)
            assertFalse(stopped.data.getValue("action_attempted").jsonPrimitive.boolean)
            assertEquals(0, port.writes)
        }
    }

    @Test fun hangulRefreshWithChangedPhysicalLayoutOrEditorStopsWithoutReplay() = runBlocking {
        for (changeLayout in listOf(true, false)) {
            val fixture = JamoCandidateFixture(suggestionStrip = true, samsung = true)
            fixture.wrapRecommendationCaptions(WrappedStrip.DEDICATED)
            val port = fixture.port
            val touched = mutableListOf<Int>()
            port.onWrite = { input ->
                touched += input.getValue("node_id").jsonPrimitive.int
                val editor = checkNotNull(port.frame.inputTarget).copy(text = "ㄱ", empty = false,
                    selectionStart = 1, selectionEnd = 1)
                port.frame = port.frame.copy(fingerprint = "confirmed-first-jamo", inputTarget = editor,
                    nodes = port.frame.nodes.map { if (it.id == editor.id) editor else it })
            }
            var readsAfterFirst = 0
            port.onCapture = {
                // First postcondition is confirmed before the renewal snapshot changes.
                if (port.writes == 1 && ++readsAfterFirst == 2) {
                    val editor = checkNotNull(port.frame.inputTarget)
                    val fresh = if (changeLayout) editor else editor.copy(resourceId = "example.app:id/other_editor")
                    port.frame = port.frame.copy(inputTarget = fresh, fingerprint = "renewal-changed",
                        keyboardSuggestionGeometry = port.frame.keyboardSuggestionGeometry.map {
                            if (changeLayout && it.index == 166) it.copy(bounds = TapBounds(746, 2154, 864, 2309)) else it
                        }, nodes = port.frame.nodes.map {
                            when {
                                it.id == editor.id -> fresh
                                changeLayout && it.id == 166 -> it.copy(bounds = "746 2154 864 2309")
                                else -> it
                            }
                        })
                }
            }
            val observed = fixture.observeJamo(withSpace = true)
            assertEquals(fixture.expectedIds + 210, fixture.ids(observed))
            val stopped = fixture.requestJamo(observed, withSpace = true)
            assertFalse(stopped.success)
            assertEquals("keyboard_sequence_layout_or_target_changed", stopped.summary)
            assertEquals(1, stopped.data.getValue("completed_keys").jsonPrimitive.int)
            assertEquals(0, stopped.data.getValue("confirmed_image_fallback_spaces").jsonPrimitive.int)
            assertFalse(stopped.data.getValue("outcome_unknown").jsonPrimitive.boolean)
            assertFalse(fixture.requestJamo(observed, withSpace = true).success)
            assertEquals(listOf(163), touched)
            assertEquals(1, port.writes)
        }
    }

    @Test fun physicalJamoWrapperAndLabelAreOneCandidate() = runBlocking {
        val fixture = JamoCandidateFixture()
        assertEquals(fixture.expectedIds, fixture.ids(fixture.observeJamo()))
        assertEquals(0, fixture.port.writes)
    }

    @Test fun sameLabelSuggestionDoesNotHideThePhysicalJamoKey() = runBlocking {
        val fixture = JamoCandidateFixture(suggestionStrip = true)
        assertEquals("Only the physical ㄱ and ㅏ belong to the sequence",
            fixture.expectedIds, fixture.ids(fixture.observeJamo()))
        assertEquals(0, fixture.port.writes)
    }

    /** The actual API must reject a screen-space rectangle accidentally passed
     * as image_rect; node.bounds and image_rect are not interchangeable. */
    @Test fun galaxyScreenSpaceRectSubmittedAsImageRectIsRejectedBeforeAnyTouch() = runBlocking {
        val fixture = JamoCandidateFixture(samsung = true)
        val observed = fixture.observeJamo()
        assertEquals(fixture.expectedIds, fixture.ids(observed))
        val result = fixture.requestJamo(observed, screenSpaceFirstRect = true)
        assertFalse(result.success)
        assertEquals("keyboard_sequence_pixels_unverified", result.summary)
        assertEquals("image_rect_outside_frame", result.data["pixel_failure_stage"]?.jsonPrimitive?.content)
        assertEquals(1, result.data["pixel_failure_key"]?.jsonPrimitive?.int)
        assertEquals(0, result.data.getValue("completed_keys").jsonPrimitive.int)
        assertFalse(result.data.getValue("action_attempted").jsonPrimitive.boolean)
        assertFalse(result.data.getValue("outcome_unknown").jsonPrimitive.boolean)
        assertEquals(0, fixture.port.writes)
    }

    @Test fun twoPhysicalJamoKeysWithTheSameLabelAreRefusedBeforeAnyTouch() = runBlocking {
        val fixture = JamoCandidateFixture(suggestionStrip = true, secondPhysicalKey = true)
        val observed = fixture.observeJamo()
        assertTrue(fixture.ids(observed).isEmpty())
        val refused = fixture.requestJamo(observed)
        assertFalse(refused.success)
        assertEquals("keyboard_sequence_candidate_undisclosed", refused.summary)
        assertFalse(refused.data.getValue("action_attempted").jsonPrimitive.boolean)
        assertEquals(0, fixture.port.writes)
    }

    @Test fun suggestionWithoutPhysicalKeyIsNotDisclosedOrTouched() = runBlocking {
        val fixture = JamoCandidateFixture(suggestionStrip = true)
        fixture.port.frame = fixture.port.frame.copy(nodes = fixture.port.frame.nodes.filterNot { it.id in 163..164 })
        val observed = fixture.observeJamo()
        assertTrue(fixture.ids(observed).isEmpty())
        assertFalse(fixture.requestJamo(observed).success)
        assertEquals(0, fixture.port.writes)
    }

    @Test fun ambiguousSuggestionGeometryOrOwnershipDoesNotResolveTheCollision() = runBlocking {
        for (uncertain in listOf("same-parent", "overlap", "missing-row", "masked-strip", "native-key", "isolated-wide-key")) {
            val fixture = JamoCandidateFixture(suggestionStrip = true)
            val port = fixture.port
            val nodes = port.frame.nodes
            port.frame = port.frame.copy(nodes = when (uncertain) {
                "missing-row" -> nodes.filterNot { it.id in setOf(160, 161, 165, 166, 189, 190) }
                "same-parent" -> nodes.map { if (it.id == 117) it.copy(parent = 134) else it }
                "overlap" -> nodes.map { if (it.id == 117) it.copy(bounds = "858 2110 1252 2215") else it }
                "native-key" -> nodes.map { if (it.id == 117) it.copy(textEntryKey = true) else it }
                "isolated-wide-key" -> nodes.map { if (it.id == 117) it.copy(parent = 301) else it }
                else -> nodes
            })
            if (uncertain == "masked-strip") port.visualProof = port.visualProof?.copy(
                disclosedRegions = listOf(TapBounds(0, 1975, 1440, 3120)))
            val observed = fixture.observeJamo()
            assertTrue(uncertain, fixture.ids(observed).isEmpty())
            assertFalse(uncertain, fixture.requestJamo(observed).success)
            assertEquals(uncertain, 0, port.writes)
        }
    }

    @Test fun wideKeyCorroboratedByItsPhysicalRowIsNotMistakenForARecommendation() = runBlocking {
        val fixture = JamoCandidateFixture(suggestionStrip = true)
        val port = fixture.port
        port.frame = port.frame.copy(nodes = port.frame.nodes.filterNot { it.id == 164 }.map {
            if (it.id == 163) it.copy(bounds = "854 2154 1220 2309", clickable = true,
                visualKeyChildCount = 0)
            else it
        })
        assertEquals(fixture.expectedIds, fixture.ids(fixture.observeJamo()))
        assertEquals(0, port.writes)
    }

    @Test fun hangulSequenceRechecksRecommendationCollisionAndConfirmsImageSpace() = runBlocking {
        assertHangulSequenceWithSuggestionRefresh(JamoCandidateFixture(suggestionStrip = true))
    }

    // Same exact-effect oracle for the existing strip and the device-confirmed wrapper shape.
    private suspend fun assertHangulSequenceWithSuggestionRefresh(fixture: JamoCandidateFixture, recommendationId: Int = 117) {
        val port = fixture.port
        val touched = mutableListOf<Int>()
        port.onWrite = { input ->
            val id = input.getValue("node_id").jsonPrimitive.int
            touched += id
            val value = when (id) {
                163 -> "ㄱ"
                192 -> "가"
                210 -> {
                    assertEquals("Space", input["image_key_label"]?.jsonPrimitive?.content)
                    "가 "
                }
                else -> error("Non-key target: $id")
            }
            val editor = checkNotNull(port.frame.inputTarget).copy(text = value, empty = false,
                selectionStart = value.length, selectionEnd = value.length)
            port.frame = port.frame.copy(fingerprint = value, inputTarget = editor,
                nodes = port.frame.nodes.map {
                    when {
                        it.id == editor.id -> editor
                        it.id == recommendationId && id == 163 -> it.copy(text = "ㅏ", keyboardKey = "ㅏ")
                        else -> it
                    }
                })
        }
        val observed = fixture.observeJamo(withSpace = true)
        assertEquals(fixture.expectedIds + 210, fixture.ids(observed))
        assertFalse(observed.toString().contains("keyboardSuggestionGeometry"))
        val result = fixture.requestJamo(observed, withSpace = true)
        assertTrue(result.summary, result.success)
        assertEquals(listOf(163, 192, 210), touched)
        assertEquals(3, result.data.getValue("completed_keys").jsonPrimitive.int)
        assertEquals(1, result.data.getValue("confirmed_image_fallback_spaces").jsonPrimitive.int)
        assertTrue(result.data.getValue("app_result_verified").jsonPrimitive.boolean)
        assertFalse(result.data.getValue("outcome_unknown").jsonPrimitive.boolean)
        assertEquals("가 ", port.frame.inputTarget?.text)
        assertEquals(2, port.frame.inputTarget?.selectionStart)
        assertEquals(2, port.frame.inputTarget?.selectionEnd)
    }

    @Test fun undisclosedNonliteralSiblingCaptionsCanCorroborateTheStripWithoutBecomingTargets() = runBlocking {
        val fixture = JamoCandidateFixture(suggestionStrip = true)
        val port = fixture.port
        val siblings = port.frame.nodes.filter { it.id in setOf(115, 118) }
        val slots = siblings.map { KeyboardCaptionSlot(it.parent, checkNotNull(KeyboardImageFallbackPolicy.bounds(it.bounds))) }
        port.frame = port.frame.copy(nodes = port.frame.nodes - siblings.toSet(), keyboardCaptionSlots = slots)
        val observed = fixture.observeJamo()
        assertEquals(fixture.expectedIds, fixture.ids(observed))
        assertFalse(observed.toString().contains("keyboardCaptionSlots"))
        assertEquals(0, port.writes)
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
        assertEquals(4, refusals) // Initial refusal plus three safe retries.
        assertEquals(3, blockedRetry.data.getValue("pre_touch_retries").jsonPrimitive.int)
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

    private class FollowUpImageFixture {
        val port = Port()
        private val active = ControlSession().apply { start() }
        private val ime = ControlKeyboard(4, "ime", "ime/.Keyboard")
        private val editor = ControlNode(1, sourceWindowId = 3, sourcePackage = "example.app",
            editable = true, focused = true, focusable = true, selectionStart = 0, selectionEnd = 0)
        private fun key(id: Int, label: String, x: Int) = ControlNode(id, text = label, keyboardKey = label,
            sourceWindowId = 4, sourcePackage = "ime", visualKeyCandidate = true,
            bounds = "$x 50 ${x + 30} 85", visualKeyChildCount = 0)
        private val keys = listOf(key(2, "n", 15), key(3, "v", 50), key(4, "d", 85))
        val touched = mutableListOf<String>()
        val control = GenericControl(port, { true })
        init {
            port.controlSession = active
            port.frame = port.frame.copy(nodes = listOf(editor) + keys, keyboard = ime,
                inputTarget = editor, inputTargetId = editor.id)
            port.visualProof = VisualTargetProof(
                ScreenImage(140, 120, "image/jpeg", byteArrayOf(1), ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
                "test-image", TapBounds(0, 0, 140, 120), "test-window", System.currentTimeMillis(),
                scope = "keyboard", sourceWindowId = 4, sourceImeWindowId = 4, sourceDisplayId = 0,
                rotation = 0, displayGeneration = 1, imeBounds = "0 0 140 120",
                disclosedRegions = listOf(TapBounds(0, 0, 140, 120)))
            port.onWrite = { input ->
                val key = keys.single { it.id == input.getValue("node_id").jsonPrimitive.int }
                touched += key.keyboardKey
                setEditor(checkNotNull(port.frame.inputTarget).text + key.keyboardKey)
            }
        }
        fun setEditor(value: String, caret: Int = value.length) {
            val edited = editor.copy(text = value, selectionStart = caret, selectionEnd = caret, empty = value.isEmpty())
            port.frame = port.frame.copy(inputTarget = edited, nodes = listOf(edited) + keys, fingerprint = value)
        }
        suspend fun run(): ai.closepaw.chatgpt.operations.OperationResult {
            val observed = control.observe(includeImage = true, imageScope = "keyboard", keyboardSequenceLabels = listOf("n", "v"))
            assertTrue(observed.summary, observed.success)
            return control.visualKeySequence(buildJsonObject {
                put("session_id", active.id)
                put("observation_id", observed.data.getValue("observation_id"))
                put("visual_id", observed.data.getValue("visual").jsonObject.getValue("visual_id"))
                put("visual_target_confirmed", true)
                put("text", "nv")
                put("steps", buildJsonArray {
                    for ((index, key) in keys.take(2).withIndex()) add(buildJsonObject {
                        put("node_id", key.id)
                        put("label", key.keyboardKey)
                        put("image_rect", JsonArray(listOf(20 + 35 * index, 55, 38 + 35 * index, 80).map { JsonPrimitive(it) }))
                    })
                })
            })
        }
    }

    @Test fun followUpImageFailurePreservesReasonWithoutReplayingFirstKey() = runBlocking {
        for ((reason, expectedReads) in listOf("screen_share_revoked" to 1, "visual_post_screen_changed" to 4)) {
            val fixture = FollowUpImageFixture()
            var followUpReads = 0
            fixture.port.denyVisual = { if (fixture.touched.isNotEmpty()) { followUpReads++; reason } else null }
            val result = fixture.run()
            assertFalse(result.success)
            assertEquals("keyboard_sequence_new_image_unavailable", result.summary)
            assertEquals(reason, result.data["observation_reason_code"]?.jsonPrimitive?.content)
            assertEquals(1, result.data.getValue("completed_keys").jsonPrimitive.int)
            assertFalse(result.data.getValue("outcome_unknown").jsonPrimitive.boolean)
            assertEquals(expectedReads, followUpReads)
            assertEquals(listOf("n"), fixture.touched)
            assertEquals("n", fixture.port.frame.inputTarget?.text)
        }
    }

    @Test fun windowMarkerFollowsTheSameKeyAndReadIntoTheNativeAdapter() = runBlocking {
        val fixture = FollowUpImageFixture()
        val marks = mutableListOf<DisplayWindowChangeJournal.Mark?>()
        fixture.port.observeWindowTrace = { marks.add(it) }
        val result = fixture.run()
        assertTrue(result.summary, result.success)
        assertEquals(listOf(1 to 0, 2 to 1), marks.map { checkNotNull(it).let { m -> m.keyIndex to m.readAttempt } })
        assertTrue(marks.all { it?.generation == 1L })
        assertEquals(listOf("n", "v"), fixture.touched)
    }

    @Test fun keyboardPostCheckClassifiesRefusalsWithoutDisclosingRawFrameData() {
        val parts = KeyboardVisualFingerprintParts("private-context", "private-app-tree", "private-ime-tree")
        val before = ControlFrame("private.pkg", 3, "private-fingerprint", emptyList(),
            keyboardVisualFingerprintParts = parts)
        val cases = listOf(
            before.copy(reason = "screen_changed") to
                """{"source":"post_capture_frame_denied"}""",
            before.copy(windowId = 4) to
                """{"source":"window_identity","app_identity_changed":false,"target_window_changed":true,"foreground_window_changed":false}""",
            before.copy(packageName = "other.pkg", foregroundWindowId = 5) to
                """{"source":"window_identity","app_identity_changed":true,"target_window_changed":false,"foreground_window_changed":true}""",
            before.copy(fingerprint = "new-fp", keyboardVisualFingerprintParts = parts.copy(appTree = "new-app-tree")) to
                """{"source":"full_fingerprint","frame_context_changed":false,"app_tree_changed":true,"ime_tree_changed":false}""",
            before.copy(fingerprint = "new-fp", keyboardVisualFingerprintParts = parts.copy(imeTree = "new-ime-tree")) to
                """{"source":"full_fingerprint","frame_context_changed":false,"app_tree_changed":false,"ime_tree_changed":true}""",
            before.copy(fingerprint = "new-fp", keyboardVisualFingerprintParts = parts.copy(context = "new-context")) to
                """{"source":"full_fingerprint","frame_context_changed":true,"app_tree_changed":false,"ime_tree_changed":false}""",
            before.copy(fingerprint = "new-fp", keyboardVisualFingerprintParts = null) to
                """{"source":"full_fingerprint"}""",
        )
        for ((after, expected) in cases) {
            assertEquals("screen_changed", GenericControlActionPolicy.refusal(before, after, "visual_key"))
            assertEquals(Json.parseToJsonElement(expected), KeyboardVisualPostCheck.describe(before, after))
        }
    }

    @Test fun imeComparisonIgnoresShiftFromAppNodeCountButKeepsImeHierarchy() {
        fun node(id: Int, parent: Int, base: Int) = KeyboardVisualPostCheck.relativeImeNode(
            buildJsonObject {
                put("id", id); put("parent", parent); put("text_hash", "private")
            }, id, parent, base)
        assertEquals(node(30, -1, 30), node(40, -1, 40))
        assertEquals(node(34, 31, 30), node(44, 41, 40))
        assertNotEquals(node(34, 31, 30), node(44, 42, 40))
    }

    @Test fun keyboardPostCheckCapsAtThreeRetriesWithoutReplayingTheKey() = runBlocking {
        val fixture = FollowUpImageFixture()
        fixture.port.denyVisual = { if (fixture.touched.isNotEmpty()) "visual_post_screen_changed" else null }
        var reads = 0
        fixture.port.describePostCheck = { frame ->
            val changed = if (++reads == 1) frame.copy(foregroundWindowId = frame.foregroundWindowId + 1)
                else frame.copy(fingerprint = "new private fingerprint")
            KeyboardVisualPostCheck.describe(frame, changed)
        }
        val result = fixture.run()
        assertFalse(result.success)
        assertEquals("keyboard_sequence_new_image_unavailable", result.summary)
        assertEquals("visual_post_screen_changed", result.data["observation_reason_code"]?.jsonPrimitive?.content)
        assertEquals("visual_post_screen_changed", result.data["observation_retry_reason_code"]?.jsonPrimitive?.content)
        assertEquals(listOf("n"), fixture.touched)
        assertFalse(result.data.getValue("outcome_unknown").jsonPrimitive.boolean)
        val diagnostics = result.data.getValue("keyboard_post_check_refusals").jsonArray.map { it.jsonObject }
        assertEquals(listOf(2, 2, 2, 2), diagnostics.map { it.getValue("key_index").jsonPrimitive.int })
        assertEquals(listOf(1, 2, 3, 4), diagnostics.map { it.getValue("read_attempt").jsonPrimitive.int })
        assertEquals(3, result.data.getValue("pre_touch_retries").jsonPrimitive.int)
        assertTrue(diagnostics.all { it.getValue("read_elapsed_ms").jsonPrimitive.long >= 0L })
        assertEquals(listOf("window_identity", "full_fingerprint", "full_fingerprint", "full_fingerprint"),
            diagnostics.map { it.getValue("change").jsonObject.getValue("source").jsonPrimitive.content })
    }

    @Test fun oneTransientFollowUpImageRetryStillRequiresTheExactEditor() = runBlocking {
        for (changeEditor in listOf(false, true)) {
            val fixture = FollowUpImageFixture()
            var followUpReads = 0
            fixture.port.denyVisual = {
                if (fixture.touched.isNotEmpty() && ++followUpReads == 1) "visual_post_screen_changed" else null
            }
            fixture.port.onCapture = {
                if (changeEditor && followUpReads == 1) fixture.setEditor("nExtra", 1)
            }
            val result = fixture.run()
            assertEquals(result.summary, !changeEditor, result.success)
            assertEquals("visual_post_screen_changed", result.data["observation_retry_reason_code"]?.jsonPrimitive?.content)
            assertEquals(2, followUpReads)
            assertEquals(if (changeEditor) listOf("n") else listOf("n", "v"), fixture.touched)
            if (!changeEditor) {
                assertEquals(2, result.data.getValue("completed_keys").jsonPrimitive.int)
                assertEquals("nv", fixture.port.frame.inputTarget?.text)
                assertEquals(2, fixture.port.frame.inputTarget?.selectionEnd)
            }
        }
    }

    @Test fun followUpObservationSharesTheBoundedThreeRetryBudget() = runBlocking {
        val fixture = FollowUpImageFixture()
        var firstRefusal = true
        var followUpReads = 0
        fixture.port.beforeWrite = {
            if (!firstRefusal) null else {
                firstRefusal = false
                ControlEffect(false, false, "screen_changed")
            }
        }
        fixture.port.denyVisual = {
            if (fixture.touched.isEmpty()) null else {
                followUpReads++
                "visual_post_screen_changed"
            }
        }
        val result = fixture.run()
        assertFalse(result.success)
        assertEquals("visual_post_screen_changed", result.data["observation_reason_code"]?.jsonPrimitive?.content)
        assertEquals("visual_post_screen_changed", result.data["observation_retry_reason_code"]?.jsonPrimitive?.content)
        assertEquals(3, followUpReads)
        assertEquals(3, result.data.getValue("pre_touch_retries").jsonPrimitive.int)
        assertEquals(listOf("n"), fixture.touched)
    }

    @Test fun followUpSourceFailuresPreserveStageAndConditionWithoutAnotherTouch() = runBlocking {
        for (stage in VisualSourceStage.entries) for (condition in listOf(
            VisualSourceCondition.DISPLAY_WINDOW_CHANGED, VisualSourceCondition.IMAGE_CONSENT_CHANGED,
            VisualSourceCondition.CONTROL_SESSION_CHANGED,
        )) {
            val fixture = FollowUpImageFixture()
            var valid = true
            val gate = VisualSourceGate(
                consentValid = { valid || condition != VisualSourceCondition.IMAGE_CONSENT_CHANGED },
                displayValid = { valid || condition != VisualSourceCondition.DISPLAY_WINDOW_CHANGED },
                sourceValid = { true },
                sessionValid = { valid || condition != VisualSourceCondition.CONTROL_SESSION_CHANGED },
            )
            fixture.port.visualProof = checkNotNull(fixture.port.visualProof)
                .copy(sourceDisclosureAllowed = gate::allowed, sourceDisclosureGate = gate)
            var followUpReads = 0
            var blockedAttempts = 0
            fixture.port.denyVisual = {
                if (fixture.touched.isNotEmpty() && ++followUpReads == 1) "visual_post_screen_changed" else null
            }
            if (stage == VisualSourceStage.ACTION_VALIDATION) {
                // Capture for action validation occurs after the successful second observation.
                fixture.port.onCapture = { if (followUpReads == 2) { valid = false; blockedAttempts++ } }
            } else {
                fixture.port.beforeWrite = {
                    if (fixture.touched.isEmpty()) null else {
                        valid = false
                        blockedAttempts++
                        val info = checkNotNull(fixture.port.visualProof?.sourceFailure(stage))
                        ControlEffect(false, false, VISUAL_SOURCE_REFUSED, info)
                    }
                }
            }
            val result = fixture.run()
            assertFalse(result.success)
            assertEquals(VISUAL_SOURCE_REFUSED, result.summary)
            assertEquals(VisualSourceFailure(stage, condition).json(), result.data["visual_source_failure"])
            assertEquals("visual_post_screen_changed", result.data["observation_retry_reason_code"]?.jsonPrimitive?.content)
            assertEquals(1, result.data.getValue("completed_keys").jsonPrimitive.int)
            assertFalse(result.data.getValue("outcome_unknown").jsonPrimitive.boolean)
            assertEquals(1, blockedAttempts)
            assertEquals(2, followUpReads)
            assertEquals(listOf("n"), fixture.touched)
            assertEquals("n", fixture.port.frame.inputTarget?.text)
        }
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
        var denyVisual: (() -> String?)? = null
        var describePostCheck: ((ControlFrame) -> JsonObject)? = null
        var observeWindowTrace: ((DisplayWindowChangeJournal.Mark?) -> Unit)? = null

        override suspend fun visualKeyboardSequenceFollowUp(frame: ControlFrame,
            onPostCheckRefusal: (JsonObject) -> Unit): CertifiedKeyboardFrame? {
            val proof = visualKeyboard(frame)
            if (proof == null && visualFailure() == "visual_post_screen_changed")
                describePostCheck?.invoke(frame)?.let(onPostCheckRefusal)
            return proof?.let { CertifiedKeyboardFrame(frame, it) }
        }
        private var lastVisualDenial: String? = null
        override fun visualFailure() = lastVisualDenial ?: super.visualFailure()
        override suspend fun visualKeyboard(frame: ControlFrame): VisualTargetProof? {
            lastVisualDenial = denyVisual?.invoke()
            if (lastVisualDenial != null) return null
            return visualProof?.copy(id = java.util.UUID.randomUUID().toString(), at = System.currentTimeMillis())
        }
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
            observeWindowTrace?.invoke(kotlinx.coroutines.currentCoroutineContext()[KeyboardWindowTrace]?.mark)
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
