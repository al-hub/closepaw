package ai.closepaw.chatgpt.control

/** Pure selection only. Native rejection/timeout is never an instruction to try another method. */
internal object UnifiedInputPolicy {
    data class Plan(val method: String = "", val resultText: String = "", val refusal: String? = null)
    fun plan(node: ControlNode, text: String, method: String, mode: String, clipboardConsent: Boolean): Plan {
        fun refuse(reason: String) = Plan(refusal = reason)
        if (method !in setOf("auto", "direct", "clipboard", "keyboard")) return refuse("input_method_invalid")
        if (mode !in setOf("empty", "insert")) return refuse("input_mode_invalid")
        if (!node.enabled || node.password || !node.editable || !node.focused) return refuse("focused_editable_required")
        // Text population cannot implicitly submit commands/messages or inject control characters.
        if (text.isEmpty() || text.length > 2000 || text.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return refuse("single_line_text_required")
        if (InputPayloadPolicy.sensitive(text)) return refuse("sensitive_input_refused")
        if (mode == "empty" && !node.empty) return refuse("existing_text_requires_insert")
        if (node.text.length > 2000) return refuse("existing_text_incomplete")
        val start = if (mode == "empty") 0 else node.selectionStart
        val end = if (mode == "empty") 0 else node.selectionEnd
        if (start !in 0..node.text.length || end !in start..node.text.length) return refuse("selection_unverified")
        fun splitsSurrogate(at: Int) = at > 0 && at < node.text.length && node.text[at - 1].isHighSurrogate() && node.text[at].isLowSurrogate()
        if (splitsSurrogate(start) || splitsSurrogate(end)) return refuse("selection_unverified")
        val result = node.text.substring(0, start) + text + node.text.substring(end)
        if (InputPayloadPolicy.sensitive(node.text) || InputPayloadPolicy.sensitive(result)) return refuse("sensitive_input_refused")
        if (result.length > 2000) return refuse("result_text_too_long")
        val selected = when {
            method == "direct" -> if (node.setText) "direct" else return refuse("direct_input_unsupported")
            method == "keyboard" -> "keyboard"
            method == "clipboard" -> if (node.paste) "clipboard" else return refuse("paste_unsupported")
            node.setText -> "direct"
            node.paste && clipboardConsent -> "clipboard"
            else -> "keyboard"
        }
        if (selected == "clipboard" && !clipboardConsent) return refuse("clipboard_consent_required")
        return Plan(selected, result)
    }
}
