package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ImeGeometryReportTest {
    private val owner = ControlKeyboard(6, "com.samsung.android.honeyboard", "test")
    private val keys = (0..2).map { i ->
        val label = listOf("a", "b", "c")[i]
        ControlNode(30 + i, text = label, keyboardKey = label, bounds = "${10 + 20*i} 100 ${29 + 20*i} 130",
            parent = 2, className = "android.view.ViewGroup", sourceWindowId = owner.windowId,
            sourcePackage = owner.packageName, visualKeyCandidate = true, visualKeyChildCount = 1)
    }
    private val frame = ControlFrame("com.android.chrome", 3, "private-fingerprint", keys, keyboard = owner)
    private val mask = VisualTargetProof(ScreenImage(150, 180, "image/jpeg", byteArrayOf(1),
        ScreenImageSource.ACCESSIBILITY_SCREENSHOT), "image-digest", TapBounds(0,0,150,180), "context", 0,
        scope = "keyboard", sourceWindowId = owner.windowId, sourceImeWindowId = owner.windowId,
        sourceDisplayId = 0, displayGeneration = 1, imeBounds = "0 10 150 180",
        disclosedRegions = listOf(TapBounds(10,10,150,180)))

    private fun node(id: Int, parent: Int, bounds: TapBounds, kind: ImeGeometryNode.Kind = ImeGeometryNode.Kind.OTHER) =
        ImeGeometryNode(id, parent, bounds, kind, true)

    private fun tree(sharedRoot: Boolean = false, overlapping: Boolean = false) = buildList {
        add(node(0, -1, TapBounds(0,10,150,180)))
        add(node(1, 0, TapBounds(0,20,135,if (overlapping) 110 else 60)))
        add(node(2, 0, TapBounds(0,96,150,140)))
        keys.forEach { add(node(it.id, 2, checkNotNull(KeyboardImageFallbackPolicy.bounds(it.bounds)))) }
        for (i in 0..2) {
            val wrapper = 80 + 2*i
            val x = i*40
            add(node(wrapper, if (sharedRoot) 0 else 1, TapBounds(x,20,x+39,60), ImeGeometryNode.Kind.WRAPPER))
            add(node(wrapper + 1, wrapper, TapBounds(x,24,x+37,56), ImeGeometryNode.Kind.CAPTION))
        }
    }

    private fun JsonObject.field(key: String) = getValue(key).jsonPrimitive.content

    @Test fun reportsOnlyGeometryAndKeepsEdgeUncertified() {
        val r = ImeGeometryReport.collect(frame, tree(), mask)
        assertEquals("separate_caption_container_observed", r.field("structure"))
        assertEquals("true", r.field("physical_rows_fully_certified"))
        val first = r.getValue("paths").jsonArray[0].jsonArray[0].jsonPrimitive.content
        val geometry = r.getValue("path_geometry").jsonObject.getValue(first).jsonObject
        assertEquals("partial", geometry.field("certified_coverage"))
        assertEquals("true", geometry.field("inside_ime"))
        listOf("private-fingerprint", "image-digest", "keyboard_key", "visual_id", "node_id", "recommendation_text")
            .forEach { assertFalse(r.toString().contains(it)) }
    }

    @Test fun rootAndOverlapAreNotDedicatedStripEvidence() {
        assertEquals("common_ancestor_is_ime_root",
            ImeGeometryReport.collect(frame, tree(sharedRoot = true), mask).field("structure"))
        assertEquals("dedicated_strip_not_established",
            ImeGeometryReport.collect(frame, tree(overlapping = true), mask).field("structure"))
    }

    @Test fun wrongOwnerOrRevocationNeverCertifiesPixels() {
        for (p in listOf(mask.copy(sourceWindowId = 9), mask.copy(sourceDisclosureAllowed = { false }), null)) {
            val r = ImeGeometryReport.collect(frame, tree(), p)
            assertEquals("false", r.field("certified_pixels_available"))
            val coverage = r.getValue("path_geometry").jsonObject.values +
                r.getValue("physical_rows").jsonArray.toList()
            assertTrue(coverage.isNotEmpty())
            assertTrue(coverage.all { it.jsonObject.field("certified_coverage") == "unknown" })
        }
    }

    @Test fun incompleteAndDuplicateTreeDoNotReturnPaths() {
        val t = tree()
        for (r in listOf(ImeGeometryReport.collect(frame.copy(truncated = true), t, mask),
            ImeGeometryReport.collect(frame, t + t.first(), mask))) {
            assertEquals("owned_tree_unavailable", r.field("structure"))
            assertFalse("paths" in r)
        }
    }

    @Test fun localFixtureRequiresExactBlankChromeAndReadButNotInteraction() {
        val editor = ControlNode(3, sourceWindowId = frame.windowId, sourcePackage = frame.packageName,
            editable = true, focusable = true, focused = true, selectionStart = 0, selectionEnd = 0)
        val blank = frame.copy(keyboard = owner.copy(readAllowed = true, interactAllowed = false),
            inputTarget = editor, inputTargetId = editor.id)
        assertNull(LocalImeGeometryFixture.refusal(blank))
        for (bad in listOf(editor.copy(text = "UNEXPORTED"), editor.copy(selectionEnd = -1),
            editor.copy(password = true), editor.copy(sourceWindowId = 12))) {
            assertEquals("blank_editor_required", LocalImeGeometryFixture.refusal(blank.copy(inputTarget = bad)))
        }
        assertEquals("chrome_samsung_read_required",
            LocalImeGeometryFixture.refusal(blank.copy(keyboard = owner.copy(readAllowed = false))))
        assertEquals("complete_owned_scene_required", LocalImeGeometryFixture.refusal(blank.copy(truncated = true)))
    }
}
