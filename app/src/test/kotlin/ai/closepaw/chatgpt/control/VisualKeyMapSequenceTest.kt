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

    private class Fixture : ControlPort {
        val control = GenericControl(this, { allowed })
        val active = ControlSession().apply { start() }
        val ime = ControlKeyboard(20, "test.ime", "test.ime/.Ime")
        private var editor = ControlNode(1, resourceId = "test.app:id/isolated_editor", className = "EditText",
            editable = true, focused = true, focusable = true, selectionStart = 0, selectionEnd = 0,
            sourceWindowId = 10, sourcePackage = "test.app", nativeEditorId = "editor-1")
        var allowed = true
        var fallbackRefusal: String? = null
        var loseCallbackFor: String? = null
        var wrongEffectFor: String? = null
        var changeTargetAfter: String? = null
        var maskNextImage = false
        val touched = mutableListOf<String>()
        var captures = 0
        var nativeChecks = 0
        private val frameBounds = TapBounds(0, 0, 1200, 150)
        private val controls = listOf("Shift", "한/영", "?123", "#+=", "ABC").mapIndexed { index, label ->
            ControlNode(90 + index, text = label, keyboardKey = label, clickable = true,
                bounds = "${index * 40} 85 ${(index + 1) * 40} 110",
                sourceWindowId = 20, sourcePackage = "test.ime", visualKeyCandidate = true)
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
            val x = i * 40 + 5
            return ControlNode(i + 3, text = label, keyboardKey = label, clickable = true, textEntryKey = true,
                bounds = "$x 45 ${x + 35} 75", sourceWindowId = 20, sourcePackage = "test.ime",
                visualKeyCandidate = true, visualKeyChildCount = 0)
        }
        fun currentEditor() = editor

        override val validatesAtNativeBoundary = true
        override fun session() = active
        override suspend fun apps(filter: String) = JsonArray(emptyList())
        override suspend fun capture(): ControlFrame {
            return ControlFrame("test.app", 10, "${editor.text}/$state/${editor.nativeEditorId}",
                listOf(editor) + controls + rows.getValue(state).map(::key),
                touchContext = "verified touch region", windowContext = "verified window",
                keyboard = ime, inputTargetId = editor.id, inputTarget = editor)
        }

        override suspend fun visualKeyboard(frame: ControlFrame): VisualTargetProof {
            captures++
            val regions = if (maskNextImage) {
                maskNextImage = false
                listOf(TapBounds(0, 0, 240, 30))
            } else listOf(frameBounds)
            return VisualTargetProof(ScreenImage(1200, 150, "image/jpeg", byteArrayOf(1),
                ScreenImageSource.ACCESSIBILITY_SCREENSHOT), "pixels", frameBounds, "context",
                System.currentTimeMillis(), scope = "keyboard", sourceWindowId = 20, sourceImeWindowId = 20,
                sourceDisplayId = 0, displayGeneration = 1, rotation = 0, imeBounds = "0 0 1200 150",
                disclosedRegions = regions)
        }

        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            nativeChecks++
            val fresh = capture()
            val refusal = GenericControlActionPolicy.refusal(frame, fresh, "visual_key")
            if (refusal != null) return ControlEffect(false, false, refusal)
            val label = node?.keyboardKey ?: return ControlEffect(false, false, "no-key")
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
            put("visual_id", observed.data.getValue("visual").jsonObject.getValue("visual_id"))
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

}
