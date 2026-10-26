package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*

/** A native acknowledgement is never evidence of a visible postcondition. No effect is replayed. */
internal object ControlResultPolicy {
    data class Verdict(val confirmed: Boolean, val reason: String, val goalVerified: Boolean = false)
    fun verify(action: String, before: ControlFrame, after: ControlFrame?, node: ControlNode?, input: JsonObject): Verdict {
        if (after == null || after.reason != null) return Verdict(false, "post_observation_unavailable")
        if (action == "open_app") return Verdict(after.matchesApplication(input["package_name"]?.jsonPrimitive?.content),
            "target_app_not_observed", true)
        if ((before.truncated || after.truncated) && action != "home") return Verdict(false, "post_observation_incomplete")
        if (action in setOf("key", "visual_key")) {
            if (node == null || before.keyboard == null || before.keyboard != after.keyboard ||
                before.packageName != after.packageName || before.windowId != after.windowId)
                return Verdict(false, "keyboard_postcondition_unavailable")
            val field = before.inputTarget ?: return Verdict(false, "keyboard_input_target_unverified")
            val key = (if (action == "visual_key") VisualKeyboardPolicy.identify(node.text, node.description)
                else KeyboardKeyPolicy.identify(node.text, node.description, node.className, node.clickable, node.textEntryKey))
                ?: return Verdict(false, "keyboard_key_unrecognized")
            val candidate = after.inputTarget
            val sameField = candidate != null && candidate.resourceId == field.resourceId && candidate.className == field.className &&
                candidate.bounds == field.bounds && candidate.sourceWindowId == field.sourceWindowId && candidate.sourcePackage == field.sourcePackage
            if (key.kind == KeyboardKeyPolicy.Kind.LAYOUT) {
                val beforeKeys = before.nodes.filter { it.sourceWindowId == before.keyboard.windowId &&
                    KeyboardKeyPolicy.identify(it.text,it.description,it.className,it.clickable,it.textEntryKey) != null }.map { it.keyboardKey }.filter { it.isNotEmpty() }.sorted()
                val afterKeys = after.nodes.filter { it.sourceWindowId == after.keyboard?.windowId &&
                    KeyboardKeyPolicy.identify(it.text,it.description,it.className,it.clickable,it.textEntryKey) != null }.map { it.keyboardKey }.filter { it.isNotEmpty() }.sorted()
                val layoutChanged = if (action == "visual_key") VisualKeyboardPolicy.literalLayoutChanged(before,after)
                    else beforeKeys != afterKeys && afterKeys.isNotEmpty()
                return Verdict(sameField && candidate?.focused == true && candidate.editable && candidate.enabled && !candidate.password && candidate.text == field.text &&
                    candidate.selectionStart == field.selectionStart && candidate.selectionEnd == field.selectionEnd &&
                    layoutChanged, "keyboard_layout_unconfirmed")
            }
            val expected = KeyboardKeyPolicy.expectedText(field, key)
            // Neither a gesture callback nor a layout/terminal screen change proves text/command success.
            if (expected != null) {
                // Exact text alone does not prove where the key left the caret.
                // Preserve the untouched suffix for insertion/replacement/deletion,
                // including composed Hangul and a deleted surrogate pair.
                val expectedCaret = expected.length - (field.text.length - field.selectionEnd)
                return Verdict(sameField && candidate?.text == expected && candidate.focused && candidate.editable &&
                    candidate.enabled && !candidate.password && candidate.selectionStart == expectedCaret &&
                    candidate.selectionEnd == expectedCaret, "keyboard_input_unconfirmed", true)
            }
            return Verdict(false, "keyboard_effect_requires_external_verification")
        }
        fun owned(candidate: ControlNode): Boolean = candidate.keyboardKey.isEmpty() &&
            (node == null || node.sourceWindowId < 0 || (candidate.sourceWindowId == node.sourceWindowId && candidate.sourcePackage == node.sourcePackage))
        val matching = after.nodes.filter { owned(it) && it.resourceId == node?.resourceId && it.bounds == node?.bounds &&
            it.className == node?.className }
        val target = matching.singleOrNull()
        // Post-effect geometry may legitimately change (IME, clear button, browser toolbar).
        // Only a unique nonempty semantic identity in BOTH complete frames can survive it.
        // Pre-dispatch freshness/geometry guards remain exact and are not changed here.
        val semantic = if (node != null && node.resourceId.isNotEmpty() && node.className.isNotEmpty() &&
            node.sourceWindowId >= 0 && node.sourcePackage.isNotEmpty() &&
            before.nodes.count { owned(it) && it.resourceId == node.resourceId && it.className == node.className } == 1)
            after.nodes.filter { owned(it) && it.resourceId == node.resourceId && it.className == node.className }.singleOrNull()
        else target
        if (action == "focus") return Verdict(after.packageName == before.packageName && after.windowId == before.windowId &&
            semantic?.focused == true, "focus_unconfirmed", true)
        if (action == "terminal_submit" && node != null) return Verdict(
            after.packageName == before.packageName && after.windowId == before.windowId && target?.focused == true &&
                node.terminal != null && TerminalInputPolicy.submitted(node.terminal,target.terminal,
                    input["text"]?.jsonPrimitive?.content.orEmpty(),input["expected_output"]?.jsonPrimitive?.content.orEmpty()),
            "terminal_execution_output_unconfirmed", true)
        if (action == "terminal_input" && node != null) return Verdict(
            after.packageName == before.packageName && after.windowId == before.windowId &&
                target?.focused == true && node.terminal != null && TerminalInputPolicy.inserted(node.terminal,
                    target.terminal, input["text"]?.jsonPrimitive?.content.orEmpty()), "terminal_echo_unconfirmed", true)
        if (action == "input" && node != null) {
            val plan = UnifiedInputPolicy.plan(node, input["text"]?.jsonPrimitive?.content.orEmpty(),
                input["method"]?.jsonPrimitive?.content ?: "auto", input["mode"]?.jsonPrimitive?.content ?: "empty",
                input["clipboard_consent"]?.jsonPrimitive?.booleanOrNull == true)
            return Verdict(plan.refusal == null && after.packageName == before.packageName && after.windowId == before.windowId &&
                semantic?.text == plan.resultText && semantic.focused && semantic.editable && semantic.enabled && !semantic.password,
                "input_unconfirmed", true)
        }
        if (action == "type") return Verdict(after.packageName == before.packageName && target?.text == input["text"]?.jsonPrimitive?.content, "input_unconfirmed", true)
        if (action in setOf("click", "tap") && node?.checkable == true) return Verdict(
            after.packageName == before.packageName && target?.checkable == true && target.checked != node.checked,
            "checked_state_unconfirmed", true)
        val changed = before.packageName != after.packageName || before.windowId != after.windowId || before.fingerprint != after.fingerprint
        return Verdict(changed, "no_visible_change")
    }
}

/** Bounded exact identities survive unrelated searches; native launch policy is always rechecked. */
internal class ControlAppSelections {
    private val packages = linkedSetOf<String>()
    fun add(values: List<String>) { packages.addAll(values); while (packages.size > 256) packages.remove(packages.first()) }
    fun clear() = packages.clear()
    fun refusal(pkg: String, launchAllowed: Boolean): String? = when {
        !launchAllowed -> "app_launch_blocked"
        pkg !in packages -> "app_selection_required"
        else -> null
    }
}
