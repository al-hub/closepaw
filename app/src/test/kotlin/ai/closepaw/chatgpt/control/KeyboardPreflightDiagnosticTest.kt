package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** #276 snapshot diagnostics, not a gesture admission or live phone test. */
class KeyboardPreflightDiagnosticTest {
    private val raw = ControlNode(
        id = 137, text = "", empty = true,
        resourceId = "com.android.chrome:id/url_bar", className = "android.widget.EditText",
        sourcePackage = "com.android.chrome", sourceWindowId = 8464,
        focused = true, focusable = true, editable = true, enabled = true,
        selectionStart = -1, selectionEnd = -1
    )
    private val proven = raw.copy(selectionStart = 0, selectionEnd = 0, keyboardCursorGeneration = 7L)
    private val ime = ControlKeyboard(8411, "com.samsung.android.honeyboard",
        "com.samsung.android.honeyboard/.service.HoneyBoardService")
    private val proof = VisualTargetProof(
        ScreenImage(443, 960, "image/jpeg", byteArrayOf(1), ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
        "synthetic-only", TapBounds(0, 0, 1440, 3120), "test",
        System.currentTimeMillis(), scope = "keyboard", sourceWindowId = 8411,
        sourceDisplayId = 0, sourceImeWindowId = 8411, displayGeneration = 1,
        imeBounds = "0 1798 1440 3120", disclosedRegions = listOf(TapBounds(0, 1798, 1440, 3120))
    )
    private val key = ControlNode(273, "ㄱ", sourcePackage = "com.samsung.android.honeyboard",
        sourceWindowId = 8411, visualKeyCandidate = true, keyboardKey = "ㄱ")

    private fun frame(field: ControlNode? = proven) = ControlFrame(
        packageName = "com.android.chrome", windowId = 8464, foregroundWindowId = 8464,
        fingerprint = "fixture", nodes = listOf(raw, key), keyboard = ime,
        inputTarget = field, inputTargetId = field?.id, visual = proof
    )

    @Test fun nativeBoundEmptyCaretIsNotMistakenForRawMinusOne() {
        val diagnostic = KeyboardPreflightDiagnostic.describe(frame(), listOf("ㄱ"), listOf(key))
        assertEquals("REVIEW_READY", diagnostic.getValue("status").jsonPrimitive.content)
        assertEquals("image_rect_review_required", diagnostic.getValue("reason_code").jsonPrimitive.content)
        assertEquals("bound_empty_editor", diagnostic.getValue("cursor_source").jsonPrimitive.content)
        assertEquals("true", diagnostic.getValue("effective_empty_caret_zero_verified").toString())
        assertEquals("true", diagnostic.getValue("raw_accessibility_selection_may_differ").toString())
        assertEquals(-1, raw.selectionStart) // Published raw nodes do not overrule bound target proof.
        assertEquals("false", diagnostic.getValue("touch_authorized").toString())
    }

    @Test fun missingNativeCaretProofBlocksWithoutGuessingZero() {
        val result = KeyboardPreflightDiagnostic.describe(frame(raw), listOf("ㄱ"), listOf(key))
        assertEquals("BLOCKED", result.getValue("status").jsonPrimitive.content)
        assertEquals("empty_caret_unproven", result.getValue("reason_code").jsonPrimitive.content)
        assertEquals("unverified", result.getValue("cursor_source").jsonPrimitive.content)
        assertEquals("false", result.getValue("action_attempted").toString())
    }

    @Test fun newlySelectedChromePopupBlocksRegardlessOfOldCaretProof() {
        val overlay = frame().copy(windowId = 8492, foregroundWindowId = 8464)
        val d = KeyboardPreflightDiagnostic.describe(overlay, listOf("ㄱ"), listOf(key))
        assertEquals("popup_window_selected", d.getValue("reason_code").jsonPrimitive.content)
    }

    @Test fun missingImageAndMissingPhysicalKeysRemainSeparateReasons() {
        val noImage = KeyboardPreflightDiagnostic.describe(frame().copy(visual = null), listOf("ㄱ"), listOf(key))
        assertEquals("keyboard_visual_unverified", noImage.getValue("reason_code").jsonPrimitive.content)
        val noCandidate = KeyboardPreflightDiagnostic.describe(frame(), listOf("ㄱ"), emptyList())
        assertEquals("physical_keys_not_unique", noCandidate.getValue("reason_code").jsonPrimitive.content)
    }

    @Test fun populatedInputDoesNotPassEmptyFieldPreflight() {
        val changed = proven.copy(text = "가", selectionStart = 1, selectionEnd = 1, keyboardCursorGeneration = null)
        val result = KeyboardPreflightDiagnostic.describe(frame(changed), listOf("ㄱ"), listOf(key))
        assertEquals("empty_caret_unproven", result.getValue("reason_code").jsonPrimitive.content)
        assertEquals("BLOCKED", result.getValue("status").jsonPrimitive.content)
    }
}
