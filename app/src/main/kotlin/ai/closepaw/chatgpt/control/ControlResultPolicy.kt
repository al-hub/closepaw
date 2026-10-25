package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*

/** A native acknowledgement is never evidence of a visible postcondition. No effect is replayed. */
internal object ControlResultPolicy {
    data class Verdict(val confirmed: Boolean, val reason: String, val goalVerified: Boolean = false)
    fun verify(action: String, before: ControlFrame, after: ControlFrame?, node: ControlNode?, input: JsonObject): Verdict {
        if (after == null || after.reason != null) return Verdict(false, "post_observation_unavailable")
        if (action == "open_app") return Verdict(after.matchesApplication(input["package_name"]?.jsonPrimitive?.content),
            "target_app_not_observed", true)
        if (after.truncated && action != "home") return Verdict(false, "post_observation_incomplete")
        if (action == "key") {
            if (node == null || before.keyboard == null || before.keyboard != after.keyboard ||
                before.packageName != after.packageName || before.windowId != after.windowId)
                return Verdict(false, "keyboard_postcondition_unavailable")
            val field = before.inputTarget ?: return Verdict(false, "keyboard_input_target_unverified")
            val key = KeyboardKeyPolicy.identify(node.text, node.description, node.className, node.clickable, node.textEntryKey)
                ?: return Verdict(false, "keyboard_key_unrecognized")
            val candidate = after.inputTarget
            val sameField = candidate != null && candidate.resourceId == field.resourceId && candidate.className == field.className &&
                candidate.bounds == field.bounds && candidate.sourceWindowId == field.sourceWindowId && candidate.sourcePackage == field.sourcePackage
            if (key.kind == KeyboardKeyPolicy.Kind.LAYOUT) {
                val beforeKeys = before.nodes.filter { it.sourceWindowId == before.keyboard.windowId }.map { it.keyboardKey }.filter { it.isNotEmpty() }.sorted()
                val afterKeys = after.nodes.filter { it.sourceWindowId == after.keyboard?.windowId }.map { it.keyboardKey }.filter { it.isNotEmpty() }.sorted()
                return Verdict(sameField && candidate?.focused == true && candidate.text == field.text &&
                    candidate.selectionStart == field.selectionStart && candidate.selectionEnd == field.selectionEnd &&
                    beforeKeys != afterKeys && afterKeys.isNotEmpty(), "keyboard_layout_unconfirmed")
            }
            val expected = KeyboardKeyPolicy.expectedText(field, key)
            // Neither a gesture callback nor a layout/terminal screen change proves text/command success.
            if (expected != null) return Verdict(sameField && candidate?.text == expected, "keyboard_input_unconfirmed", true)
            return Verdict(false, "keyboard_effect_requires_external_verification")
        }
        fun owned(candidate: ControlNode): Boolean = candidate.keyboardKey.isEmpty() &&
            (node == null || node.sourceWindowId < 0 || (candidate.sourceWindowId == node.sourceWindowId && candidate.sourcePackage == node.sourcePackage))
        val matching = after.nodes.filter { owned(it) && it.resourceId == node?.resourceId && it.bounds == node?.bounds &&
            it.className == node?.className }
        val target = matching.singleOrNull()
        if (action == "focus") {
            // IME opening may resize/move the field. Only a unique nonempty semantic identity
            // can survive that geometry change; never choose one of duplicate resource IDs.
            val uniqueBefore = node != null && node.resourceId.isNotEmpty() && before.nodes.count {
                owned(it) && it.resourceId == node.resourceId && it.className == node.className
            } == 1
            val semantic = if (uniqueBefore) after.nodes.filter {
                owned(it) && it.resourceId == node?.resourceId && it.className == node?.className
            }.singleOrNull() else target
            return Verdict(after.packageName == before.packageName && after.windowId == before.windowId &&
                semantic?.focused == true, "focus_unconfirmed", true)
        }
        if (action == "terminal_input" && node != null) return Verdict(
            after.packageName == before.packageName && after.windowId == before.windowId &&
                target?.focused == true && node.terminal != null && TerminalInputPolicy.inserted(node.terminal,
                    target.terminal, input["text"]?.jsonPrimitive?.content.orEmpty()), "terminal_echo_unconfirmed", true)
        if (action == "input" && node != null) {
            val plan = UnifiedInputPolicy.plan(node, input["text"]?.jsonPrimitive?.content.orEmpty(),
                input["method"]?.jsonPrimitive?.content ?: "auto", input["mode"]?.jsonPrimitive?.content ?: "empty",
                input["clipboard_consent"]?.jsonPrimitive?.booleanOrNull == true)
            return Verdict(plan.refusal == null && after.packageName == before.packageName && after.windowId == before.windowId &&
                target?.text == plan.resultText && target.focused && target.editable && !target.password,
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
