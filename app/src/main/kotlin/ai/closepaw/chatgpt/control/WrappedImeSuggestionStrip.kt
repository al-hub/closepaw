package ai.closepaw.chatgpt.control

/** Admit only the observed dedicated branch shape, not an arbitrary common
 * ancestor. This removes a competing caption, never supplies touch evidence. */
internal object WrappedImeSuggestionStrip {
    fun prove(tree: List<ImeGeometryNode>, physical: List<ControlNode>, firstCertifiedRowTop: Int,
        imeBounds: TapBounds?, screen: TapBounds): Map<Int, TapBounds> {
        if (tree.size !in 1..8192 || imeBounds == null || physical.isEmpty()) return emptyMap()
        val byId = tree.associateBy { it.index }
        val root = tree.singleOrNull { it.parent == -1 } ?: return emptyMap()
        if (byId.size != tree.size) return emptyMap()

        fun path(node: ImeGeometryNode): List<Int>? {
            val ids = mutableListOf<Int>()
            var current = node
            while (true) {
                if (ids.size >= 65 || current.index in ids) return null
                ids.add(current.index)
                if (current.parent == -1) return ids.takeIf { current.index == root.index }
                current = byId[current.parent] ?: return null
            }
        }
        fun admittedBounds(node: ImeGeometryNode) =
            node.eligible && imeBounds.contains(node.bounds) && screen.contains(node.bounds)

        val captions = tree.filter { it.kind == ImeGeometryNode.Kind.CAPTION && it.eligible &&
            byId[it.parent]?.kind == ImeGeometryNode.Kind.WRAPPER }
        if (captions.size !in 3..8) return emptyMap()
        data class Branch(val caption: ImeGeometryNode, val inner: ImeGeometryNode, val outer: ImeGeometryNode)
        val branches = captions.map { caption ->
            val inner = byId[caption.parent] ?: return emptyMap()
            val outer = byId[inner.parent] ?: return emptyMap()
            if (outer.kind != ImeGeometryNode.Kind.OTHER ||
                !listOf(caption, inner, outer).all(::admittedBounds) ||
                !inner.bounds.contains(caption.bounds) || !outer.bounds.contains(inner.bounds) ||
                path(caption) == null) return emptyMap()
            Branch(caption, inner, outer)
        }.sortedBy { it.outer.bounds.left }
        val common = byId[branches.first().outer.parent] ?: return emptyMap()
        if (common.index == root.index || common.kind != ImeGeometryNode.Kind.OTHER ||
            !admittedBounds(common) || common.bounds.bottom > firstCertifiedRowTop ||
            branches.map { it.inner.index }.distinct().size != branches.size ||
            branches.map { it.outer.index }.distinct().size != branches.size ||
            branches.any { it.outer.parent != common.index || !common.bounds.contains(it.outer.bounds) ||
                it.outer.bounds.top != branches.first().outer.bounds.top ||
                it.outer.bounds.bottom != branches.first().outer.bounds.bottom } ||
            branches.zipWithNext().any { (a, b) -> a.outer.bounds.right > b.outer.bounds.left })
            return emptyMap()
        // Do not infer ownership of an unobserved/cyclic ancestor or use only
        // the certified subset of keys to establish what is under the branch.
        for (key in physical) {
            val node = byId[key.id] ?: return emptyMap()
            val chain = path(node) ?: return emptyMap()
            val bounds = KeyboardImageFallbackPolicy.bounds(key.bounds) ?: return emptyMap()
            if (node.kind != ImeGeometryNode.Kind.OTHER || node.parent != key.parent ||
                node.bounds != bounds || common.index in chain || bounds.top < common.bounds.bottom)
                return emptyMap()
        }
        return captions.associate { it.index to it.bounds }
    }
}
