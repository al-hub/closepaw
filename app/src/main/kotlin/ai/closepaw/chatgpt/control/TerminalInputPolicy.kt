package ai.closepaw.chatgpt.control

/** A narrow adapter for a documented noneditable InputConnection host. A cursor
 * image or a generic canvas never establishes this contract. Entire echo stays
 * in operation memory; only capability metadata is exported. */
internal data class TerminalEvidence(val echo: String, val generation: Long, val fieldId: Int)
internal object TerminalInputPolicy {
    // Termux's documented Samsung char-based mode omits TYPE_CLASS_TEXT (0x1),
    // so this exact class-NULL value is not a standard password editor. Package,
    // view ID/class, native password flags and visible empty-prompt guards still apply.
    private fun hiddenFormat(text: String) = text.any { Character.getType(it) == Character.FORMAT.toInt() }
    fun inputTypeAllowed(type: Int) = type == 0 || type == 0x00080090
    fun supported(pkg: String, resource: String, className: String) = pkg == "com.termux" &&
        resource == "com.termux:id/terminal_view" && className in setOf("com.termux.view.TerminalView", "android.view.View")
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
        if (hiddenFormat(text) || text.isEmpty() || text.length > 256 || text.any { it.isISOControl() || it == '\u2028' || it == '\u2029' } || text.last().isWhitespace())
            return "terminal_single_line_text_required"
        if (InputPayloadPolicy.sensitive(text)) return "sensitive_input_refused"
        return null
    }
    fun submitRefusal(node: ControlNode, command: String, terminalAuthorized: Boolean, submitAuthorized: Boolean): String? {
        val proof = node.terminal ?: return "terminal_binding_unverified"
        if (!terminalAuthorized || !submitAuthorized) return "terminal_submission_authorization_required"
        if (!node.focused || !node.enabled || node.password || !supported(node.sourcePackage,node.resourceId,node.className)) return "terminal_target_unverified"
        if (hiddenFormat(command) || command.isEmpty() || command.length > 256 || command.any { it.isISOControl() } || InputPayloadPolicy.sensitive(command)) return "terminal_command_invalid"
        val line = proof.echo.substringAfterLast('\n')
        if (!line.endsWith(command) || !emptyPrompt(line.removeSuffix(command).trimEnd())) return "terminal_command_changed"
        return null
    }
    fun submitted(before: TerminalEvidence, after: TerminalEvidence?, command: String, expectedOutput: String): Boolean {
        if (after == null || before.generation != after.generation || before.fieldId != after.fieldId ||
            hiddenFormat(expectedOutput) || expectedOutput.isEmpty() || expectedOutput.length > 256 || expectedOutput.any { it.isISOControl() } || InputPayloadPolicy.sensitive(expectedOutput)) return false
        if (!after.echo.startsWith(before.echo + "\n") || expectedOutput in before.echo.lines()) return false
        val tail = after.echo.removePrefix(before.echo+"\n").lines()
        val prompt = before.echo.substringAfterLast('\n').removeSuffix(command).trimEnd()
        return expectedOutput in tail && tail.lastOrNull() == prompt
    }
    /** Termux getSelectedText trims row-ending spaces. No per-space success is
     * claimed: one whole commit is verified against the exact visible echo. */
    fun inserted(before: TerminalEvidence, after: TerminalEvidence?, text: String): Boolean = after != null &&
        before.generation == after.generation && before.fieldId == after.fieldId &&
        (after.echo == before.echo + " " + text || after.echo == before.echo + text)
}
