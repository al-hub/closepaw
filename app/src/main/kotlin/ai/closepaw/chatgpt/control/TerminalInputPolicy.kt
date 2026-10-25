package ai.closepaw.chatgpt.control

/** A narrow adapter for a documented noneditable InputConnection host. A cursor
 * image or a generic canvas never establishes this contract. Entire echo stays
 * in operation memory; only capability metadata is exported. */
internal data class TerminalEvidence(val echo: String, val generation: Long, val fieldId: Int)
internal object TerminalInputPolicy {
    fun supported(pkg: String, resource: String, className: String) = pkg == "com.termux" &&
        resource == "com.termux:id/terminal_view" && className == "com.termux.view.TerminalView"
    fun echo(description: String): String? = description.takeIf { it.length in 1..8192 &&
        it.none { c -> c.isISOControl() && c != '\n' && c != '\r' } }?.replace("\r\n", "\n")?.trimEnd()
    fun emptyPrompt(echo: String): Boolean {
        val line = echo.substringAfterLast('\n')
        return line.length in 1..160 && line.endsWith('$') &&
            line.none { it.isISOControl() } && !InputPayloadPolicy.sensitive(line)
    }
    fun refusal(node: ControlNode, text: String, authorized: Boolean): String? {
        val proof = node.terminal ?: return "terminal_binding_unverified"
        if (!authorized) return "terminal_input_authorization_required"
        if (!node.enabled || !node.focused || node.password || !supported(node.sourcePackage, node.resourceId, node.className))
            return "terminal_target_unverified"
        if (!emptyPrompt(proof.echo)) return "empty_terminal_prompt_required"
        if (text.isEmpty() || text.length > 256 || text.any { it.isISOControl() || it == '\u2028' || it == '\u2029' } || text.last().isWhitespace())
            return "terminal_single_line_text_required"
        if (InputPayloadPolicy.sensitive(text)) return "sensitive_input_refused"
        return null
    }
    /** Termux getSelectedText trims row-ending spaces. No per-space success is
     * claimed: one whole commit is verified against the exact visible echo. */
    fun inserted(before: TerminalEvidence, after: TerminalEvidence?, text: String): Boolean = after != null &&
        before.generation == after.generation && before.fieldId == after.fieldId &&
        (after.echo == before.echo + " " + text || after.echo == before.echo + text)
}
