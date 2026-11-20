package ai.closepaw.chatgpt.control

/** Private geometry of an owned IME caption with no recognized key role. */
internal data class KeyboardCaptionSlot(val parent: Int, val bounds: TapBounds)

/** Select the requested roles from the full verified frame, before presentation
 * limits. This is candidate disclosure only, never image or touch authority. */
internal object VisualSequenceCandidates {
    fun literal(label: String): KeyboardKeyPolicy.Key? =
        KeyboardImageFallbackPolicy.key(label)?.takeIf {
            it.kind in setOf(KeyboardKeyPolicy.Kind.CHARACTER, KeyboardKeyPolicy.Kind.SPACE)
        }

    fun select(frame: ControlFrame, labels: List<String>): List<ControlNode> {
        if (frame.truncated || frame.navigationOnly) return emptyList()
        val proof = frame.visual?.takeIf { it.scope == "keyboard" } ?: return emptyList()
        val ime = frame.keyboard?.takeIf { it.readAllowed && it.interactAllowed } ?: return emptyList()
        val owned = frame.nodes.filter { imeOwns(ime, it) }
        val evidence by lazy { StripEvidence(owned, ime, proof, frame) }
        return labels.distinct().map { label ->
            val role = literal(label) ?: return emptyList()
            val native = owned.filter {
                it.visualKeyCandidate && !it.keyboardImageCandidate && it.keyboardKey == label &&
                    VisualKeyboardPolicy.identifyCandidate(it, ime) == role
            }
            val candidate = if (native.isNotEmpty()) {
                val possibleStrip = native.filter { wideCharacterLeaf(it, ime) }
                val physical = native.filterNot { it in possibleStrip && evidence.isSuggestion(it) }
                onePhysicalKey(physical)?.takeIf { key ->
                    // An unproven wide leaf must neither win a collision nor
                    // be promoted on its own when only a suggestion is present.
                    // Keep the old corroboration requirement for an unproven long-click target.
                    ((key !in possibleStrip || key.longClickable) && physical.size == native.size ||
                        evidence.corroboratesKey(key)) &&
                        VisualKeyboardPolicy.refusal(frame, key, "keyboard") == null
                }
            } else {
                owned.singleOrNull { it.keyboardImageCandidate &&
                    VisualKeyboardPolicy.refusal(frame, it, "keyboard", label) == null }
            }
            candidate ?: return emptyList()
        }.takeIf { nodes -> nodes.map { it.id }.distinct().size == nodes.size }.orEmpty()
    }

    /** Optional case/language/page witness. Two distinct physically disjoint
     * character locations are needed. Space or a layout-control caption is
     * not evidence that the requested page became active. */
    fun witnessesLayout(frame: ControlFrame, labels: List<String>): Boolean {
        if (labels.size !in 2..6 || labels.distinct().size != labels.size ||
            labels.any { literal(it)?.kind != KeyboardKeyPolicy.Kind.CHARACTER }) return false
        val physical = select(frame, labels).filterNot { it.keyboardImageCandidate }
            .mapNotNull { KeyboardImageFallbackPolicy.bounds(it.bounds) }
        return physical.size == labels.size && physical.indices.any { i ->
            physical.drop(i + 1).any { b ->
                val a = physical[i]
                a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top
            }
        }
    }

    private data class Cell(val node: ControlNode, val rect: TapBounds)
    private data class Row(val parent: Int, val top: Int, val bottom: Int, val width: Int, val keys: List<Cell>)

    fun layoutNodes(frame: ControlFrame): List<ControlNode> {
        val proof = frame.visual?.takeIf { it.scope == "keyboard" } ?: return frame.nodes
        val ime = frame.keyboard ?: return frame.nodes
        val evidence by lazy {
            StripEvidence(frame.nodes.filter { imeOwns(ime, it) }, ime, proof, frame)
        }
        // A strip can itself contain three aligned labels. Do not let it
        // become the first physical layout row during sequence renewal.
        return frame.nodes.filterNot {
            imeOwns(ime, it) && wideCharacterLeaf(it, ime) && evidence.isSuggestion(it)
        }
    }

    private fun imeOwns(ime: ControlKeyboard, node: ControlNode): Boolean =
        node.enabled && !node.password && node.sourceWindowId == ime.windowId && node.sourcePackage == ime.packageName

    // A row needs independent, same-size disjoint character key boundaries,
    // under one native parent. Inner labels and actionable suggestion leaves
    // do not corroborate row geometry.
    private fun physicalRows(nodes: List<ControlNode>, ime: ControlKeyboard, proof: VisualTargetProof): List<Row> {
        if (VisualKeyboardPolicy.proofOwnerRefusal(proof, ime) != null || proof.sourceDisplayId != 0)
            return emptyList()
        return nodes.mapNotNull { node ->
            if (node.parent < 0 || !physicalCharacter(node, ime)) return@mapNotNull null
            ownedBounds(node, proof)?.let { Cell(node, it) }
        }.groupBy { cell ->
            listOf(cell.node.parent, cell.rect.top, cell.rect.bottom, cell.rect.right - cell.rect.left)
        }.values.mapNotNull { peers ->
            val keys = peers.sortedBy { it.rect.left }
            if (keys.size < 3 || keys.zipWithNext().any { (a, b) -> a.rect.right > b.rect.left })
                return@mapNotNull null
            val first = keys.first()
            Row(first.node.parent, first.rect.top, first.rect.bottom, first.rect.right - first.rect.left, keys)
        }
    }

