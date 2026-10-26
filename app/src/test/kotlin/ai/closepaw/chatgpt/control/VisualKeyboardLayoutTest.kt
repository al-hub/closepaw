package ai.closepaw.chatgpt.control

import ai.closepaw.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VisualKeyboardLayoutTest {
    private val field = ControlNode(1, text = "draft", resourceId = "app:id/input", className = "EditText",
        bounds = "0 0 100 50", sourceWindowId = 1, sourcePackage = "app", editable = true,
        enabled = true, focused = true, focusable = true, selectionStart = 2, selectionEnd = 4)
    private fun key(id: Int, label: String, left: Int) = ControlNode(id, text = label, keyboardKey = label,
        bounds = "$left 110 ${left + 20} 140", visualKeyCandidate = true, sourceWindowId = 2, sourcePackage = "ime")
    private val shift = key(2, "Shift", 0)
    private val frame = ControlFrame("app", 1, "before", listOf(field, shift, key(3, "a", 20), key(4, "b", 40)),
        keyboard = ControlKeyboard(2, "ime", "ime/.Keyboard"), inputTargetId = 1, inputTarget = field, touchContext = "geometry")

    @Test fun namedLayoutRequiresKnownSelectionAndUniqueObservedControl() {
        assertNotNull(VisualKeyboardPolicy.identify("Shift", ""))
        assertNotNull(VisualKeyboardPolicy.identify("한/영", ""))
        assertNotNull(VisualKeyboardPolicy.identify("?123", ""))
        for (label in listOf("🌐", "Enter", "Send", "Search", "suggestion"))
            assertNull(VisualKeyboardPolicy.identify(label, ""))
        assertNull(VisualKeyboardPolicy.refusal(frame, shift, "keyboard"))
        assertNotNull(VisualKeyboardPolicy.refusal(frame.copy(inputTarget = field.copy(selectionStart = -1)), shift, "keyboard"))
        assertNotNull(VisualKeyboardPolicy.refusal(frame.copy(nodes = frame.nodes + shift.copy(id = 5, bounds = "70 110 90 140")), shift, "keyboard"))
        // A parent and child corroborating one exact target are still one purpose/location.
        assertNull(VisualKeyboardPolicy.refusal(frame.copy(nodes = frame.nodes + shift.copy(id = 5)), shift, "keyboard"))
    }

    private inner class Port : ControlPort {
        val session = ControlSession().also { it.start() }
        var calls = 0
        var current = frame
        var changeKeys = true
        var changeField = false
        var changeSelection = false
        override fun session() = session
        override suspend fun capture() = current
        override suspend fun apps(filter: String) = JsonArray(emptyList())
        override suspend fun visualKeyboard(frame: ControlFrame) = VisualTargetProof(
            ScreenImage(100,100,"image/jpeg",byteArrayOf(1),ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "pixels",TapBounds(0,100,100,200),"geometry",System.currentTimeMillis(),scope="keyboard",sourceWindowId=2)
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            calls++
            val after = field.copy(text = if (changeField) "other" else field.text,
                selectionStart = if (changeSelection) 0 else field.selectionStart)
            val keys = if (changeKeys) listOf(key(3,"A",20),key(4,"B",40)) else frame.nodes.drop(2)
            current = frame.copy(fingerprint = "after", nodes = listOf(after, shift) + keys, inputTarget = after)
            return ControlEffect(true,true,"gesture_completed")
        }
    }
    private fun request(observed: ai.closepaw.chatgpt.operations.OperationResult) = buildJsonObject {
        put("action","visual_key"); put("node_id",2); put("observation_id",observed.data.getValue("observation_id"))
        put("visual_id",observed.data.getValue("visual").jsonObject.getValue("visual_id")); put("visual_target_confirmed",true)
        put("image_rect",JsonArray(listOf(0,10,20,40).map { JsonPrimitive(it) }))
    }
    @Test fun layoutEffectNeedsChangedCharacterKeysAndUnchangedFieldAndNeverReplays() = runBlocking {
        for (mode in listOf("valid", "unchanged_keys", "field_changed", "selection_changed")) {
            val port = Port().apply {
                changeKeys = mode != "unchanged_keys"; changeField = mode == "field_changed"; changeSelection = mode == "selection_changed"
            }
            val control = GenericControl(port,{true})
            val observed = control.observe(includeImage = true,imageScope = "keyboard")
            val input = request(observed); val result = control.action(input)
            assertEquals(mode, mode == "valid", result.success)
            assertEquals(JsonPrimitive(false), result.data["app_result_verified"])
            assertEquals(JsonPrimitive(mode != "valid"), result.data["outcome_unknown"])
            assertFalse(control.action(input).success); assertEquals(1,port.calls)
        }
    }
    @Test fun shiftLabelOrSuggestionChangesDoNotProveACharacterLayoutTransition() {
        val changed = frame.copy(nodes = frame.nodes.map { if (it.id == 2) it.copy(text="Caps Lock",keyboardKey="Caps Lock") else it } +
            key(5,"suggestion",70))
        assertFalse(VisualKeyboardPolicy.literalLayoutChanged(frame,changed))
        // One transient label is insufficient, even if it looks like a character.
        val one = frame.copy(nodes = frame.nodes.map { if (it.id == 3) it.copy(text="A",keyboardKey="A") else it })
        assertFalse(VisualKeyboardPolicy.literalLayoutChanged(frame,one))
    }
    @Test fun overlappingParentChildCharacterLabelsAreNotTwoPhysicalKeys() {
        val parent = key(3,"a",20)
        for (childBounds in listOf("25 115 35 135", "30 110 50 140")) {
            val child = parent.copy(id = 5, parent = 3, bounds = childBounds)
            val before = frame.copy(nodes = listOf(field, shift, parent, child))
            val after = before.copy(nodes = before.nodes.map {
                if (it.id in setOf(3,5)) it.copy(text="A",keyboardKey="A") else it
            })
            assertFalse(childBounds, VisualKeyboardPolicy.literalLayoutChanged(before,after))
            val disjointBefore = before.copy(nodes = before.nodes + key(4,"b",60))
            val disjointAfter = after.copy(nodes = after.nodes + key(4,"B",60))
            assertTrue(VisualKeyboardPolicy.literalLayoutChanged(disjointBefore,disjointAfter))
        }
    }
}
