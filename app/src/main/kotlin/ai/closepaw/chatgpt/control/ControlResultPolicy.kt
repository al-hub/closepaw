package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*

/** A native acknowledgement is never evidence of a visible postcondition. No effect is replayed. */
internal object ControlResultPolicy {
    data class Verdict(val confirmed: Boolean, val reason: String, val goalVerified: Boolean = false)
    fun verify(action: String, before: ControlFrame, after: ControlFrame?, node: ControlNode?, input: JsonObject): Verdict {
        if (action == "visual_key" && node != null &&
            VisualSearchPolicy.isSearch(VisualKeyboardPolicy.identifyCandidate(node, before.keyboard)))
            return Verdict(false, "keyboard_search_results_require_readonly_verification")
        if (after == null || after.reason != null) return Verdict(false, "post_observation_unavailable")
        if (action == "open_app") return Verdict(after.matchesApplication(input["package_name"]?.jsonPrimitive?.content),
            "target_app_not_observed", true)
        if ((before.truncated || after.truncated) && action != "home") return Verdict(false, "post_observation_incomplete")
        if (action in setOf("key", "visual_key")) {
            if (node == null || before.keyboard == null || before.keyboard != after.keyboard ||
                before.packageName != after.packageName || before.windowId != after.windowId)
                return Verdict(false, "keyboard_postcondition_unavailable")
            val field = before.inputTarget ?: return Verdict(false, "keyboard_input_target_unverified")
            val key = (if (action == "visual_key" && node.keyboardImageCandidate)
                KeyboardImageFallbackPolicy.key((input["image_key_label"] as? JsonPrimitive)?.contentOrNull)
                else if (action == "visual_key") VisualKeyboardPolicy.identifyCandidate(node, before.keyboard)
                else KeyboardKeyPolicy.identify(node.text, node.description, node.className, node.clickable, node.textEntryKey))
                ?: return Verdict(false, "keyboard_key_unrecognized")
            val candidate = after.inputTarget
            val sameField = KeyboardEditorContinuity.sameFocusedEditor(before, after,
                allowAnonymousResize = action == "visual_key" && key.kind == KeyboardKeyPolicy.Kind.CHARACTER &&
                    field.text.isEmpty() && field.selectionStart == 0 && field.selectionEnd == 0)
            if (key.kind == KeyboardKeyPolicy.Kind.LAYOUT) {
                val beforeKeys = before.nodes.filter { it.sourceWindowId == before.keyboard.windowId &&
                    KeyboardKeyPolicy.identify(it.text,it.description,it.className,it.clickable,it.textEntryKey) != null }.map { it.keyboardKey }.filter { it.isNotEmpty() }.sorted()
                val afterKeys = after.nodes.filter { it.sourceWindowId == after.keyboard?.windowId &&
                    KeyboardKeyPolicy.identify(it.text,it.description,it.className,it.clickable,it.textEntryKey) != null }.map { it.keyboardKey }.filter { it.isNotEmpty() }.sorted()
                val destination = if (key.label == "한/영" && KeyboardImageFallbackPolicy.trustedIme(before.keyboard, node.sourcePackage))
                    when (node.description) {
                        "영어(미국)로 변경" -> Character.UnicodeScript.LATIN
                        "한국어로 변경" -> Character.UnicodeScript.HANGUL
                        else -> null
                    } else null
                val layoutChanged = if (action == "visual_key")
                    VisualKeyboardPolicy.literalLayoutChanged(before, after, key.label in setOf("한/영", "한영"), destination)
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

/** Compares guarded, complete reads. Geometry or a matching prefix alone is
 * never evidence of editor continuity or of a committed edit. */
internal object KeyboardEditorContinuity {
    fun sameFocusedEditor(before: ControlFrame, after: ControlFrame, allowAnonymousResize: Boolean): Boolean {
        if (before.reason != null || after.reason != null || before.truncated || after.truncated ||
            before.navigationOnly || after.navigationOnly ||
            before.packageName != after.packageName || before.windowId != after.windowId ||
            before.foregroundWindowId != after.foregroundWindowId || before.windowContext != after.windowContext ||
            before.configurationRevision != after.configurationRevision || before.keyboard != after.keyboard) return false
        fun focused(frame: ControlFrame): ControlNode? =
            frame.nodes.singleOrNull { node ->
                node.sourcePackage == frame.packageName && node.sourceWindowId == frame.windowId &&
                    node.editable && node.focused && node.focusable && node.enabled && !node.password
            }?.let { node -> frame.inputTarget?.takeIf { candidate ->
                frame.inputTargetId == node.id && candidate.id == node.id &&
                    candidate.copy(selectionStart = node.selectionStart, selectionEnd = node.selectionEnd,
                        keyboardCursorGeneration = node.keyboardCursorGeneration) == node
            } }
        val old = focused(before) ?: return false
        val fresh = focused(after) ?: return false
        fun sameIdentity(node: ControlNode) = node.resourceId == old.resourceId && node.className == old.className &&
            node.sourceWindowId == old.sourceWindowId && node.sourcePackage == old.sourcePackage
        if (!sameIdentity(fresh)) return false
        if (old.nativeEditorId != null || fresh.nativeEditorId != null)
            return old.nativeEditorId == fresh.nativeEditorId
        if (old.resourceId.isNotEmpty()) {
            // When platform uniqueId (API 33+) is unavailable, reject simultaneous duplicates
            // and structural one-for-one replacements (changed parent, WebView ancestry, or
            // vertical/disjoint bounds). Single-edge horizontal resize (e.g. Chrome URL bar
            // showing/hiding the clear button on typing or Backspace) remains permitted.
            // Two sequential OS nodes with 100% identical accessible metadata remain inherently
            // indistinguishable without platform uniqueId.
            if (before.nodes.count(::sameIdentity) != 1 || after.nodes.count(::sameIdentity) != 1) return false
            if (old.webEditorContainer != fresh.webEditorContainer || old.parent != fresh.parent) return false
            if (fresh.bounds == old.bounds) return true
            val a = KeyboardImageFallbackPolicy.bounds(old.bounds) ?: return false
            val b = KeyboardImageFallbackPolicy.bounds(fresh.bounds) ?: return false
            return a.top == b.top && a.bottom == b.bottom && (a.left == b.left || a.right == b.right) &&
                2L * (minOf(a.right, b.right) - maxOf(a.left, b.left)) >= maxOf(a.right - a.left, b.right - b.left)
        }
        // Anonymous WebViews can expose no native unique ID. In that case
        // require the same owned WebView ancestry and tightly bounded geometry.
        if (old.webEditorContainer >= 0 || fresh.webEditorContainer >= 0) {
            if (old.webEditorContainer < 0 || old.webEditorContainer != fresh.webEditorContainer ||
                old.parent < 0 || old.parent != fresh.parent) return false
        }
        if (fresh.bounds == old.bounds) return old.parent == fresh.parent
        if (!allowAnonymousResize || old.webEditorContainer < 0) return false
        val a = KeyboardImageFallbackPolicy.bounds(old.bounds) ?: return false
        val b = KeyboardImageFallbackPolicy.bounds(fresh.bounds) ?: return false
        return a.top == b.top && a.bottom == b.bottom && (a.left == b.left || a.right == b.right) &&
            2L * (minOf(a.right, b.right) - maxOf(a.left, b.left)) >= maxOf(a.right - a.left, b.right - b.left)
    }

    fun sameValue(before: ControlFrame, after: ControlFrame): Boolean {
        val a = before.inputTarget ?: return false
        val b = after.inputTarget ?: return false
        // No reliable composition or suggestion metadata travels in this
        // snapshot. Keep the entire platform value, including any unknown suffix.
        return a.text == b.text && a.selectionStart in 0..a.text.length &&
            a.selectionEnd in a.selectionStart..a.text.length &&
            a.selectionStart == b.selectionStart && a.selectionEnd == b.selectionEnd
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
