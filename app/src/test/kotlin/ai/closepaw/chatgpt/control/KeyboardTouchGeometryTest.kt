package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class KeyboardTouchGeometryTest {
    private val ime = ControlKeyboard(2, "ime", "ime/.Keyboard")
    private val owner = KeyboardTouchGeometry.Owner("session", 1, 2, 3, "window", ime)
    private val transform = KeyboardTouchGeometry.Transform(TapBounds(10, 20, 210, 220), 1, 0, 100, 100)
    private val key = TapBounds(60, 120, 110, 160)
    private val entry = KeyboardTouchGeometry.Entry(key, TapBounds(25, 50, 50, 70),
        TapBounds(20, 100, 170, 200), emptyList(), "Backspace", transform)

    @Test fun offCenterPatchMustBeInteriorOwnedAndNotANeighbor() {
        val cache = KeyboardTouchGeometry()
        val left = TapBounds(70, 130, 79, 150)
        val result = cache.select(owner, "layout", entry, listOf(left))!!
        assertFalse(result.patch.contains(key.center.first, key.center.second))
        assertTrue(left.contains(result.patch))
        assertNull(cache.select(owner, "layout", entry.copy(neighbors = listOf(left)), listOf(left)))
        assertNull(cache.select(owner, "layout", entry, listOf(TapBounds(61, 130, 65, 145))))
        assertNull(cache.select(owner, "layout", entry, emptyList()))
        assertNull(cache.select(owner, "layout", entry.copy(imeBounds = TapBounds(20, 100, 100, 200)), listOf(left)))
    }

    @Test fun rotationOriginAndScaleAreBoundToDisplayAlignedImage() {
        assertEquals(key, transform.toScreen(entry.imageProposal))
        assertNull(transform.copy(rotation = -1).toScreen(entry.imageProposal))
        assertNull(transform.copy(display = 1).toScreen(entry.imageProposal))
        assertNull(transform.toScreen(TapBounds(25, 50, 101, 70)))
        val cache = KeyboardTouchGeometry()
        assertFalse(cache.select(owner, "layout", entry, listOf(key))!!.reused)
        assertTrue(cache.select(owner, "layout", entry, listOf(key))!!.reused)
        assertFalse(cache.select(owner, "layout", entry.copy(transform = transform.copy(rotation = 3)), listOf(key))!!.reused)
        val rescaled = entry.copy(transform = transform.copy(imageWidth = 200, imageHeight = 200),
            imageProposal = TapBounds(50, 100, 100, 140))
        assertFalse(cache.select(owner, "layout", rescaled, listOf(key))!!.reused)
        assertTrue(cache.select(owner, "layout", rescaled, listOf(key))!!.reused)
    }

    @Test fun roundTripReusesCalculationsOnlyForActiveArrayWithFreshCoverage() {
        val cache = KeyboardTouchGeometry()
        assertFalse(cache.select(owner, "한글", entry, listOf(key))!!.reused)
        assertTrue(cache.select(owner, "한글", entry, listOf(key))!!.reused)
        assertFalse(cache.select(owner, "Latin", entry, listOf(key))!!.reused)
        assertNull(cache.select(owner, "한글", entry, emptyList()))
        assertFalse(cache.select(owner, "한글", entry, listOf(key))!!.reused)
        assertTrue(cache.select(owner, "Latin", entry, listOf(key))!!.reused)
        assertTrue(cache.select(owner, "한글", entry, listOf(key))!!.reused)
        for (changed in listOf(owner.copy(epoch = 2), owner.copy(consentEpoch = 5),
            owner.copy(windowContext = "different"), owner.copy(configuration = 7))) {
            assertFalse(cache.select(changed, "한글", entry, listOf(key))!!.reused)
        }
        assertFalse(cache.select(owner, "", entry, listOf(key))!!.reused)
        assertFalse(cache.select(owner, "", entry, listOf(key))!!.reused)
    }

    @Test fun suggestionTextDoesNotChangeLayoutButActualCharactersDo() {
        fun native(id: Int, label: String, b: TapBounds) = ControlNode(id, text = label, keyboardKey = label,
            sourceWindowId = ime.windowId, sourcePackage = ime.packageName, visualKeyCandidate = true,
            bounds = "${b.left} ${b.top} ${b.right} ${b.bottom}")
        val keys = listOf(native(1, "a", TapBounds(30, 130, 45, 150)), native(2, "f", TapBounds(50, 130, 65, 150)),
            native(3, "z", TapBounds(70, 130, 90, 150)))
        val suggestion = native(4, "word", TapBounds(30, 105, 100, 120))
        fun digest(nodes: List<ControlNode>) = KeyboardTouchLayout.fingerprint(nodes, entry.imeBounds, ime)
        assertTrue(digest(keys).isNotEmpty())
        assertEquals(digest(keys), digest(keys + suggestion))
        assertNotEquals(digest(keys), digest(keys.drop(1) + keys.first().copy(text = "ㅁ", keyboardKey = "ㅁ")))
    }
}
