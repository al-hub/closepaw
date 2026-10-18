package ai.closepaw.chatgpt

/** A bounded viewport, never proof of a whole answer by itself. Text is invocation-local. */
internal data class GeminiReplyPage(
    val containerKey: String,
    val containerIndex: Int,
    val text: String,
    val questionVisible: Boolean,
    val endVisible: Boolean,
    val canForward: Boolean,
    val canBackward: Boolean,
    val conflictingTurn: Boolean = false,
    val textEvidence: Set<String> = emptySet(),
    // Exact native question/response ownership exposes the entire body, including offscreen text.
    val nativeWholeBody: Boolean = false,
)

/** Restricts paging to one vertical conversation region, excluding composer and app chrome. */
internal object GeminiReplyPageParser {
    private val questionIds = setOf("assistant_robin_query_text", "robin_query_text", "user_query", "assistant_robin_user_message_text")
    private val replyIds = setOf("assistant_robin_response_text", "robin_response_text", "assistant_robin_response_container")
    private val toolbar = setOf("대답이 마음에 들어요", "대답이 마음에 들지 않아요", "good response", "bad response",
        "like response", "dislike response", "복사", "copy", "copy response", "답변 복사", "옵션 다시 실행",
        "다시 실행", "공유", "옵션 더보기", "듣기", "retry", "regenerate", "share", "more options", "listen", "read aloud")
    private fun GeminiUiNode.body() = text.ifBlank { description }.trim()
    private fun GeminiUiNode.id() = resourceId.substringAfterLast('/')
    private fun labels(n: GeminiUiNode) = listOf(n.text, n.description).map { it.trim().lowercase() }
    private fun control(n: GeminiUiNode) = labels(n).any { it in toolbar } ||
        GeminiUiControlResolver.isStopRole(n) || GeminiUiControlResolver.isSendRole(n) || GeminiUiControlResolver.isCopyRole(n)
    private fun menu(n: GeminiUiNode) = listOf(n.id(), n.className).any {
        val s = it.lowercase(); listOf("menu", "dialog", "popup", "picker").any(s::contains)
    }
    private fun disclaimer(s: String) = s.startsWith("Gemini는 AI이며") || s.startsWith("Gemini can make mistakes") ||
        s.startsWith("Gemini may display inaccurate info")

    fun parse(nodes: List<GeminiUiNode>, question: String?, complete: Boolean, trustedKey: String? = null): GeminiReplyPage? {
        if (!complete || question.isNullOrBlank() || nodes.isEmpty() || nodes.map { it.index }.distinct().size != nodes.size) return null
        val byIndex = nodes.associateBy { it.index }
        val pkg = nodes.first().packageName
        fun chain(node: GeminiUiNode): List<GeminiUiNode> {
            val result = mutableListOf<GeminiUiNode>(); var current = node
            repeat(42) {
                if (result.any { it.index == current.index }) return emptyList()
                result.add(current)
                if (current.parent < 0) return result
                current = byIndex[current.parent] ?: return emptyList()
            }
            return emptyList()
        }
        fun safe(n: GeminiUiNode) = chain(n).let { it.isNotEmpty() && it.all { a ->
            a.visible && a.enabled && !a.password && !a.editable && !a.composer && a.packageName == pkg && !menu(a)
        } }
        fun within(n: GeminiUiNode, root: GeminiUiNode) = chain(n).any { it.index == root.index }
        fun key(n: GeminiUiNode) = chain(n).asReversed().joinToString("/") { "${it.resourceId}:${it.className}" }
        val containers = nodes.filter { n -> safe(n) && (n.scrollable || key(n) == trustedKey) &&
            (n.scrollForward || n.scrollBackward || key(n) == trustedKey) && !n.className.contains("Horizontal", true) &&
            nodes.none { (it.composer || it.editable) && within(it, n) } &&
            (key(n) == trustedKey || nodes.any { safe(it) && within(it, n) && (it.body() == question || it.id() in replyIds || control(it)) }) }
        // Nested scroll regions are deliberately unsupported; never guess a list/carousel.
        val root = containers.singleOrNull() ?: return null
        GeminiNativeReplyPageParser.parse(nodes, question, root, key(root))?.let { return it }
        val region = nodes.filter { safe(it) && within(it, root) && it.index != root.index }
        // RecyclerView attachment order can put the response toolbar before its question/body.
        // Physical top/left order restores the visible turn boundary without trusting stale children.
        val scope = if (region.any { it.screenTop != null }) region.sortedWith(
            compareBy<GeminiUiNode>({ it.screenTop ?: Int.MAX_VALUE }, { it.screenLeft ?: Int.MAX_VALUE }, { it.index })
        ) else region
        val anchors = scope.filter { it.body() == question && !control(it) }.filter { n ->
            scope.none { it.index != n.index && it.body() == question && chain(n).drop(1).any { a -> a.index == it.index } }
        }
        if (anchors.size > 1) return null
        val anchor = anchors.singleOrNull()
        val start = anchor?.let { scope.indexOf(it) + 1 } ?: 0
        val tail = scope.drop(start).filterNot { anchor != null && chain(it).drop(1).any { a -> a.index == anchor.index } }
        val otherTurn = tail.any { it.id() in questionIds && it.body().isNotBlank() && it.body() != question }
        val footerIndex = tail.indexOfFirst { control(it) && !GeminiUiControlResolver.isStopRole(it) && !GeminiUiControlResolver.isSendRole(it) }
        val beforeFooter = if (footerIndex >= 0) tail.take(footerIndex) else tail
        if (beforeFooter.any { (it.clickable || it.clickAction) && !control(it) && it.body().isNotBlank() }) return null
        val candidates = beforeFooter.filter { it.body().isNotBlank() && !control(it) && !disclaimer(it.body()) && it.body() != question }
        val outer = candidates.filter { n -> candidates.none { a -> a.index != n.index && chain(n).drop(1).any { it.index == a.index } } }
        val footer = if (footerIndex >= 0) tail.drop(footerIndex) else emptyList()
        fun actionTarget(n: GeminiUiNode) = chain(n).take(4).firstOrNull {
            it.index != root.index && !it.scrollable && safe(it) && (it.clickable || it.clickAction)
        }?.index
        val copy = footer.filter { GeminiUiControlResolver.isCopyRole(it) }.mapNotNull(::actionTarget).distinct()
        val positive = footer.filter { n -> labels(n).any { it in setOf("대답이 마음에 들어요", "good response", "like response") } }
            .mapNotNull(::actionTarget).distinct()
        val negative = footer.filter { n -> labels(n).any { it in setOf("대답이 마음에 들지 않아요", "bad response", "dislike response") } }
            .mapNotNull(::actionTarget).distinct()
        val verifiedToolbar = copy.size == 1 && positive.size == 1 && negative.size == 1 &&
            (copy + positive + negative).distinct().size == 3
        val explicit = scope.any { it.id() in replyIds }
        return GeminiReplyPage(key(root), root.index, outer.joinToString("\n") { it.body() }, anchor != null,
            verifiedToolbar || (copy.size == 1 && explicit), root.scrollForward, root.scrollBackward, otherTurn, outer.map { GeminiUiTextEvidence.hash(it.body()) }.toSet())
    }

    /** Re-resolve the exact same path, package and viewport immediately before scrolling. */
    fun target(nodes: List<GeminiUiNode>, question: String?, complete: Boolean, key: String, forward: Boolean): Int? {
        val page = parse(nodes, question, complete, key) ?: return null
        if (page.containerKey != key || page.conflictingTurn || !(if (forward) page.canForward else page.canBackward)) return null
        return page.containerIndex
    }
}
