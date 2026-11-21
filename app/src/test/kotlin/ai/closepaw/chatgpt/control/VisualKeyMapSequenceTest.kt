package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.OperationResult
import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VisualKeyMapSequenceTest {
    private companion object {
        const val benchmark = "오늘 회의는 Zoom으로 3시에 시작! 자료는 A2개."
    }

    private class Fixture(private val chrome: Boolean = false) : ControlPort {
        val control = GenericControl(this, { allowed })
        val active = ControlSession().apply { start() }
        private val app = if (chrome) LocalImeGeometryFixture.CHROME else "test.app"
        private val imePackage = if (chrome) LocalImeGeometryFixture.SAMSUNG else "test.ime"
        val ime = ControlKeyboard(20, imePackage, if (chrome) "$imePackage/.service.HoneyBoardService" else "$imePackage/.Ime")
        private var editor = ControlNode(1, resourceId = if (chrome) "$app:id/url_bar" else "$app:id/isolated_editor",
            className = "EditText", editable = true, focused = true, focusable = true, selectionStart = 0,
            selectionEnd = 0, sourceWindowId = 10, sourcePackage = app, nativeEditorId = "editor-1")
        var duringFirstSpaceCapture: (() -> Unit)? = null
        var afterFirstSpaceCapture: (() -> Unit)? = null
        var alternativeCaption = 0
        var otherRegion = "other"
        var layoutRevision = "original"
        var suggestionMotion = false
        var captionChangeAtNativeSpace = false
        fun moveCaret() { editor = editor.copy(selectionStart = 0, selectionEnd = 0) }
        var allowed = true
        var rejectFourthOnce = false
        var fifthSourceRefusals = 0
        var fifthSourceRefusalsSeen = 0
        var fifthSourceCondition = VisualSourceCondition.DISPLAY_WINDOW_CHANGED
        var fifthSourceStage = VisualSourceStage.NATIVE_ACTION_VALIDATION
        var fifthSourceAttempted = false
        var advanceWindowVersion = true
        var reuseRejectedProofId = false
        var afterFifthSourceRefusal: (() -> Unit)? = null
        var duringFifthRenewal: (() -> Unit)? = null
        var invalidateFifthRenewal = false
        var transientFifthCaptureFailures = 0
        var initialProofAgeMs = 0L
        var wrongInitialProofId = false
        var onNextSequenceCapture: (() -> Unit)? = null
        private var windowVersion = 1L
        private var rejectedProofId: String? = null
        var keyOffset = 0
        var keyNodeIdRevision = 0
        var proposalOutputLimit = 50
        var touchContext = "verified touch region"
        fun replaceEditor() { editor = editor.copy(nativeEditorId = "different-editor") }
        var fallbackRefusal: String? = null
        var loseCallbackFor: String? = null
        var wrongEffectFor: String? = null
        var changeTargetAfter: String? = null
        var maskNextImage = false
        var partiallyMaskedNativeVowel = false
        var fullyMaskedNativeVowel = false
        val touched = mutableListOf<String>()
        var captures = 0
        var nativeChecks = 0
        private val frameBounds = TapBounds(0, 0, 1200, 150)
        private val controls = listOf("Shift", "한/영", "?123", "#+=", "ABC").mapIndexed { index, label ->
            ControlNode(90 + index, text = label, keyboardKey = label, clickable = true,
                bounds = "${(if (chrome) 820 else 0) + index * 40} 85 ${(if (chrome) 820 else 0) + (index + 1) * 40} 110",
                sourceWindowId = 20, sourcePackage = imePackage, visualKeyCandidate = true)
        }
        var state = "ko"
        var nextState: String? = null

        private val rows = mapOf(
            "ko" to (HangulKeyComposer.labels("ㄱㅏㄴㅂ" + benchmark.filter { it.code in 0xac00..0xd7a3 }).distinct() + "Space"),
            "en" to (('a'..'z').map(Char::toString) + "Space"),
            "shift" to (('A'..'Z').map(Char::toString) + "Space"),
            "numeric" to (('0'..'9').map(Char::toString) + "Space"),
            "symbol" to listOf("!", "?", "&", ".", "Space"),
        )

        fun key(label: String): ControlNode {
            val i = rows.getValue(state).indexOf(label)
            require(i >= 0)
            if (chrome && label == "Space") return ControlNode(i + 3, parent = 80,
                bounds = "165 82 325 112", sourceWindowId = 20, sourcePackage = imePackage,
                className = "android.view.ViewGroup", keyboardImageCandidate = true, visualKeyChildCount = 0)
            val x = i * 40 + 5 + keyOffset
            return ControlNode(i + 3 + keyNodeIdRevision, text = label, keyboardKey = label, clickable = true, textEntryKey = true,
                bounds = "$x 45 ${x + 35} 75", sourceWindowId = 20, sourcePackage = imePackage,
                visualKeyCandidate = true, visualKeyChildCount = 0)
        }
        fun currentEditor() = editor

        override val validatesAtNativeBoundary = true
        override fun session() = active
        override suspend fun apps(filter: String) = JsonArray(emptyList())
        override suspend fun captureKeyboardSequenceFrame(): ControlFrame {
            onNextSequenceCapture?.let { onNextSequenceCapture = null; it() }
            return capture()
        }
        override suspend fun capture(): ControlFrame {
            val scope = if (chrome) KeyboardVisualFingerprintParts("context", "${editor.text}/$alternativeCaption/$otherRegion",
                "$state/$layoutRevision", ChromeTreeRegions.Fingerprints(
                    "${editor.text}/${editor.selectionEnd}", "caption-$alternativeCaption", otherRegion,
                    if (suggestionMotion) "suggestion moved" else "suggestion shape")) else null
            val spaceNeighbors = if (chrome) listOf("," to "140 82 165 112", "." to "325 82 350 112").mapIndexed { i, (label, bounds) ->
                ControlNode(70 + i, text = label, keyboardKey = label, bounds = bounds, parent = 80,
                    sourceWindowId = 20, sourcePackage = imePackage, clickable = true, textEntryKey = true, visualKeyCandidate = true)
            } else emptyList()
            return ControlFrame(app, 10, "${editor.text}/$state/${editor.nativeEditorId}/$alternativeCaption/$otherRegion/$layoutRevision/${editor.selectionEnd}",
                listOf(editor) + controls + rows.getValue(state).map(::key) + spaceNeighbors,
                outputLimit = proposalOutputLimit,
                touchContext = touchContext, windowContext = "verified window",
                keyboard = ime, inputTargetId = editor.id, inputTarget = editor, keyboardVisualFingerprintParts = scope)
        }

        override suspend fun visualKeyboardSequenceFollowUp(frame: ControlFrame,
            onPostCheckRefusal: (JsonObject) -> Unit): CertifiedKeyboardFrame? {
            if (fifthSourceRefusalsSeen > 0 && editor.text == "가 ㄴ") {
                duringFifthRenewal?.invoke()
                if (invalidateFifthRenewal || transientFifthCaptureFailures > 0) {
                    if (transientFifthCaptureFailures > 0) transientFifthCaptureFailures--
                    failure = "display_window_changed_during_capture"
                    return null
                }
            }
            val proof = visualKeyboard(frame)
            if (editor.text != "가") return CertifiedKeyboardFrame(frame, proof)
            duringFirstSpaceCapture?.invoke()
            val after = capture()
            val refusal = GenericControlActionPolicy.refusal(frame, after, "visual_key")
            val certified = KeyboardVisualPostCheck.certifiedFrame(frame, after, refusal, allowCaptionOnly = chrome)
            if (certified == null) {
                onPostCheckRefusal(KeyboardVisualPostCheck.describe(frame, after))
                failure = "visual_post_$refusal"
                return null
            }
            afterFirstSpaceCapture?.invoke()
            return CertifiedKeyboardFrame(certified, proof)
        }
        private var failure = "visual_observation_unavailable"
        override fun visualFailure() = failure

        override suspend fun visualKeyboard(frame: ControlFrame): VisualTargetProof {
            captures++
            val regions = when {
                maskNextImage -> {
                    maskNextImage = false
                    listOf(TapBounds(0, 0, 240, 30))
                }
                fullyMaskedNativeVowel -> listOf(TapBounds(0, 0, 45, 150), TapBounds(80, 0, 1200, 150))
                partiallyMaskedNativeVowel -> listOf(TapBounds(0, 0, 70, 150), TapBounds(80, 0, 1200, 150))
                else -> listOf(frameBounds)
            }
            val proof = VisualTargetProof(ScreenImage(1200, 150, "image/jpeg", byteArrayOf(1),
                ScreenImageSource.ACCESSIBILITY_SCREENSHOT), "pixels", frameBounds, "context",
                System.currentTimeMillis() - initialProofAgeMs.also { initialProofAgeMs = 0L }, scope = "keyboard", sourceWindowId = 20, sourceImeWindowId = 20,
                sourceDisplayId = 0, displayGeneration = windowVersion, rotation = 0, imeBounds = "0 0 1200 150",
                disclosedRegions = regions)
            return if (reuseRejectedProofId) rejectedProofId?.let { proof.copy(id = it) } ?: proof else proof
        }

        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            nativeChecks++
            if (captionChangeAtNativeSpace && editor.text == "가" && node?.keyboardImageCandidate == true) {
                captionChangeAtNativeSpace = false
                alternativeCaption++
            }
            val fresh = capture()
            val refusal = KeyboardVisualPostCheck.nativeSequenceRefusal(frame, fresh, "visual_key", true)
            if (refusal != null) return ControlEffect(false, false, refusal)
            if (rejectFourthOnce && editor.text == "가 " && node?.keyboardKey == "ㄴ") {
                rejectFourthOnce = false
                return ControlEffect(false, false, "screen_changed")
            }
            // Refuse after the real full-frame policy passed, before this fake port dispatches.
            if (fifthSourceRefusalsSeen < fifthSourceRefusals && editor.text == "가 ㄴ" && node?.keyboardKey == "ㅏ") {
                fifthSourceRefusalsSeen++
                rejectedProofId = frame.visual?.id
                if (advanceWindowVersion) windowVersion++
                afterFifthSourceRefusal?.invoke()
                return ControlEffect(fifthSourceAttempted, false, VISUAL_SOURCE_REFUSED,
                    VisualSourceFailure(fifthSourceStage, fifthSourceCondition))
            }
            val label = node?.keyboardKey?.ifEmpty { input["image_key_label"]?.jsonPrimitive?.content.orEmpty() }
                ?: return ControlEffect(false, false, "no-key")
            if (label == fallbackRefusal) {
                fallbackRefusal = null
                return ControlEffect(false, false, "visual_pixels_or_geometry_changed")
            }
            touched.add(label)
            if (controls.any { it.keyboardKey == label }) {
                state = checkNotNull(nextState)
                nextState = null
            } else {
                val result = if (wrongEffectFor == label) editor.text
                    else if (label == "Space") editor.text + " " else HangulKeyComposer.append(editor.text, label)
                editor = editor.copy(text = result, empty = result.isEmpty(), selectionStart = result.length,
                    selectionEnd = result.length)
                if (label == changeTargetAfter) editor = editor.copy(nativeEditorId = "other-editor")
                if (state == "shift") state = "en"
            }
            return ControlEffect(true, label != loseCallbackFor, "gesture-completed")
        }

        private fun observedInput(observed: OperationResult) = buildJsonObject {
            put("observation_id", observed.data.getValue("observation_id"))
            put("visual_id", if (wrongInitialProofId) JsonPrimitive("wrong-image-id") else observed.data.getValue("visual").jsonObject.getValue("visual_id"))
            put("session_id", active.id)
            put("visual_target_confirmed", true)
        }

        suspend fun run(text: String, prefix: String? = null, learn: Boolean = true,
            witnesses: List<String>? = null): OperationResult {
            val labels = HangulKeyComposer.labels(text)
            // Invalid phase requests are still valid method calls with a fresh image.
            val observed = control.observe(includeImage = true, imageScope = "keyboard",
                keyboardSequenceLabels = labels.distinct())
            assertTrue(observed.summary, observed.success)
            return control.visualKeySequence(buildJsonObject {
                for ((key, value) in observedInput(observed)) put(key, value)
                put("reuse_key_map", true)
                put("text", text)
                witnesses?.let { put("layout_witness_labels", JsonArray(it.map(::JsonPrimitive))) }
                if (prefix != null) put("expected_prefix", prefix)
                if (learn) put("steps", buildJsonArray {
                    for (label in labels) {
                        val key = key(label)
                        add(buildJsonObject {
                            put("node_id", key.id)
                            put("label", label)
                            put("image_rect", JsonArray(key.bounds.split(' ').map { JsonPrimitive(it.toInt()) }))
                        })
                    }
                })
            })
        }

        suspend fun switch(to: String) {
            val label = when (to) {
                "en" -> if (state == "symbol" || state == "numeric") "ABC" else "한/영"
                "shift" -> "Shift"
                "numeric" -> "?123"
                "symbol" -> "#+="
                "ko" -> "한/영"
                else -> error("unexpected fixture state")
            }
            nextState = to
            val layoutButton = controls.single { it.keyboardKey == label }
            val observed = control.observe(includeImage = true, imageScope = "keyboard", keyboardKeyExact = label)
            val result = control.action(buildJsonObject {
                for ((key, value) in observedInput(observed)) put(key, value)
                put("action", "visual_key")
                put("node_id", layoutButton.id)
                put("image_rect", JsonArray(layoutButton.bounds.split(' ').map { JsonPrimitive(it.toInt()) }))
            })
            assertTrue(result.summary, result.success)
            assertEquals(to, state)
        }
    }

    @Test fun sixKoreanKeysFromOneProposalFrameStillUseNativeGuardForEachTouch() = runBlocking {
        val f = Fixture()
        val result = f.run("가 나 ")
        assertTrue(result.summary, result.success)
        assertEquals("가 나 ", f.currentEditor().text)
        assertEquals(4, f.currentEditor().selectionEnd)
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space"), f.touched)
        assertEquals(1, f.captures) // Excludes the native adapter's independent live pixel checks.
        assertEquals(6, f.nativeChecks)
        assertEquals(JsonPrimitive(5), result.data["reused_key_steps"])
    }

    @Test fun maskedNativeKeyRecoversWithoutRepeatingEarlierConfirmedKeys() = runBlocking {
        val f = Fixture().apply { partiallyMaskedNativeVowel = true }
        val result = f.run("가 나 ")
        assertTrue(result.summary, result.success)
        assertEquals("가 나 ", f.currentEditor().text)
        assertEquals(4, f.currentEditor().selectionEnd)
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space"), f.touched)
        assertEquals(JsonPrimitive(6), result.data["completed_keys"])
        assertEquals(JsonPrimitive(1), result.data["pre_touch_retries"])
        assertEquals(JsonPrimitive(1), result.data["partial_crop_recoveries"])
        assertEquals(JsonPrimitive(false), result.data["outcome_unknown"])
    }

    @Test fun maskedWholeNativeKeyStillBlocksBeforeAnyTouch() = runBlocking {
        val f = Fixture().apply { fullyMaskedNativeVowel = true }
        val result = f.run("가 나 ")
        assertFalse(result.success)
        assertEquals("keyboard_sequence_pixels_unverified", result.summary)
        assertEquals(JsonPrimitive(0), result.data["completed_keys"])
        assertEquals(JsonPrimitive(false), result.data["action_attempted"])
        assertEquals(JsonPrimitive(false), result.data["outcome_unknown"])
        assertTrue(f.touched.isEmpty())
    }

    @Test fun preTouchRefusalRenewsOnceThenTypesOnlyUnsentKeys() = runBlocking {
        val f = Fixture()
        f.fallbackRefusal = "ㅏ"
        val result = f.run("가 나 ")
        assertTrue(result.summary, result.success)
        assertEquals("가 나 ", f.currentEditor().text)
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space"), f.touched)
        assertEquals(7, f.nativeChecks)
        assertEquals(JsonPrimitive(true), result.data["fallback_used"])
        assertEquals(JsonPrimitive(5), result.data["keyboard_image_renewals"])
    }

    @Test fun captionOnlyWhileRenewingFirstSpaceDoesNotPublishTheObsoleteFrame() = runBlocking {
        val f = Fixture(chrome = true)
        f.fallbackRefusal = "Space"
        f.duringFirstSpaceCapture = { f.alternativeCaption++ }
        val result = f.run("가 나 ")
        assertTrue(result.summary, result.success)
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space"), f.touched)
        assertEquals("가 나 ", f.currentEditor().text)
        assertEquals(4, f.currentEditor().selectionEnd)
        assertEquals(JsonPrimitive(2), result.data["confirmed_image_fallback_spaces"])
    }

    @Test fun firstSpaceRenewalCannotCertifyCaretOrLayoutOrSuggestionGeometryChanges() = runBlocking {
        val mutations: Map<String, (Fixture) -> Unit> = mapOf(
            "caret" to { it.moveCaret() },
            "IME layout" to { it.layoutRevision += " changed" },
            "suggestion geometry" to { it.suggestionMotion = !it.suggestionMotion; it.alternativeCaption++ },
            "other Chrome region" to { it.otherRegion += " changed"; it.alternativeCaption++ },
        )
        for ((name, change) in mutations) {
            val f = Fixture(chrome = true).apply { fallbackRefusal = "Space" }
            f.duringFirstSpaceCapture = { change(f) }
            val result = f.run("가 나 ")
            assertFalse("$name: ${result.summary}", result.success)
            assertEquals(JsonPrimitive(2), result.data["completed_keys"])
            assertEquals(listOf("ㄱ", "ㅏ"), f.touched)
            assertEquals("가", f.currentEditor().text)
        }
    }

    @Test fun anotherChangeAfterCertificationCannotPassStrictNativeGuard() = runBlocking {
        val f = Fixture(chrome = true).apply { fallbackRefusal = "Space" }
        f.duringFirstSpaceCapture = { f.alternativeCaption++ }
        f.afterFirstSpaceCapture = { f.otherRegion += " changed" }
        val result = f.run("가 나 ")
        assertEquals("screen_changed", result.summary)
        assertEquals(JsonPrimitive(2), result.data["completed_keys"])
        assertEquals(JsonPrimitive(false), result.data["outcome_unknown"])
        assertEquals(listOf("ㄱ", "ㅏ"), f.touched)
    }

    @Test fun verifiedStateChangesCacheOnlyTheirOwnMapAndOneShotShiftRequiresNewObservation() = runBlocking {
        val f = Fixture()
        assertTrue(f.run("가 ").success)
        f.switch("en")
        assertTrue(f.run("ab", "가 ").success)
        f.switch("shift")
        assertTrue(f.run("A", "가 ab").success)
        assertEquals("en", f.state)
        assertEquals("keyboard_sequence_key_map_miss", f.run("A", "가 abA", learn = false).summary)
        assertEquals("keyboard_sequence_layout_witness_mismatch",
            f.run("b", "가 abA", learn = false, witnesses = listOf("A", "B")).summary)
        val lower = f.run("b", "가 abA", learn = false, witnesses = listOf("a", "b"))
        assertTrue(lower.summary, lower.success)
        assertEquals(JsonPrimitive(1), lower.data["key_map_hits"])
        f.switch("numeric")
        assertTrue(f.run("13", "가 abAb", witnesses = listOf("1", "3")).success)
        f.switch("symbol")
        assertTrue(f.run("!?", "가 abAb13", witnesses = listOf("!", "?")).success)
        f.switch("en")
        f.switch("ko")
        val korean = f.run("가 ", "가 abAb13!?", learn = false)
        assertTrue(korean.summary, korean.success)
        assertEquals(JsonPrimitive(3), korean.data["key_map_hits"])
        assertEquals("가 abAb13!?가 ", f.currentEditor().text)
    }

    @Test fun exactAsciiCallbackLossContinuesWithoutReplay() = runBlocking {
        val f = Fixture().apply { state = "en"; loseCallbackFor = "a" }
        val result = f.run("ab")
        assertTrue(result.summary, result.success)
        assertEquals(listOf("a", "b"), f.touched)
        assertEquals(JsonPrimitive(1), result.data["resolved_acknowledgements"])
        assertEquals(JsonPrimitive(2), result.data["completed_keys"])
    }

    @Test fun uncertainEffectOrEditorChangeStopsBeforeNextTouch() = runBlocking {
        val uncertain = Fixture().apply { state = "en"; loseCallbackFor = "a"; wrongEffectFor = "a" }
        val result = uncertain.run("ab")
        assertFalse(result.success)
        assertEquals(JsonPrimitive(true), result.data["outcome_unknown"])
        assertEquals(listOf("a"), uncertain.touched)
        val composing = Fixture().apply { loseCallbackFor = "ㄱ" }
        assertEquals(JsonPrimitive(true), composing.run("가 ").data["outcome_unknown"])
        assertEquals("ㄱ", composing.currentEditor().text)
        assertEquals(listOf("ㄱ"), composing.touched)
        assertEquals("keyboard_sequence_prefix_unverified", composing.run("가 ", "ㄱ").summary)
        val replaced = Fixture().apply { state = "en"; changeTargetAfter = "a" }
        assertFalse(replaced.run("ab").success)
        assertEquals(listOf("a"), replaced.touched)
    }

    @Test fun cachedMapCannotAuthorizeNewSessionOrRevokedPixels() = runBlocking {
        val f = Fixture().apply { state = "en" }
        assertTrue(f.run("a").success)
        f.maskNextImage = true
        assertFalse(f.run("a", "a", learn = false).success)
        f.active.pause()
        check(f.active.beginResume()); f.active.finishResume(f.active.epoch, true)
        assertEquals("keyboard_sequence_prefix_unverified", f.run("a", "a", learn = false).summary)
        assertEquals(listOf("a"), f.touched)
    }
    @Test fun fixedMixedBenchmarkThreeTimesPerSimulatedPath() = runBlocking {
        val phases = listOf(
            "ko" to "오늘 회의는 ", "shift" to "Z", "en" to "oom", "ko" to "으로 ",
            "numeric" to "3", "ko" to "시에 시작", "symbol" to "!", "ko" to " 자료는 ",
            "shift" to "A", "numeric" to "2", "ko" to "개", "symbol" to ".",
        )
        for (fallback in listOf(false, true)) repeat(3) {
            val f = Fixture()
            if (fallback) f.fallbackRefusal = "ㅗ"
            var fallbackCount = 0
            var prefix = ""
            suspend fun group(segment: String, witnesses: List<String>) {
                val r = f.run(segment, prefix.ifEmpty { null }, witnesses = witnesses)
                assertTrue(r.summary, r.success)
                if (r.data["fallback_used"] == JsonPrimitive(true)) fallbackCount++
                prefix += segment
            }
            for ((mode, segment) in phases) {
                if ((mode == "ko" && f.state in setOf("numeric", "symbol")) ||
                    (mode == "shift" && f.state == "ko")) f.switch("en")
                if (f.state != mode) f.switch(mode)
                val witnesses = when (mode) {
                    "ko" -> listOf("ㄱ", "ㅏ")
                    "en" -> listOf("a", "b")
                    "shift" -> listOf("A", "B")
                    "numeric" -> listOf("3", "5")
                    else -> listOf("!", "?")
                }
                var chunk = ""
                for (c in segment) {
                    if (HangulKeyComposer.labels(chunk + c).size > 6) {
                        group(chunk, witnesses)
                        chunk = ""
                    }
                    chunk += c
                }
                if (chunk.isNotEmpty()) group(chunk, witnesses)
            }
            assertEquals(benchmark, prefix)
            assertEquals(benchmark, f.currentEditor().text)
            assertEquals(benchmark.length, f.currentEditor().selectionEnd)
            assertEquals(if (fallback) 1 else 0, fallbackCount)
        }
    }

    @Test fun nativeGuardAllowsOnlyStationarySuggestionCaptionChange() = runBlocking {
        val f = Fixture(chrome = true).apply { captionChangeAtNativeSpace = true }
        val result = f.run("가 나 ")
        assertTrue(result.summary, result.success)
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space"), f.touched)
        assertEquals("가 나 ", f.currentEditor().text)
        assertEquals(4, f.currentEditor().selectionEnd)
        val before = f.capture()
        f.alternativeCaption++
        assertEquals("screen_changed", GenericControlActionPolicy.refusal(before, f.capture(), "visual_key"))
        assertNull(KeyboardVisualPostCheck.nativeSequenceRefusal(before, f.capture(), "visual_key", true))
        assertEquals("screen_changed", KeyboardVisualPostCheck.nativeSequenceRefusal(before, f.capture(), "visual_key", false))
        f.suggestionMotion = true
        assertEquals("screen_changed", KeyboardVisualPostCheck.nativeSequenceRefusal(before, f.capture(), "visual_key", true))
        f.suggestionMotion = false
        f.otherRegion = "new-overlay"
        assertEquals("screen_changed", KeyboardVisualPostCheck.nativeSequenceRefusal(before, f.capture(), "visual_key", true))
    }

    private fun pendingFifth(refusals: Int = 1) = Fixture(chrome = true).apply {
        fallbackRefusal = "Space"
        fifthSourceRefusals = refusals
        duringFirstSpaceCapture = { alternativeCaption++ }
    }

    @Test fun nativeWindowChangeRenewsOnlyUnsentFifthKeyAndPreservesPrefix() = runBlocking {
        val f = pendingFifth()
        val result = f.run("가 나 ")
        assertTrue(result.summary, result.success)
        assertEquals(JsonPrimitive(5), result.data["display_window_recheck_key"])
        assertEquals(1, f.fifthSourceRefusalsSeen)
        assertEquals(JsonPrimitive(1), result.data["display_window_rechecks"])
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space"), f.touched)
        assertEquals("가 나 ", f.currentEditor().text)
        assertEquals(4, f.currentEditor().selectionEnd)
    }

    @Test fun repeatedNativeWindowChangesRecertifyOnlyPendingKeyWithinSharedBudget() = runBlocking {
        // A prior Space fallback consumes one retry; two more confirmed no-touch
        // display refusals may use the remaining shared budget with fresh image proofs.
        val f = pendingFifth(refusals = 2)
        val result = f.run("가 나 ")
        assertTrue(result.summary, result.success)
        assertEquals(JsonPrimitive(6), result.data["completed_keys"])
        assertEquals(JsonPrimitive(3), result.data["pre_touch_retries"])
        assertEquals(JsonPrimitive(2), result.data["display_window_rechecks"])
        assertEquals(JsonPrimitive(5), result.data["display_window_recheck_key"])
        assertEquals(2, f.fifthSourceRefusalsSeen)
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space"), f.touched)
        assertEquals("가 나 ", f.currentEditor().text)
        assertEquals(4, f.currentEditor().selectionEnd)
    }

    @Test fun furtherNativeWindowChangeStopsAtRequestBudgetWithoutReplaying() = runBlocking {
        val f = pendingFifth(refusals = 3)
        val result = f.run("가 나 ")
        assertFalse(result.success)
        assertEquals(VISUAL_SOURCE_REFUSED, result.summary)
        assertEquals(JsonPrimitive(4), result.data["completed_keys"])
        assertEquals(JsonPrimitive(3), result.data["pre_touch_retries"])
        assertEquals(JsonPrimitive(2), result.data["display_window_rechecks"])
        assertEquals(JsonPrimitive(true), result.data["fallback_used"])
        assertEquals(JsonPrimitive(false), result.data["outcome_unknown"])
        assertEquals(JsonPrimitive(5), result.data["display_window_recheck_key"])
        assertEquals(3, f.fifthSourceRefusalsSeen)
        assertEquals("display_window_changed",
            result.data.getValue("visual_source_failure").jsonObject.getValue("condition").jsonPrimitive.content)
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ"), f.touched)
        assertEquals("가 ㄴ", f.currentEditor().text)
        assertEquals(3, f.currentEditor().selectionEnd)
    }

    @Test fun repeatedWindowRecertificationStillBlocksChangedEditorAndGesture() = runBlocking {
        for (change in listOf<Fixture.() -> Unit>(
            { replaceEditor() },
            { touchContext = "covered" },
        )) {
            val f = pendingFifth(refusals = 2)
            f.afterFifthSourceRefusal = { if (f.fifthSourceRefusalsSeen == 2) change(f) }
            val result = f.run("가 나 ")
            assertFalse(result.summary, result.success)
            assertEquals(JsonPrimitive(4), result.data["completed_keys"])
            assertEquals(JsonPrimitive(false), result.data["outcome_unknown"])
            assertEquals(2, f.fifthSourceRefusalsSeen)
            assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ"), f.touched)
        }
    }

    @Test fun renewedKeyRequiresNewImageIdAndNewerDisplayGeneration() = runBlocking {
        for (f in listOf(pendingFifth().apply { advanceWindowVersion = false },
            pendingFifth().apply { reuseRejectedProofId = true })) {
            val result = f.run("가 나 ")
            assertEquals("keyboard_sequence_window_recheck_stale", result.summary)
            assertEquals(JsonPrimitive(4), result.data["completed_keys"])
            assertEquals(JsonPrimitive(5), result.data["display_window_recheck_key"])
            assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ"), f.touched)
        }
    }

    @Test fun renewedKeyCannotCrossAnEditorCaretLayoutOrTouchRegionChange() = runBlocking {
        val changes: Map<String, Fixture.() -> Unit> = mapOf(
            "editor" to { replaceEditor() },
            "caret" to { moveCaret() },
            "layout" to { keyOffset = 3 },
            "touch region" to { touchContext = "covered" },
        )
        for ((name, change) in changes) {
            val f = pendingFifth().apply { afterFifthSourceRefusal = { change(this) } }
            val result = f.run("가 나 ")
            assertFalse("$name: ${result.summary}", result.success)
            assertEquals(JsonPrimitive(4), result.data["completed_keys"])
            assertEquals(JsonPrimitive(5), result.data["display_window_recheck_key"])
            assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ"), f.touched)
        }
    }

    @Test fun staleImageDoesNotBecomeASecondRetryDuringRenewal() = runBlocking {
        val f = pendingFifth().apply { invalidateFifthRenewal = true }
        val result = f.run("가 나 ")
        assertEquals("keyboard_sequence_new_image_unavailable", result.summary)
        assertEquals(JsonPrimitive("display_window_changed_during_capture"), result.data["observation_reason_code"])
        assertEquals(JsonPrimitive(5), result.data["display_window_recheck_key"])
        assertEquals(1, f.fifthSourceRefusalsSeen)
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ"), f.touched)
    }

    @Test fun unrelatedSourceReasonWrongStageAndAmbiguousTouchAreNotRenewed() = runBlocking {
        val cases = listOf(
            pendingFifth().apply { fifthSourceCondition = VisualSourceCondition.IMAGE_CONSENT_CHANGED },
            pendingFifth().apply { fifthSourceCondition = VisualSourceCondition.IMAGE_SOURCE_UNAVAILABLE },
            pendingFifth().apply { fifthSourceStage = VisualSourceStage.PRE_GESTURE },
            pendingFifth().apply { fifthSourceAttempted = true },
        )
        for (f in cases) {
            val result = f.run("가 나 ")
            assertEquals(VISUAL_SOURCE_REFUSED, result.summary)
            assertNull(result.data["display_window_recheck_key"])
            assertEquals(JsonPrimitive(f.fifthSourceAttempted), result.data["outcome_unknown"])
            assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ"), f.touched)
        }
    }

    @Test fun renewedKeyCannotUseNewConsentEpochOrEndedSession() = runBlocking {
        val f = pendingFifth().apply {
            duringFifthRenewal = { ai.closepaw.security.ImageRevocationAuthority.revokeConsent() }
        }
        val result = f.run("가 나 ")
        assertEquals("keyboard_sequence_window_recheck_consent_changed", result.summary)
        assertEquals(JsonPrimitive(5), result.data["display_window_recheck_key"])
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ"), f.touched)
        val ended = pendingFifth().apply { duringFifthRenewal = { active.pause() } }
        val stopped = ended.run("가 나 ")
        assertEquals("control_session_changed_observe_again", stopped.summary)
        assertEquals(JsonPrimitive(5), stopped.data["display_window_recheck_key"])
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ"), ended.touched)
    }

    @Test fun windowRecheckStillWorksAfterEarlierPreTouchRetryWithinBudget() = runBlocking {
        val f = pendingFifth().apply { rejectFourthOnce = true }
        val result = f.run("가 나 ")
        assertTrue(result.summary, result.success)
        assertEquals(JsonPrimitive(6), result.data["completed_keys"])
        assertEquals(JsonPrimitive(5), result.data["display_window_recheck_key"])
        assertEquals(JsonPrimitive(3), result.data["pre_touch_retries"])
        assertEquals(1, f.fifthSourceRefusalsSeen)
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space"), f.touched)
    }


    @Test fun transientCaptureWindowRaceRenewsEnvironmentAndResumesWithoutDuplicate() = runBlocking {
        val f = pendingFifth().apply { transientFifthCaptureFailures = 1 }
        val result = f.run("가 나 ")
        assertTrue(result.summary, result.success)
        assertEquals(JsonPrimitive(3), result.data["pre_touch_retries"])
        assertEquals(JsonPrimitive(6), result.data["completed_keys"])
        assertEquals(JsonPrimitive(false), result.data["outcome_unknown"])
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space"), f.touched)
        assertEquals("가 나 ", f.currentEditor().text)
        assertEquals(4, f.currentEditor().selectionEnd)
    }

    @Test fun expiredInitialImageIsRecertifiedBeforeFirstGesture() = runBlocking {
        val f = Fixture().apply { initialProofAgeMs = 12_000L }
        val result = f.run("가 나 ")
        assertTrue(result.summary, result.success)
        assertEquals(JsonPrimitive(1), result.data["pre_touch_retries"])
        assertEquals(6, f.touched.size)
        assertEquals("가 나 ", f.currentEditor().text)
    }

    @Test fun expiredImageRebindsOnlyEquivalentPhysicalKeyWhenNativeNodeIdsChange() = runBlocking {
        val f = Fixture().apply {
            initialProofAgeMs = 12_000L
            onNextSequenceCapture = { keyNodeIdRevision = 1000 }
        }
        val result = f.run("가 나 ")
        assertTrue(result.summary, result.success)
        assertEquals(JsonPrimitive(1), result.data["pre_touch_retries"])
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space"), f.touched)
        assertEquals("가 나 ", f.currentEditor().text)
        assertEquals(4, f.currentEditor().selectionEnd)

        // ID churn never authorizes a changed key rectangle.
        val changed = Fixture().apply {
            initialProofAgeMs = 12_000L
            onNextSequenceCapture = { keyNodeIdRevision = 1000; keyOffset = 5 }
        }
        val refused = changed.run("가 나 ")
        assertEquals("keyboard_sequence_environment_changed", refused.summary)
        assertEquals(JsonPrimitive(false), refused.data["action_attempted"])
        assertTrue(changed.touched.isEmpty())
    }

    @Test fun recertificationCannotElevateNodeHiddenByOriginalObservationLimit() = runBlocking {
        val f = Fixture().apply {
            proposalOutputLimit = 1
            initialProofAgeMs = 12_000L
            // A subsequent read may disclose many more keys; it cannot enlarge
            // the authority of the caller's original one-key observation.
            onNextSequenceCapture = {
                proposalOutputLimit = 50
                keyNodeIdRevision = 1000
            }
        }
        val refused = f.run("가 나 ")
        assertEquals("keyboard_sequence_candidate_undisclosed", refused.summary)
        assertEquals(JsonPrimitive(false), refused.data["action_attempted"])
        assertEquals(JsonPrimitive(0), refused.data["pre_touch_retries"])
        assertEquals(0, f.touched.size)
    }

    @Test fun initialRebuildCannotCrossEditorIdentityAndWrongImageIdCannotBeRebound() = runBlocking {
        val replaced = Fixture().apply {
            initialProofAgeMs = 12_000L
            onNextSequenceCapture = { replaceEditor() }
        }
        val stopped = replaced.run("가 나 ")
        assertFalse(stopped.success)
        assertTrue(replaced.touched.isEmpty())
        assertEquals(JsonPrimitive(false), stopped.data["action_attempted"])
        val wrong = Fixture().apply { wrongInitialProofId = true }
        val invalid = wrong.run("가 나 ")
        assertEquals("keyboard_visual_proof_unavailable", invalid.summary)
        assertTrue(wrong.touched.isEmpty())
        assertEquals(JsonPrimitive(false), invalid.data["action_attempted"])
    }

    @Test fun repeatedCaptureWindowRaceStopsAtSharedRecoveryBudgetWithoutReplay() = runBlocking {
        val f = pendingFifth().apply { transientFifthCaptureFailures = 5 }
        val stopped = f.run("가 나 ")
        assertFalse(stopped.success)
        assertEquals(JsonPrimitive(3), stopped.data["pre_touch_retries"])
        assertEquals(JsonPrimitive(4), stopped.data["completed_keys"])
        assertEquals(JsonPrimitive(false), stopped.data["outcome_unknown"])
        assertEquals(listOf("ㄱ", "ㅏ", "Space", "ㄴ"), f.touched)
    }

    @Test fun verifiedMultiChunkGoalSharesOneRecoveryBudgetAcrossSequences() = runBlocking {
        val f = Fixture()
        var prefix = ""
        for (segment in listOf("가 ", "나", "바")) {
            f.fallbackRefusal = "ㅏ"
            val result = f.run(segment, prefix.ifEmpty { null })
            assertTrue(result.summary, result.success)
            prefix += segment
            assertEquals(prefix, f.currentEditor().text)
            assertEquals(JsonPrimitive(1), result.data["pre_touch_retries"])
        }
        val deliveredBefore = f.touched.toList()
        val invalidRestart = f.run("가")
        assertEquals("keyboard_empty_editor_required", invalidRestart.summary)
        assertEquals(JsonPrimitive(3), invalidRestart.data["recovery_budget_total"])
        assertEquals(deliveredBefore, f.touched)
        f.fallbackRefusal = "ㅏ"
        val stopped = f.run("가", prefix)
        assertFalse(stopped.success)
        assertEquals(JsonPrimitive(3), stopped.data["recovery_budget_total"])
        assertEquals(JsonPrimitive(0), stopped.data["pre_touch_retries"])
        assertEquals(JsonPrimitive(false), stopped.data["outcome_unknown"])
        assertEquals(JsonPrimitive(1), stopped.data["completed_keys"])
        assertEquals(deliveredBefore + "ㄱ", f.touched)
        // A Korean final consonant composes into the preceding syllable ("바" + ㄱ = "박").
        assertEquals(HangulKeyComposer.append(prefix, "ㄱ"), f.currentEditor().text)
    }
}
