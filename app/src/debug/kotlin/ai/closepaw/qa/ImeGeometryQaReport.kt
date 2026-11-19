package ai.closepaw.qa

import ai.closepaw.chatgpt.control.*
import kotlinx.serialization.json.*

/** Geometry-only historical evidence from one admitted blank Chrome fixture.
 * Paths use report-local aliases; no labels, native IDs, images or action handles. */
internal object ImeGeometryQaReport {
    fun collect(frame: ControlFrame, tree: List<ImeGeometryQaNode>, proof: VisualTargetProof?): JsonObject {
        val owner = frame.keyboard
        val byId = tree.associateBy { it.index }
        val root = tree.singleOrNull { it.parent == -1 }
        val verified = proof?.takeIf { p -> owner != null &&
            VisualKeyboardPolicy.proofOwnerRefusal(p, owner) == null && p.scope == "keyboard" &&
            p.sourceDisplayId == 0 && p.disclosedRegions.isNotEmpty() && p.sourceDisclosureAllowed()
        }
        fun refusal(reason: String) = buildJsonObject {
            put("structure", reason)
            put("certified_pixels_available", verified != null)
        }
        if (frame.reason != null || frame.truncated || frame.navigationOnly || root == null || owner == null ||
            tree.size != byId.size || tree.size > 8192) return refusal("owned_tree_unavailable")

        fun path(id: Int): List<Int>? {
            val ids = mutableListOf<Int>()
            var next = id
            while (next >= 0) {
                if (ids.size >= 65 || next in ids) return null
                val node = byId[next] ?: return null
                ids.add(next)
                next = node.parent
            }
            return ids.takeIf { it.lastOrNull() == root.index }
        }
        val caps = tree.filter { it.kind == ImeGeometryQaNode.Kind.CAPTION && it.eligible &&
            byId[it.parent]?.let { p -> p.kind == ImeGeometryQaNode.Kind.WRAPPER && p.eligible } == true
        }.sortedBy { it.bounds.left }
        if (caps.size !in 3..8 || caps.map { it.parent }.distinct().size != caps.size)
            return refusal("caption_paths_ambiguous")
        val paths = caps.map { path(it.index) ?: return refusal("caption_path_incomplete") }
        val commonIndex = paths.first().drop(2).firstOrNull { id -> paths.all { id in it.drop(2) } }
            ?: return refusal("common_ancestor_missing")
        val common = byId.getValue(commonIndex)
        val displayed = paths.flatten().distinct()
        if (displayed.size > 64) return refusal("path_output_limit")
        val aliases = displayed.withIndex().associate { (i, id) -> id to "n${i + 1}" }

        val cells = frame.nodes.filter { n ->
            n.sourceWindowId == owner.windowId && n.sourcePackage == owner.packageName && n.enabled && !n.password &&
                n.visualKeyCandidate && !n.keyboardImageCandidate && (n.textEntryKey || n.visualKeyChildCount == 1) &&
                VisualKeyboardPolicy.identifyCandidate(n, owner)?.kind == KeyboardKeyPolicy.Kind.CHARACTER
        }.mapNotNull { n ->
            val native = byId[n.id] ?: return@mapNotNull null
            native.takeIf { it.bounds.valid && it.eligible && path(it.index) != null }
        }
        val rows = cells.groupBy { listOf(it.parent, it.bounds.top, it.bounds.bottom, it.bounds.right - it.bounds.left) }
            .values.mapNotNull { peers ->
                val sorted = peers.sortedBy { it.bounds.left }
                if (sorted.size < 3 || sorted.zipWithNext().any { (a, b) -> a.bounds.right > b.bounds.left }) null
                else sorted
            }.sortedBy { it.first().bounds.top }
        val rowHulls = rows.map { row ->
            val a = row.first().bounds
            TapBounds(a.left, a.top, row.last().bounds.right, a.bottom)
        }
        val fullRows = rows.all { row -> row.all { pixelState(it.bounds, verified) == "full" } }
        val containsPhysical = cells.any { commonIndex in path(it.index).orEmpty() }
        val separate = commonIndex != root.index && common.eligible && common.bounds.valid &&
            caps.all { common.bounds.contains(it.bounds) } && rows.isNotEmpty() && !containsPhysical &&
            rowHulls.all { common.bounds.bottom <= it.top }
        return buildJsonObject {
            put("structure", when {
                commonIndex == root.index -> "common_ancestor_is_ime_root"
                rows.isEmpty() -> "physical_rows_unverified"
                !separate -> "dedicated_strip_not_established"
                else -> "separate_caption_container_observed"
            })
            put("common_ancestor", aliases.getValue(commonIndex))
            put("ime_root", aliases.getValue(root.index))
            put("common_ancestor_contains_physical_candidates", containsPhysical)
            put("certified_pixels_available", verified != null)
            put("physical_rows_fully_certified", rows.isNotEmpty() && fullRows)
            verified?.let { p ->
                put("screen_bounds", box(p.window))
                KeyboardImageFallbackPolicy.bounds(p.imeBounds)?.let { put("ime_bounds", box(it)) }
            }
            put("paths", buildJsonArray {
                paths.forEach { chain -> add(JsonArray(chain.map { JsonPrimitive(aliases.getValue(it)) })) }
            })
            put("path_geometry", buildJsonObject {
                displayed.forEach { id ->
                    val node = byId.getValue(id)
                    put(aliases.getValue(id), geometry(node.bounds, verified))
                }
            })
            put("physical_rows", buildJsonArray {
                rowHulls.take(8).forEach { add(geometry(it, verified)) }
            })
        }
    }

    private fun box(r: TapBounds) = JsonArray(listOf(r.left, r.top, r.right, r.bottom).map(::JsonPrimitive))
    private fun overlaps(a: TapBounds, b: TapBounds) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun geometry(bounds: TapBounds, p: VisualTargetProof?) = buildJsonObject {
        put("bounds", box(bounds))
        put("certified_coverage", pixelState(bounds, p))
        if (p != null) {
            put("inside_screen", p.window.contains(bounds))
            put("inside_ime", KeyboardImageFallbackPolicy.bounds(p.imeBounds)?.contains(bounds) == true)
        }
    }

    private fun pixelState(r: TapBounds, p: VisualTargetProof?): String = when {
        p == null -> "unknown"
        r.right <= r.left || r.bottom <= r.top -> "unusable_bounds"
        p.window.contains(r) && KeyboardImageFallbackPolicy.bounds(p.imeBounds)?.contains(r) == true &&
            OwnedPixelPolicy.contains(p.disclosedRegions, r) -> "full"
        p.disclosedRegions.any { it.valid && overlaps(it, r) } -> "partial"
        else -> "none"
    }
}
