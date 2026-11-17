package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import org.junit.Assert.*
import org.junit.Test

class KeyboardImageFallbackPolicyTest {
    private val ime = ControlKeyboard(2, "com.samsung.android.honeyboard",
        "com.samsung.android.honeyboard/.service.HoneyBoardService")
    private val field = ControlNode(1, editable = true, focused = true, focusable = true, selectionStart = 0,
        selectionEnd = 0, sourceWindowId = 1, sourcePackage = "app")
    private val target = ControlNode(2, sourceWindowId = 2, sourcePackage = ime.packageName,
        clickable = true, textEntryKey = true, keyboardImageCandidate = true, visualKeyChildCount = 0,
        className = "android.widget.Button", parent = 20, bounds = "30 100 50 140")
    private fun label(id: Int, label: String, bounds: String) = target.copy(id = id, text = label,
        keyboardKey = label, bounds = bounds, keyboardImageCandidate = false)
    private val left = label(3, "f", "10 100 28 140")
    private val right = label(4, "h", "52 100 70 140")
    private val frame = ControlFrame("app", 1, "one", listOf(field, target, left, right),
        touchContext = "geometry", keyboard = ime, inputTarget = field, inputTargetId = field.id)

    @Test fun exactlyOneUnlabeledInteriorKeyWithTwoNativeLiteralAnchors() {
        assertNull(VisualKeyboardPolicy.refusal(frame, target, "keyboard", "g"))
        assertEquals("keyboard_image_requires_keyboard_scope", VisualKeyboardPolicy.refusal(frame, target, "display", "g"))
        assertNotNull(VisualKeyboardPolicy.refusal(frame.copy(nodes = frame.nodes - left), target, "keyboard", "g"))
        assertNotNull(VisualKeyboardPolicy.refusal(frame, target.copy(textEntryKey = false), "keyboard", "g"))
        assertNotNull(VisualKeyboardPolicy.refusal(frame, target, "keyboard", "f"))
        assertNotNull(VisualKeyboardPolicy.refusal(frame.copy(keyboard = ime.copy(inputMethodId = "other")), target, "keyboard", "g"))
        val duplicate = target.copy(id = 5)
        assertEquals("keyboard_image_candidate_ambiguous",
            VisualKeyboardPolicy.refusal(frame.copy(nodes = frame.nodes + duplicate), target, "keyboard", "g"))
        val overlap = label(6, "Enter", "35 105 45 130")
        assertEquals("keyboard_image_row_unverified",
            VisualKeyboardPolicy.refusal(frame.copy(nodes = frame.nodes + overlap), target, "keyboard", "g"))
    }

    @Test fun samsungRowsWithoutNativeTextEntryFlagsStillRequireOneExactLiteralSlot() {
        fun wrapper(n: ControlNode) = n.copy(className = "android.view.ViewGroup",
            clickable = false, textEntryKey = false, visualKeyChildCount = 1,
            visualKeyCandidate = !n.keyboardImageCandidate)
        val unknown = wrapper(target)
        val leftNative = wrapper(left)
        val rightNative = wrapper(right)
        val nativeFrame = frame.copy(nodes = listOf(field, unknown, leftNative, rightNative))
        assertEquals("g", KeyboardImageFallbackPolicy.slotLabel(nativeFrame, unknown))
        assertNull(VisualKeyboardPolicy.refusal(nativeFrame, unknown, "keyboard", "g"))
        assertNotNull(VisualKeyboardPolicy.refusal(nativeFrame, unknown, "keyboard", "G"))
        assertNotNull(VisualKeyboardPolicy.refusal(nativeFrame, unknown, "keyboard", "1"))
        assertNotNull(VisualKeyboardPolicy.refusal(nativeFrame.copy(nodes = nativeFrame.nodes - leftNative +
            leftNative.copy(keyboardKey = "d", text = "d")), unknown, "keyboard", "g"))
        assertNotNull(VisualKeyboardPolicy.refusal(nativeFrame.copy(nodes = nativeFrame.nodes - rightNative +
            rightNative.copy(parent = 28)), unknown, "keyboard", "g"))
        assertNotNull(VisualKeyboardPolicy.refusal(nativeFrame.copy(nodes = nativeFrame.nodes +
            leftNative.copy(id = 17)), unknown, "keyboard", "g"))
    }

    @Test fun localRecognitionIsExactAndRejectsSubmissionAndAmbiguousLabels() {
        for (text in listOf("g", "G", "4", "ㅎ", "é", "⌫", "한/영", "?")) assertTrue(KeyboardImageFallbackPolicy.matchesRead(text, text, 0.99f))
        for (text in listOf("enter", "↵", " ", "", "g!", "✓", "🌐"))
            assertFalse(KeyboardImageFallbackPolicy.matchesRead(text, text, 0.99f))
        assertFalse(KeyboardImageFallbackPolicy.matchesRead("g", "G", 0.99f))
        assertFalse(KeyboardImageFallbackPolicy.matchesRead("g", "g", 0.80f))
        assertFalse(KeyboardImageFallbackPolicy.matchesRead("g", "g", Float.NaN))
    }