    private fun physicalCharacter(node: ControlNode, ime: ControlKeyboard): Boolean =
        node.visualKeyCandidate && !node.keyboardImageCandidate &&
            (node.textEntryKey || node.visualKeyChildCount == 1) &&
            VisualKeyboardPolicy.identifyCandidate(node, ime)?.kind == KeyboardKeyPolicy.Kind.CHARACTER

    private fun wideCharacterLeaf(node: ControlNode, ime: ControlKeyboard): Boolean {
        if (!node.visualKeyCandidate || node.keyboardImageCandidate || !node.clickable ||
            node.textEntryKey || node.visualKeyChildCount != 0 ||
            VisualKeyboardPolicy.identifyCandidate(node, ime)?.kind != KeyboardKeyPolicy.Kind.CHARACTER) return false
        val r = KeyboardImageFallbackPolicy.bounds(node.bounds) ?: return false
        return r.right - r.left > 2L * (r.bottom - r.top)
    }

    private class StripEvidence(owned: List<ControlNode>, ime: ControlKeyboard, private val proof: VisualTargetProof,
        frame: ControlFrame) {
        private val rows = physicalRows(owned, ime, proof)
        private val first = rows.minByOrNull { it.top }
        private val wrapped = if (first != null && frame.reason == null && !frame.truncated && !frame.navigationOnly &&
            ime.packageName == LocalImeGeometryFixture.SAMSUNG && ime.readAllowed && ime.interactAllowed &&
            proof.scope == "keyboard" && proof.sourceDisclosureAllowed())
            WrappedImeSuggestionStrip.prove(frame.keyboardSuggestionGeometry, owned.filter { physicalCharacter(it, ime) },
                first.top, KeyboardImageFallbackPolicy.bounds(proof.imeBounds), proof.window)
        else emptyMap()
        private val captions = (frame.keyboardCaptionSlots + owned.mapNotNull {
            if (it.longClickable || !wideCharacterLeaf(it, ime)) null
            else ownedBounds(it, proof)?.let { r -> KeyboardCaptionSlot(it.parent, r) }
        }).groupBy { it.parent }

        fun isSuggestion(node: ControlNode): Boolean {
            val row = first ?: return false
            val geometry = KeyboardImageFallbackPolicy.bounds(node.bounds) ?: return false
            // Structural exclusion is not pixel certification. Physical keys
            // still need a fully certified owned bound and their own row proof.
            if (wrapped[node.id] == geometry) return true
            // Samsung's removable recommendation can be long-clickable. The
            // legacy same-parent heuristic must never exclude it without the
            // dedicated container proof above.
            if (node.longClickable) return false
            val target = ownedBounds(node, proof) ?: return false
            if (node.parent < 0 || rows.any { it.parent == node.parent } || target.bottom > row.top ||
                target.bottom - target.top >= row.bottom - row.top) return false
            val slots = captions[node.parent].orEmpty().map { it.bounds }.filter {
                it.top == target.top && it.bottom == target.bottom && it.right - it.left > 2L * row.width &&
                    it.right - it.left > 2L * (it.bottom - it.top) && ownedBounds(it, proof)
            }.sortedBy { it.left }
            // Being detached is not enough: require a whole strip of separate
            // same-parent captions. An isolated competing key remains ambiguous.
            return slots.size >= 3 && target in slots && slots.zipWithNext().all { (a, b) -> a.right <= b.left }
        }

        fun corroboratesKey(node: ControlNode): Boolean {
            val target = ownedBounds(node, proof) ?: return false
            return rows.any { row ->
                row.parent == node.parent && row.top == target.top && row.bottom == target.bottom &&
                    (row.keys.any { it.node.id == node.id } ||
                        row.keys.all { it.rect.right <= target.left || target.right <= it.rect.left } &&
                        row.keys.any { target.left - it.rect.right in 0..row.width ||
                            it.rect.left - target.right in 0..row.width })
            }
        }
    }

    private fun ownedBounds(node: ControlNode, proof: VisualTargetProof): TapBounds? =
        KeyboardImageFallbackPolicy.bounds(node.bounds)?.takeIf { ownedBounds(it, proof) }

    private fun ownedBounds(rect: TapBounds, proof: VisualTargetProof): Boolean =
        KeyboardImageFallbackPolicy.bounds(proof.imeBounds)?.contains(rect) == true &&
            proof.window.contains(rect) && OwnedPixelPolicy.contains(proof.disclosedRegions, rect)

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
