package ai.closepaw.chatgpt.control

/** Only layout/geometry metadata lives here. This cache never holds a visual
 * proof, observation ID, editor text, or permission to dispatch. */
internal class KeyboardTouchGeometry {
    data class Owner(val session: String, val epoch: Long, val consentEpoch: Long,
        val configuration: Long, val windowContext: String, val ime: ControlKeyboard)
    data class Transform(val viewport: TapBounds, val rotation: Int, val display: Int,
        val imageWidth: Int, val imageHeight: Int) {
        fun toScreen(rect: TapBounds): TapBounds? {
            if (display != 0 || rotation !in 0..3 || !viewport.valid ||
                !TapBounds(0, 0, imageWidth, imageHeight).contains(rect)) return null
            // Android provides the already display-aligned buffer. Rotation
            // binds that transform, and must never be applied a second time.
            fun x(v: Int) = viewport.left + (v.toLong() * (viewport.right - viewport.left) / imageWidth).toInt()
            fun y(v: Int) = viewport.top + (v.toLong() * (viewport.bottom - viewport.top) / imageHeight).toInt()
            return TapBounds(x(rect.left), y(rect.top), x(rect.right), y(rect.bottom)).takeIf(viewport::contains)
        }
    }
    data class Entry(val key: TapBounds, val imageProposal: TapBounds, val imeBounds: TapBounds,
        val neighbors: List<TapBounds>, val role: String, val transform: Transform)
    data class Selection(val patch: TapBounds, val reused: Boolean)
    private var owner: Owner? = null
    private val arrays = LinkedHashMap<String, LinkedHashMap<Entry, TapBounds>>(4, .75f, true)

    @Synchronized fun clear() { owner = null; arrays.clear() }

    /** Call only with today's independently certified owned regions. The same
     * checked patch must be covered in a fresh snapshot even on a cache hit. */
    @Synchronized fun select(current: Owner, layout: String, entry: Entry, owned: List<TapBounds>): Selection? {
        if (current != owner) { clear(); owner = current }
        val table = layout.takeIf { it.isNotEmpty() }?.let { arrays.getOrPut(it) { LinkedHashMap(16, .75f, true) } }
        while (arrays.size > 4) arrays.remove(arrays.keys.first())
        table?.get(entry)?.let {
            if (OwnedPixelPolicy.contains(owned, it)) return Selection(it, true)
            table.remove(entry)
        }
        val dx = maxOf(2, (entry.key.right - entry.key.left) / 8)
        val dy = maxOf(2, (entry.key.bottom - entry.key.top) / 8)
        val inner = TapBounds(entry.key.left + dx, entry.key.top + dy, entry.key.right - dx, entry.key.bottom - dy)
        // An apparently clipped native key is not repaired by moving the tap.
        val proposal = entry.transform.toScreen(entry.imageProposal) ?: return null
        if (!entry.imeBounds.contains(entry.key) || !entry.key.contains(proposal) || !inner.valid) return null
        val allowed = intersection(inner, proposal) ?: return null
        val (cx, cy) = entry.key.center
        val patch = owned.asSequence().mapNotNull { intersection(it, allowed) }.mapNotNull { area ->
            // An independently owned patch around the physical touch point,
            // not an isolated pixel at the edge of a key or neighboring key.
            if (area.right - area.left < 5 || area.bottom - area.top < 5) return@mapNotNull null
            val x = cx.coerceIn(area.left + 2, area.right - 3)
            val y = cy.coerceIn(area.top + 2, area.bottom - 3)
            TapBounds(x - 2, y - 2, x + 3, y + 3).takeUnless { p ->
                entry.neighbors.any { intersection(it, p) != null }
            }
        }.minByOrNull { p ->
            val (x, y) = p.center
            (x - cx).toLong() * (x - cx) + (y - cy).toLong() * (y - cy)
        } ?: return null
        if (table != null) {
            table[entry] = patch
            while (table.size > 32) table.remove(table.keys.first())
        }
        return Selection(patch, false)
    }

    private fun intersection(a: TapBounds, b: TapBounds): TapBounds? =
        TapBounds(maxOf(a.left,b.left), maxOf(a.top,b.top), minOf(a.right,b.right), minOf(a.bottom,b.bottom))
            .takeIf { it.valid }
}

/** Relative native layout identity. App/editor text and suggestion words are
 * not part of this key. Fresh ownership checks happen outside this identity. */
internal object KeyboardTouchLayout {
    fun fingerprint(nodes: List<ControlNode>, imeBounds: TapBounds, ime: ControlKeyboard): String {
        val keys = nodes.mapNotNull { node ->
            val rect = KeyboardImageFallbackPolicy.bounds(node.bounds) ?: return@mapNotNull null
            if (!imeBounds.contains(rect) || !node.enabled || node.password ||
                node.sourcePackage != ime.packageName || node.sourceWindowId != ime.windowId) return@mapNotNull null
            val key = VisualKeyboardPolicy.identifyCandidate(node, ime)
                ?: KeyboardKeyPolicy.identify(node.text, node.description, node.className, node.clickable, node.textEntryKey)
            if (key == null && !node.keyboardImageCandidate) return@mapNotNull null
            rect to key
        }.distinct()
        val literalRows = keys.filter { it.second?.kind == KeyboardKeyPolicy.Kind.CHARACTER }
            .groupBy { it.first.center.second }
            .filterValues { row -> row.map { it.first.left }.distinct().size >= 3 }
            .keys
        return keys.filter { (rect, _) -> literalRows.any { it in rect.top until rect.bottom } ||
            literalRows.maxOrNull()?.let { row ->
                rect.center.second >= row && rect.top - row <= rect.bottom - rect.top
            } == true
        }.map { (rect, role) ->
            "${rect.left-imeBounds.left},${rect.top-imeBounds.top},${rect.right-imeBounds.left},${rect.bottom-imeBounds.top}:${role?.kind}:${role?.label.orEmpty()}"
        }.sorted().joinToString("|")
    }
}
