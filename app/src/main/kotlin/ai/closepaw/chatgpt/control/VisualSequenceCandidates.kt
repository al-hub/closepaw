package ai.closepaw.chatgpt.control

/** Select the requested roles from the full verified frame, before presentation
 * limits. This is candidate disclosure only, never image or touch authority. */
internal object VisualSequenceCandidates {
    fun literal(label: String): KeyboardKeyPolicy.Key? =
        KeyboardImageFallbackPolicy.key(label)?.takeIf {
            it.kind in setOf(KeyboardKeyPolicy.Kind.CHARACTER, KeyboardKeyPolicy.Kind.SPACE)
        }

    fun select(frame: ControlFrame, labels: List<String>): List<ControlNode> {
        if (frame.truncated || frame.navigationOnly || frame.visual?.scope != "keyboard") return emptyList()
        val ime = frame.keyboard?.takeIf { it.readAllowed && it.interactAllowed } ?: return emptyList()
        val owned = frame.nodes.filter {
            it.enabled && !it.password && it.sourceWindowId == ime.windowId && it.sourcePackage == ime.packageName
        }
        return labels.distinct().map { label ->
            val role = literal(label) ?: return emptyList()
            val native = owned.filter {
                it.visualKeyCandidate && !it.keyboardImageCandidate && it.keyboardKey == label &&
                    VisualKeyboardPolicy.identifyCandidate(it, ime) == role
            }
            val candidate = if (native.isNotEmpty()) {
                onePhysicalKey(native)?.takeIf { VisualKeyboardPolicy.refusal(frame, it, "keyboard") == null }
            } else {
                owned.singleOrNull { it.keyboardImageCandidate &&
                    VisualKeyboardPolicy.refusal(frame, it, "keyboard", label) == null }
            }
            candidate ?: return emptyList()
        }.takeIf { nodes -> nodes.map { it.id }.distinct().size == nodes.size }.orEmpty()
    }

    // A directly nested label can corroborate its wrapper. Overlap or equal
    // text alone never merges two independently actionable physical keys.
    private fun onePhysicalKey(nodes: List<ControlNode>): ControlNode? =
        nodes.singleOrNull { wrapper ->
            val outer = KeyboardImageFallbackPolicy.bounds(wrapper.bounds) ?: return@singleOrNull false
            nodes.all { other ->
                other === wrapper || (other.parent == wrapper.id && !other.clickable && !other.longClickable &&
                    !other.textEntryKey && other.visualKeyChildCount == 0 &&
                    KeyboardImageFallbackPolicy.bounds(other.bounds)?.let(outer::contains) == true)
            }
        }
}