    @Test fun wideBottomSpaceNeedsUniqueOwnedStructureAndCorroboratingImageCaption() {
        val space = target.copy(bounds = "50 165 140 205")
        val group = listOf(label(5, ",", "26 165 49 205"), label(6, ".", "141 165 165 205"),
            label(7, "ㅁ", "54 118 77 158"), label(8, "ㄴ", "79 118 102 158"),
            label(9, "ㅇ", "104 118 127 158"))
        val bottom = frame.copy(nodes = listOf(field, space) + group)
        assertTrue(KeyboardImageFallbackPolicy.canPropose(bottom, space))
        assertNull(VisualKeyboardPolicy.refusal(bottom, space, "keyboard", "Space"))
        assertTrue(KeyboardImageFallbackPolicy.matchesRead("Space", "English (US)", 0.96f))
        assertTrue(KeyboardImageFallbackPolicy.matchesRead("Space", "한국어", 0.96f))
        assertFalse(KeyboardImageFallbackPolicy.matchesRead("Space", "한/영", 0.96f))
        assertFalse(KeyboardImageFallbackPolicy.matchesRead("Space", "", 0.96f))
        assertNotNull(VisualKeyboardPolicy.refusal(bottom.copy(nodes = bottom.nodes - group.first()), space, "keyboard", "Space"))
        assertEquals("keyboard_image_candidate_ambiguous", VisualKeyboardPolicy.refusal(
            bottom.copy(nodes = bottom.nodes + space.copy(id = 10)), space, "keyboard", "Space"))
        assertNotNull(VisualKeyboardPolicy.refusal(bottom, space.copy(sourceWindowId = 8), "keyboard", "Space"))
        assertNotNull(VisualKeyboardPolicy.refusal(bottom, space, "keyboard", "Enter"))
        val nativeSpace = label(11, "Space", "21 165 44 205")
        assertEquals("keyboard_image_use_native_label", VisualKeyboardPolicy.refusal(
            bottom.copy(nodes = bottom.nodes + nativeSpace), space, "keyboard", "Space"))
    }

    @Test fun anotherSelectedImeUsesNativeTextKeyStructureWithoutQwerty() {
        val alternate = ControlKeyboard(6, "other.ime", "other.ime/.Keyboard")
        fun own(node: ControlNode) = node.copy(sourcePackage = alternate.packageName, sourceWindowId = alternate.windowId)
        val candidate = own(target)
        val observed = frame.copy(keyboard = alternate, nodes = listOf(field, candidate,
            own(left.copy(text = "ㅁ", keyboardKey = "ㅁ")), own(right.copy(text = "ㅂ", keyboardKey = "ㅂ"))))
        assertNull(VisualKeyboardPolicy.refusal(observed, candidate, "keyboard", "ㅎ"))
        assertNull(VisualKeyboardPolicy.refusal(observed, candidate, "keyboard", "%"))
        assertNotNull(VisualKeyboardPolicy.refusal(observed, candidate.copy(textEntryKey = false), "keyboard", "ㅎ"))
        assertNotNull(VisualKeyboardPolicy.refusal(observed.copy(nodes = observed.nodes + candidate.copy(id = 9)),
            candidate, "keyboard", "ㅎ"))
    }

    @Test fun labelFreeSpaceBoundaryIsProposedBeforePixelRoleVerification() {
        val space = target.copy(bounds = "50 165 140 205", clickable = false, textEntryKey = false,
            className = "android.view.View", visualKeyChildCount = 0)
        val blankNeighbor = space.copy(id = 10, bounds = "26 165 49 205")
        val characters = listOf(label(5, ".", "145 165 170 205"), label(6, "ㅁ", "54 118 77 158"),
            label(7, "ㄴ", "79 118 102 158"))
        val arranged = frame.copy(nodes = listOf(field, space, blankNeighbor) + characters)
        assertTrue(KeyboardImageFallbackPolicy.canPropose(arranged, space))
        assertNull(VisualKeyboardPolicy.refusal(arranged, space, "keyboard", "Space"))
        assertNotNull(VisualKeyboardPolicy.refusal(arranged, space, "keyboard", "검색"))
        assertFalse(KeyboardImageFallbackPolicy.canPropose(arranged.copy(nodes = arranged.nodes - blankNeighbor), space))
        assertFalse(KeyboardImageFallbackPolicy.canPropose(arranged.copy(nodes = arranged.nodes - characters.last()), space))
    }

    @Test fun cropRefusesAnyMaskedOrUncertifiedInteriorPixels() {
        val screen = TapBounds(100, 400, 200, 500)
        val proof = VisualTargetProof(
            ScreenImage(200, 500, "image/jpeg", byteArrayOf(1), ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "hash", TapBounds(0, 0, 400, 1000), "geometry", 2L, scope = "keyboard", sourceDisplayId = 0,
            disclosedRegions = listOf(screen))
        assertEquals(TapBounds(56, 206, 94, 244), KeyboardImageFallbackPolicy.crop(proof, screen))
        assertNotNull(KeyboardImageFallbackPolicy.crop(proof.copy(disclosedRegions = listOf(screen.copy(right = 199))), screen))
        assertNull(KeyboardImageFallbackPolicy.crop(proof.copy(disclosedRegions = listOf(screen.copy(right = 150))), screen))
        assertNull(KeyboardImageFallbackPolicy.crop(proof.copy(sourceDisplayId = -1), screen))
        assertNull(KeyboardImageFallbackPolicy.crop(proof.copy(scope = "display"), screen))
        assertNotNull(GenericControlActionPolicy.refusal(frame, frame.copy(touchContext = "stale"), "visual_key"))
    }
}
