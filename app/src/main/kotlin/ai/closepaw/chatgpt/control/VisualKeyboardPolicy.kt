package ai.closepaw.chatgpt.control

/** A native label is corroborating evidence, never a substitute for reading the
 * separate OS-selected IME image. Named layout controls require a preserved
 * focused field and changed literal keys. Submission and IME picker controls
 * are excluded. This does not relax semantic-key admission. */
internal object VisualKeyboardPolicy {
    /** Reading a verified IME surface does not grant permission to press keys. */
    fun imageReadRefusal(keyboard: ControlKeyboard?, runtimeReadAllowed: Boolean, requiresTarget: Boolean, targetPresent: Boolean): String? =
        if (keyboard == null || !keyboard.readAllowed || !runtimeReadAllowed || (requiresTarget && !targetPresent))
            "visual_keyboard_policy_denied" else null

    fun literal(text: String, description: String): KeyboardKeyPolicy.Key? =
        KeyboardKeyPolicy.identifyLabel(text, description)?.takeIf {
            it.kind in setOf(KeyboardKeyPolicy.Kind.CHARACTER, KeyboardKeyPolicy.Kind.SPACE, KeyboardKeyPolicy.Kind.BACKSPACE)
        }

    fun identify(text: String, description: String): KeyboardKeyPolicy.Key? =
        KeyboardKeyPolicy.identifyLabel(text, description)?.takeIf {
            it.kind != KeyboardKeyPolicy.Kind.SUBMIT && it.label != "🌐"
        }

    private fun bounds(value: String): TapBounds? {
        val parts = value.split(' ').map { it.toIntOrNull() ?: return null }
        if (parts.size != 4) return null
        return TapBounds(parts[0],parts[1],parts[2],parts[3]).takeIf { it.valid }
    }

    fun proofOwnerRefusal(proof: VisualTargetProof?, keyboard: ControlKeyboard): String? = when {
        proof == null -> "keyboard_visual_required"
        proof.scope == "keyboard" && proof.sourceWindowId == keyboard.windowId -> null
        proof.scope == "display" && proof.sourceDisplayId == 0 && proof.sourceImeWindowId == keyboard.windowId &&
            proof.imeBounds.isNotEmpty() && proof.displayGeneration >= 0 -> null
        else -> "keyboard_visual_owner_unverified"
    }

    fun refusal(frame: ControlFrame, node: ControlNode?, scope: String): String? {
        if (scope !in setOf("keyboard", "display")) return "keyboard_visual_required"
        val keyboard = frame.keyboard ?: return "keyboard_target_unverified"
        val field = frame.inputTarget ?: return "keyboard_input_target_unverified"
        if (!keyboard.readAllowed || !keyboard.interactAllowed || field.id != frame.inputTargetId ||
            field.sourceWindowId != frame.windowId || field.sourcePackage != frame.packageName ||
            !field.enabled || !field.editable || !field.focused || !field.focusable || field.password ||
            field.text.length > 2000 || InputPayloadPolicy.sensitive(field.text)) return "keyboard_input_target_unverified"
        if (node == null || !node.visualKeyCandidate || !node.enabled || node.password ||
            node.sourceWindowId != keyboard.windowId || node.sourcePackage != keyboard.packageName)
            return "keyboard_visual_candidate_unverified"
        val key = identify(node.text,node.description) ?: return "keyboard_key_unrecognized"
        if (key.label != node.keyboardKey || (key.kind != KeyboardKeyPolicy.Kind.LAYOUT && KeyboardKeyPolicy.expectedText(field,key) == null))
            return "keyboard_literal_unverified"
        val target = bounds(node.bounds) ?: return "keyboard_visual_bounds_invalid"
        val (x,y) = target.center
        if (key.kind == KeyboardKeyPolicy.Kind.LAYOUT) {
            if (field.selectionStart !in 0..field.text.length || field.selectionEnd !in field.selectionStart..field.text.length)
                return "keyboard_selection_unverified"
            if (frame.nodes.any { it.id != node.id && it.sourceWindowId == keyboard.windowId &&
                    identify(it.text,it.description) == key && bounds(it.bounds)?.center != target.center })
                return "keyboard_layout_ambiguous"
        }
        // Equal parent/child labels describe one purpose. Conflicting labels at
        // the injected point are ambiguous even when the image looks plausible.
        if (frame.nodes.any { it.sourceWindowId == keyboard.windowId && it.id != node.id &&
                bounds(it.bounds)?.contains(x,y) == true &&
                KeyboardKeyPolicy.identifyLabel(it.text,it.description)?.let { other -> other != key } == true })
            return "keyboard_visual_label_ambiguous"
        return null
    }

    /** Layout widgets/suggestion words alone do not establish a keyboard change.
     * Require two disjoint existing character-key locations to change labels;
     * overlapping parent/child bounds cannot count as separate physical keys. */
    fun literalLayoutChanged(before: ControlFrame, after: ControlFrame): Boolean {
        fun labels(frame: ControlFrame): Map<TapBounds,String> {
            val keyboard = frame.keyboard ?: return emptyMap()
            return frame.nodes.filter { it.enabled && !it.password && it.sourceWindowId == keyboard.windowId &&
                it.sourcePackage == keyboard.packageName && (it.visualKeyCandidate ||
                    KeyboardKeyPolicy.identify(it.text,it.description,it.className,it.clickable,it.textEntryKey) != null) }
                .mapNotNull { node ->
                    val key = literal(node.text,node.description)?.takeIf { it.kind == KeyboardKeyPolicy.Kind.CHARACTER }
                        ?: return@mapNotNull null
                    bounds(node.bounds)?.let { it to key.label }
                }.groupBy({ it.first },{ it.second }).mapNotNull { (rect,values) ->
                    values.distinct().singleOrNull()?.let { rect to it }
                }.toMap()
        }
        val old = labels(before); val fresh = labels(after)
        val changed = old.filter { (rect,label) -> fresh[rect]?.let { it != label } == true }.keys
        if (changed.size < 2) return false
        // A disjoint pair exists iff an interval starts at/after another ends on
        // either axis. Valid rectangles ensure the extrema cannot be one rect.
        return changed.maxOf { it.left } >= changed.minOf { it.right } ||
            changed.maxOf { it.top } >= changed.minOf { it.bottom }
    }
}
