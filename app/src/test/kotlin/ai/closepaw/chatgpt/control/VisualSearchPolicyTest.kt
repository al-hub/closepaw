package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VisualSearchPolicyTest {
    private val app = "com.android.chrome"
    private val query = "sample 전망"
    private val ime = ControlKeyboard(2, "some.ime", "some.ime/.Ime")
    private val editor = ControlNode(1, text = query, editable = true, focused = true, focusable = true,
        selectionStart = query.length, selectionEnd = query.length, sourceWindowId = 1,
        sourcePackage = app, webEditorContainer = 14)
    private val bar = ControlNode(2, text = "www.google.com/", resourceId = "$app:id/url_bar",
        sourceWindowId = 1, sourcePackage = app)
    private val key = ControlNode(3, description = "검색", keyboardKey = "검색", className = "android.view.ViewGroup",
        bounds = "80 160 100 200", visualKeyChildCount = 1, visualKeyCandidate = true,
        sourceWindowId = 2, sourcePackage = ime.packageName)
    private val letterKeys = listOf(
        key.copy(id = 10, text = "x", description = "", keyboardKey = "x", bounds = "30 115 50 155"),
        key.copy(id = 11, text = "ㅊ", description = "", keyboardKey = "ㅊ", bounds = "52 115 73 155"),
    )
    private val frame = ControlFrame(app, 1, "before", listOf(editor, bar, key) + letterKeys, touchContext = "geometry",
        keyboard = ime, inputTarget = editor, inputTargetId = editor.id)
    private fun approval(value: String = query) = buildJsonObject {
        put("submit_authorized", true); put("expected_search_query", value)
    }

    @Test fun needsSearchLabelGoogleOriginUniqueWebEditorExactValueAndConsent() {
        assertNull(VisualKeyboardPolicy.refusal(frame, key, "keyboard", input = approval()))
        assertEquals("submission_authorization_required", VisualKeyboardPolicy.refusal(frame, key, "keyboard"))
        assertNotNull(VisualKeyboardPolicy.refusal(frame, key, "keyboard", input = approval("sample전망")))
        assertNotNull(VisualKeyboardPolicy.refusal(frame, key, "display", input = approval()))
        for (other in listOf("Enter", "Done", "Send", "⌫", "↵", "go", "🔍"))
            assertNull(other, VisualSearchPolicy.role("", other, key.className, 1))
        for (origin in listOf("http://www.google.com/", "https://www.google.com.evil.example/", "www.google.com/search?q=old",
            "https://fake@www.google.com/")) {
            val replaced = frame.copy(nodes = listOf(editor, bar.copy(text = origin), key) + letterKeys)
            assertNotNull(origin, VisualKeyboardPolicy.refusal(replaced, key, "keyboard", input = approval()))
        }
        assertNotNull(VisualKeyboardPolicy.refusal(frame.copy(inputTarget = editor.copy(webEditorContainer = -1)), key,
            "keyboard", input = approval()))
        assertNotNull(VisualKeyboardPolicy.refusal(frame.copy(nodes = frame.nodes + editor.copy(id = 4)), key,
            "keyboard", input = approval()))
        assertNotNull(VisualKeyboardPolicy.refusal(frame.copy(inputTarget = editor.copy(selectionStart = 0)), key,
            "keyboard", input = approval()))
        val toolbar = key.copy(bounds = "82 65 100 105")
        assertEquals("keyboard_search_action_row_unverified",
            VisualKeyboardPolicy.refusal(frame.copy(nodes = frame.nodes - key + toolbar), toolbar, "keyboard", input = approval()))
        assertEquals("keyboard_search_ambiguous", VisualKeyboardPolicy.refusal(frame.copy(nodes = frame.nodes +
            key.copy(id = 4, bounds = "70 150 90 200")), key, "keyboard", input = approval()))
    }

    @Test fun exactGoogleFocusedFormMarkerDoesNotRelaxTheSearchGate() {
        fun withAddress(address: String) = frame.copy(nodes = listOf(editor, bar.copy(text = address), key) + letterKeys)
        val focused = withAddress("www.google.com/#sbfbu=1&pi=")
        assertNull(VisualKeyboardPolicy.refusal(focused, key, "keyboard", input = approval()))
        assertEquals("submission_authorization_required", VisualKeyboardPolicy.refusal(focused, key, "keyboard"))
        assertEquals("keyboard_search_query_unverified",
            VisualKeyboardPolicy.refusal(focused, key, "keyboard", input = approval("sample전망")))
        for (address in listOf("www.google.com/#unknown", "www.google.com/#sbfbu=1&pi=other",
            "www.google.com/#sbfbu=1&pi=&next=", "www.google.com/#sbfbu=1&%70i=",
            "www.google.com/search#sbfbu=1&pi=", "www.google.com.example/#sbfbu=1&pi=",
            "http://www.google.com/#sbfbu=1&pi=")) {
            assertEquals(address, "keyboard_search_target_unverified",
                VisualKeyboardPolicy.refusal(withAddress(address), key, "keyboard", input = approval()))
        }
    }

    @Test fun partialPixelsNeedOneSafeInteriorPatchAndNeverOverlapOtherKeys() {
        val key = TapBounds(80, 160, 100, 200)
        val viewport = TapBounds(0, 100, 100, 200)
        val owner = KeyboardTouchGeometry.Owner("explicit", 1, 1, 1, "win", ime)
        val entry = KeyboardTouchGeometry.Entry(key, TapBounds(84, 70, 94, 90), viewport, emptyList(), "검색",
            KeyboardTouchGeometry.Transform(viewport, 0, 0, 100, 100))
        val visible = listOf(TapBounds(84, 175, 94, 185))
        val calculator = KeyboardTouchGeometry()
        val patch = requireNotNull(calculator.select(owner, "layout", entry, visible)).patch
        assertTrue(key.contains(patch))
        assertNull(calculator.select(owner, "layout", entry, listOf(TapBounds(95, 175, 100, 185))))
        assertNull(calculator.select(owner, "layout", entry.copy(neighbors = visible), visible))
        assertNull(calculator.select(owner, "layout", entry.copy(key = TapBounds(80, 160, 102, 200)), visible))
    }

    @Test fun nativeRefusalMayRetryButAnUnknownAttemptCannotReplayWithNewObservation() = runBlocking {
        val session = ControlSession().also { it.start() }
        var attempts = 0
        val port = object : ControlPort {
            override fun session() = session
            override suspend fun capture() = frame
            override suspend fun apps(filter: String) = JsonArray(emptyList())
            override suspend fun visualKeyboard(frame: ControlFrame) = VisualTargetProof(
                ScreenImage(100,100,"image/jpeg",byteArrayOf(1),ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
                "pixels",TapBounds(0,100,100,200),"geometry",System.currentTimeMillis(),scope="keyboard",sourceWindowId=2)
            override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect =
                if (attempts++ == 0) ControlEffect(false, false, "visual_pixels_or_geometry_changed")
                else ControlEffect(true, true, "gesture_completed")
        }
        var control = GenericControl(port, { true })
        suspend fun request(): JsonObject {
            val seen = control.observe(includeImage = true, imageScope = "keyboard", keyboardKeyExact = "검색")
            return buildJsonObject {
                approval().forEach { (k, v) -> put(k, v) }
                put("session_id", session.id); put("action", "visual_key"); put("node_id", key.id)
                put("observation_id", seen.data.getValue("observation_id")); put("visual_target_confirmed", true)
                put("visual_id", seen.data.getValue("visual").jsonObject.getValue("visual_id"))
                put("image_rect", JsonArray(listOf(85,70,94,90).map { JsonPrimitive(it) }))
            }
        }
        val stale = request()
        request()
        assertEquals("stale_observation", control.action(stale).summary)
        assertEquals(0, attempts)
        assertEquals(JsonPrimitive(false), control.action(request()).data["action_attempted"])
        val pending = control.action(request())
        assertFalse(pending.success)
        assertEquals("keyboard_search_results_require_readonly_verification", pending.summary)
        assertEquals(JsonPrimitive(true), pending.data["outcome_unknown"])
        assertEquals(JsonPrimitive(false), pending.data["app_result_verified"])
        control.invalidate()
        // A replacement MCP executor attaches to the same accessibility session.
        control = GenericControl(port, { true })
        assertEquals("keyboard_search_replay_refused", control.action(request()).summary)
        assertEquals(2, attempts)
        session.end()
        assertTrue(session.start())
        assertEquals(JsonPrimitive(true), control.action(request()).data["action_attempted"])
        assertEquals(3, attempts)
    }
}
