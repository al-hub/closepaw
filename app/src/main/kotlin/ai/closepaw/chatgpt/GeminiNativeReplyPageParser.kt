package ai.closepaw.chatgpt

/** Read offscreen text only from the native response structurally following this exact user turn.
 * Offscreen toolbar controls never prove completion. The caller still validates foreground/lock.
 */
internal object GeminiNativeReplyPageParser {
    private const val HISTORY = "assistant_robin_chat_history_list"
    private const val QUESTION = "assistant_robin_user_message_text"
    private const val RESPONSE = "assistant_robin_content_message_container"
    private const val BODY = "assistant_robin_text"
    private fun GeminiUiNode.id() = resourceId.substringAfterLast('/')
    private fun GeminiUiNode.body() = text.ifBlank { description }.trim()
    private fun menu(n: GeminiUiNode) = listOf(n.id(), n.className).any { name ->
        listOf("menu", "dialog", "popup", "picker").any { it in name.lowercase() }
    }

    fun parse(nodes: List<GeminiUiNode>, question: String, root: GeminiUiNode, key: String): GeminiReplyPage? {
        if (root.id() != HISTORY || !root.visible || !root.enabled) return null
        val pkg = root.packageName
        if (pkg !in setOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")) return null
        val byIndex = nodes.associateBy { it.index }
        val positions = nodes.mapIndexed { i, n -> n.index to i }.toMap()
        fun path(n: GeminiUiNode): List<GeminiUiNode> {
            val out = mutableListOf<GeminiUiNode>(); var current = n
            repeat(42) {
                if (out.any { it.index == current.index }) return emptyList()
                out.add(current)
                if (current.parent < 0) return out
                current = byIndex[current.parent] ?: return emptyList()
            }
            return emptyList()
        }
        fun belongs(n: GeminiUiNode, owner: GeminiUiNode) = path(n).any { it.index == owner.index }
        fun safe(n: GeminiUiNode): Boolean {
            val p = path(n)
            return p.isNotEmpty() && belongs(n, root) && p.all { a ->
                a.packageName == pkg && a.enabled && !a.password && !a.editable && !a.composer && !menu(a)
            } && p.dropWhile { it.index != root.index }.all { it.visible }
        }
        fun visible(n: GeminiUiNode) = safe(n) && path(n).all { it.visible }
        val owned = nodes.filter(::safe)
        val anchors = owned.filter { it.id() == QUESTION && it.body() == question }
        val anchor = anchors.singleOrNull() ?: return null
        fun page(text: String = "", end: Boolean = false, conflict: Boolean = false, evidence: Set<String> = emptySet()) =
            GeminiReplyPage(key, root.index, text, visible(anchor), end, root.scrollForward, root.scrollBackward, conflict, evidence)
        val after = owned.filter { positions.getValue(it.index) > positions.getValue(anchor.index) }
        if (after.any { it.id() == QUESTION && it.body().isNotBlank() && it.body() != question }) return page(conflict = true)
        val replies = after.filter { it.id() == RESPONSE }.filter { n ->
            after.none { it.index != n.index && it.id() == RESPONSE && belongs(n, it) }
        }
        if (replies.isEmpty()) return null // Startup can still use the exact-question viewport path.
        val reply = replies.singleOrNull() ?: return page(conflict = true)
        val scope = owned.filter { belongs(it, reply) }
        val fragments = scope.filter { it.id() == BODY && it.body().isNotBlank() }.filter { n ->
            scope.none { it.index != n.index && it.id() == BODY && belongs(n, it) }
        }.map { it.body() }
        if (fragments.isEmpty()) return page()
        // Native layouts either wrap body+toolbar in one row or attach the
        // toolbar as the immediately following history item. Only known actions
        // containers in that bounded interval may prove completion.
        fun row(n: GeminiUiNode) = path(n).zipWithNext().firstOrNull { it.second.index == root.index }?.first
        val responseRow = row(reply)
        val directRows = nodes.filter { it.parent == root.index }
        val nextRow = responseRow?.let { directRows.getOrNull(directRows.indexOf(it) + 1) }
        val separateToolbarRow = nextRow?.takeIf { candidate ->
            responseRow?.index == reply.index && owned.any { n ->
                belongs(n, candidate) && n.id() == "assistant_robin_message_content_actions_section"
            } && owned.none { n -> belongs(n, candidate) && n.id() in setOf(QUESTION, RESPONSE, BODY) }
        }
        val siblingToolbar = owned.filter { n ->
            val sameWrappedRow = responseRow != null && responseRow.index != reply.index && row(n)?.index == responseRow.index
            val separateRow = separateToolbarRow != null && belongs(n, separateToolbarRow)
            (sameWrappedRow || separateRow) && positions.getValue(n.index) > positions.getValue(reply.index) &&
                path(n).any { a -> a.id() in setOf(
                    "assistant_robin_content_actions_scroll_view", "assistant_robin_content_actions_container") } &&
                path(n).none { a -> a.id() == RESPONSE && a.index != reply.index }
        }
        val controls = (scope + siblingToolbar).distinctBy { it.index }.filter(::visible)
        fun target(n: GeminiUiNode) = path(n).take(4).firstOrNull { a ->
            a.index != reply.index && a.index != root.index && visible(a) && !a.scrollable && (a.clickable || a.clickAction)
        }?.index
        fun labels(n: GeminiUiNode) = listOf(n.text, n.description).map { it.trim().lowercase() }
        val copy = controls.filter(GeminiUiControlResolver::isCopyRole).mapNotNull(::target).distinct()
        val positive = controls.filter { n -> labels(n).any { it in setOf("대답이 마음에 들어요", "good response", "like response") } }
            .mapNotNull(::target).distinct()
        val negative = controls.filter { n -> labels(n).any { it in setOf("대답이 마음에 들지 않아요", "bad response", "dislike response") } }
            .mapNotNull(::target).distinct()
        val end = copy.size == 1 && positive.size == 1 && negative.size == 1 && (copy + positive + negative).distinct().size == 3
        return page(fragments.joinToString("\n"), end, evidence = fragments.map(GeminiUiTextEvidence::hash).toSet())
            .copy(nativeWholeBody = true)
    }
}
