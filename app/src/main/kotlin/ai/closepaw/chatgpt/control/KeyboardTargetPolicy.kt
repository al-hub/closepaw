package ai.closepaw.chatgpt.control

/** Recheck the app's real FOCUS_INPUT node after IME scan and immediately before dispatch. */
internal object KeyboardTargetPolicy {
    fun refusal(expected: ControlNode, live: ControlNode?): String? = when {
        live == null || !live.enabled || !live.focused || !live.focusable || !live.editable || live.password -> "keyboard_input_target_changed"
        expected.sourceWindowId != live.sourceWindowId || expected.sourcePackage != live.sourcePackage ||
            expected.resourceId != live.resourceId || expected.className != live.className || expected.bounds != live.bounds ||
            expected.text != live.text || expected.selectionStart != live.selectionStart || expected.selectionEnd != live.selectionEnd -> "keyboard_input_target_changed"
        InputPayloadPolicy.sensitive(live.text) -> "sensitive_input_refused"
        else -> null
    }
}
