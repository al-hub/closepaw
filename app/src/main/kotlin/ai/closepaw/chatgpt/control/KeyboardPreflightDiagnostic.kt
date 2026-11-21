package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Snapshot-only #276 explanation for a requested visual key sequence.
 *
 * Never an action permission: image_rect is chosen from a separately inspected
 * image and all fresh native guards still run immediately before any touch.
 */
internal object KeyboardPreflightDiagnostic {
    fun describe(frame: ControlFrame, requested: List<String>, selected: List<ControlNode>): JsonObject {
        val field = frame.inputTarget
        val source = when {
            field?.keyboardCursorGeneration != null -> "bound_empty_editor"
            field != null && field.selectionStart in 0..field.text.length &&
                field.selectionEnd in field.selectionStart..field.text.length -> "accessibility"
            else -> "unverified"
        }
        // The native bound copy may prove caret 0/0 while raw Accessibility
        // nodes still expose -1/-1. Never infer zero from those raw values.
        val zeroCaret = field != null && field.text.isEmpty() &&
            field.selectionStart == 0 && field.selectionEnd == 0
        val ime = frame.keyboard
        val uniqueLabels = requested.distinct()
        val reason = when {
            frame.reason != null -> "screen_observation_unavailable"
            frame.truncated || frame.navigationOnly -> "screen_scan_incomplete"
            frame.windowId != frame.foregroundWindowId -> "popup_window_selected"
            field == null || frame.inputTargetId != field.id ||
                field.sourceWindowId != frame.windowId || field.sourcePackage != frame.packageName ||
                !field.enabled || !field.focused || !field.focusable || !field.editable || field.password ->
                "keyboard_input_target_unverified"
            !zeroCaret -> "empty_caret_unproven"
            ime == null || !ime.readAllowed || !ime.interactAllowed -> "keyboard_interaction_not_authorized"
            frame.visual?.scope != "keyboard" ||
                VisualKeyboardPolicy.proofOwnerRefusal(frame.visual, ime) != null ->
                "keyboard_visual_unverified"
            selected.size != uniqueLabels.size ||
                selected.map { it.id }.distinct().size != selected.size -> "physical_keys_not_unique"
            else -> "image_rect_review_required"
        }
        return buildJsonObject {
            put("scope", "read_only_snapshot")
            put("status", if (reason == "image_rect_review_required") "REVIEW_READY" else "BLOCKED")
            put("reason_code", reason)
            put("cursor_source", source)
            put("effective_empty_caret_zero_verified", zeroCaret)
            put("raw_accessibility_selection_may_differ", field?.keyboardCursorGeneration != null)
            put("physical_candidate_count", selected.size)
            put("unique_requested_labels", uniqueLabels.size)
            put("image_rects_verified", false)
            put("action_attempted", false)
            put("touch_authorized", false)
        }
    }
}
