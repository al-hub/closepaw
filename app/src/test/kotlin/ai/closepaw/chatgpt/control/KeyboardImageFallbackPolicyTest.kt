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

    @Test fun localRecognitionIsExactAndRestrictedToLiteralCharactersAndDigits() {
        for (text in listOf("g", "G", "4")) assertTrue(KeyboardImageFallbackPolicy.matchesRead(text, text, 0.99f))
        for (text in listOf("enter", "↵", " ", "", "한/영", "⌫", "g!", "?"))
            assertFalse(KeyboardImageFallbackPolicy.matchesRead(text, text, 0.99f))
        assertFalse(KeyboardImageFallbackPolicy.matchesRead("g", "G", 0.99f))
        assertFalse(KeyboardImageFallbackPolicy.matchesRead("g", "g", 0.80f))
        assertFalse(KeyboardImageFallbackPolicy.matchesRead("g", "g", Float.NaN))
    }

    @Test fun cropRefusesAnyMaskedOrUncertifiedKeyPixels() {
        val screen = TapBounds(100, 400, 200, 500)
        val proof = VisualTargetProof(
            ScreenImage(200, 500, "image/jpeg", byteArrayOf(1), ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "hash", TapBounds(0, 0, 400, 1000), "geometry", 2L, scope = "keyboard", sourceDisplayId = 0,
            disclosedRegions = listOf(screen))
        assertEquals(TapBounds(56, 206, 94, 244), KeyboardImageFallbackPolicy.crop(proof, screen))
        assertNull(KeyboardImageFallbackPolicy.crop(proof.copy(disclosedRegions = listOf(screen.copy(right = 199))), screen))
        assertNull(KeyboardImageFallbackPolicy.crop(proof.copy(sourceDisplayId = -1), screen))
        assertNull(KeyboardImageFallbackPolicy.crop(proof.copy(scope = "display"), screen))
        assertNotNull(GenericControlActionPolicy.refusal(frame, frame.copy(touchContext = "stale"), "visual_key"))
    }
}
