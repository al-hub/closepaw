package ai.closepaw.chatgpt.control

/** A native label is corroborating evidence, never a substitute for reading the
 * separate OS-selected IME image. This route deliberately excludes layout and
 * submission keys. It does not relax semantic-key admission. */
internal object VisualKeyboardPolicy {
    fun literal(text: String, description: String): KeyboardKeyPolicy.Key? =
        KeyboardKeyPolicy.identifyLabel(text, description)?.takeIf {
            it.kind in setOf(KeyboardKeyPolicy.Kind.CHARACTER, KeyboardKeyPolicy.Kind.SPACE, KeyboardKeyPolicy.Kind.BACKSPACE)
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
        val key = literal(node.text,node.description) ?: return "keyboard_key_unrecognized"
        if (key.label != node.keyboardKey || KeyboardKeyPolicy.expectedText(field,key) == null)
            return "keyboard_literal_unverified"
        val target = bounds(node.bounds) ?: return "keyboard_visual_bounds_invalid"
        val (x,y) = target.center
        // Equal parent/child labels describe one purpose. Conflicting labels at
        // the injected point are ambiguous even when the image looks plausible.
        if (frame.nodes.any { it.sourceWindowId == keyboard.windowId && it.id != node.id &&
                bounds(it.bounds)?.contains(x,y) == true &&
                KeyboardKeyPolicy.identifyLabel(it.text,it.description)?.let { other -> other != key } == true })
            return "keyboard_visual_label_ambiguous"
        return null
    }
}
