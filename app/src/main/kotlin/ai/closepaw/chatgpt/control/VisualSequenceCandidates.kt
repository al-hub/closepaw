package ai.closepaw.chatgpt.control

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
        val rows by lazy { physicalRows(owned, ime, proof) }
        return labels.distinct().map { label ->
            val role = literal(label) ?: return emptyList()
            val native = owned.filter {
                it.visualKeyCandidate && !it.keyboardImageCandidate && it.keyboardKey == label &&
                    VisualKeyboardPolicy.identifyCandidate(it, ime) == role
            }
            val candidate = if (native.isNotEmpty()) {
                val possibleStrip = native.filter { wideCharacterLeaf(it, ime) }
                val physical = native.filterNot { it in possibleStrip && detachedFromRows(it, rows, proof) }
                onePhysicalKey(physical)?.takeIf {
                    // An unproven wide leaf must neither win a collision nor
                    // be promoted on its own when only a suggestion is present.
                    (it !in possibleStrip) &&
                        (physical.size == native.size || rows.any { row -> row.keys.any { key -> key.node.id == it.id } }) &&
                        VisualKeyboardPolicy.refusal(frame, it, "keyboard") == null
                }
            } else {
                owned.singleOrNull { it.keyboardImageCandidate &&
                    VisualKeyboardPolicy.refusal(frame, it, "keyboard", label) == null }
            }
            candidate ?: return emptyList()
        }.takeIf { nodes -> nodes.map { it.id }.distinct().size == nodes.size }.orEmpty()
    }

    private data class Cell(val node: ControlNode, val rect: TapBounds)
    private data class Row(val parent: Int, val top: Int, val bottom: Int, val width: Int, val keys: List<Cell>)

    fun layoutNodes(frame: ControlFrame): List<ControlNode> {
        val proof = frame.visual?.takeIf { it.scope == "keyboard" } ?: return frame.nodes
        val ime = frame.keyboard ?: return frame.nodes
        val rows by lazy { physicalRows(frame.nodes.filter { imeOwns(ime, it) }, ime, proof) }
        // A strip can itself contain three aligned labels. Do not let it
        // become the first physical layout row during sequence renewal.
        return frame.nodes.filterNot {
            imeOwns(ime, it) && wideCharacterLeaf(it, ime) && detachedFromRows(it, rows, proof)
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
            if (node.parent < 0 || !node.visualKeyCandidate || node.keyboardImageCandidate ||
                (!node.textEntryKey && node.visualKeyChildCount != 1) ||
                VisualKeyboardPolicy.identifyCandidate(node, ime)?.kind != KeyboardKeyPolicy.Kind.CHARACTER)
                return@mapNotNull null
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

    private fun wideCharacterLeaf(node: ControlNode, ime: ControlKeyboard): Boolean {
        if (!node.visualKeyCandidate || node.keyboardImageCandidate || !node.clickable ||
            node.longClickable || node.textEntryKey || node.visualKeyChildCount != 0 ||
            VisualKeyboardPolicy.identifyCandidate(node, ime)?.kind != KeyboardKeyPolicy.Kind.CHARACTER) return false
        val r = KeyboardImageFallbackPolicy.bounds(node.bounds) ?: return false
        return r.right - r.left > 2L * (r.bottom - r.top)
    }

    private fun detachedFromRows(node: ControlNode, rows: List<Row>, proof: VisualTargetProof): Boolean {
        val first = rows.minByOrNull { it.top } ?: return false
        val r = ownedBounds(node, proof) ?: return false
        return node.parent >= 0 && rows.none { it.parent == node.parent } &&
            r.bottom <= first.top && r.right - r.left > 2L * first.width &&
            r.bottom - r.top < first.bottom - first.top
    }

    private fun ownedBounds(node: ControlNode, proof: VisualTargetProof): TapBounds? {
        val ime = KeyboardImageFallbackPolicy.bounds(proof.imeBounds) ?: return null
        return KeyboardImageFallbackPolicy.bounds(node.bounds)?.takeIf {
            ime.contains(it) && proof.window.contains(it) && OwnedPixelPolicy.contains(proof.disclosedRegions, it)
        }
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
