package ai.closepaw.chatgpt

/** Resolves only a semantic Gemini control or its small, nearest actionable wrapper. */
internal object GeminiUiControlResolver {
    private val packages = setOf(AndroidGeminiDraftUi.PACKAGE, AndroidGeminiDraftUi.GOOGLE_HOST)
    private val sendIds = setOf("assistant_robin_send_button", "robin_send_button", "send_button")
    private val stopIds = setOf("assistant_robin_stop_button", "stop_generating_button")
    private val copyIds = setOf("assistant_robin_copy_button", "copy_response_button")
    private val sendLabels = setOf("send", "send message", "send prompt", "메시지 보내기", "프롬프트 보내기", "보내기")
    private val stopLabels = setOf("stop response", "stop generating", "답변 중지", "응답 중지", "생성 중지")
    private val copyLabels = setOf("copy", "copy response", "답변 복사", "복사")
    private val feedbackLabels = setOf("대답이 마음에 들어요", "대답이 마음에 들지 않아요", "good response", "bad response", "like response", "dislike response")

    fun isSendRole(node: GeminiUiNode) = role(node, sendIds, sendLabels)
    fun isStopRole(node: GeminiUiNode) = role(node, stopIds, stopLabels)
    fun isCopyRole(node: GeminiUiNode) = role(node, copyIds, copyLabels)
    fun isFeedbackRole(node: GeminiUiNode) = role(node, emptySet(), feedbackLabels)

    /** Keep distinct safe targets: the caller must require exactly one before ACTION_CLICK. */
    fun sendTargets(nodes: List<GeminiUiNode>): List<Int> {
        if (nodes.map { it.index }.distinct().size != nodes.size) return emptyList()
        val byIndex = nodes.associateBy { it.index }
        val children = nodes.groupBy { it.parent }
        fun eligible(node: GeminiUiNode, pkg: String) = node.packageName == pkg && pkg in packages &&
            node.visible && node.enabled && !node.editable && !node.password && !node.composer
        fun safeTarget(candidate: GeminiUiNode, label: GeminiUiNode, path: Set<Int>): Boolean {
            if (candidate.parent < 0 || !(candidate.clickable || candidate.clickAction)) return false
            val owner = byIndex[candidate.parent] ?: return false
            if (!eligible(owner, label.packageName)) return false
            if (candidate.className.substringAfterLast('.') in setOf("WebView", "ScrollView", "NestedScrollView", "RecyclerView", "ListView")) return false
            if (candidate.resourceId.substringAfterLast('/') in setOf("content", "root", "chat_container", "conversation_container", "page_container")) return false
            val subtree = mutableListOf<GeminiUiNode>()
            val seen = mutableSetOf<Int>()
            fun visit(node: GeminiUiNode, depth: Int): Boolean {
                if (depth > 3 || subtree.size >= 12 || !seen.add(node.index) || !eligible(node, label.packageName)) return false
                subtree.add(node)
                val descendants = children[node.index].orEmpty()
                if (descendants.size > 6) return false
                return descendants.all { visit(it, depth + 1) }
            }
            if (!visit(candidate, 0)) return false
            // Parent and its single child label may describe one control. Sibling labels are independent.
            val roles = subtree.filter(::isSendRole)
            val leafRoles = roles.filter { role ->
                roles.none { other -> other.index != role.index && isDescendant(other, role.index, byIndex) }
            }
            if (leafRoles.size != 1) return false
            // A wrapper containing a different clickable control is a group, not the send button.
            return subtree.none { it.index != candidate.index && it.index !in path && (it.clickable || it.clickAction) }
        }
        return nodes.filter { isSendRole(it) && eligible(it, it.packageName) }.mapNotNull { label ->
            var current = label
            val path = mutableSetOf(label.index)
            repeat(4) { distance ->
                if (!eligible(current, label.packageName)) return@mapNotNull null
                if (current.clickable || current.clickAction) {
                    return@mapNotNull current.index.takeIf { safeTarget(current, label, path) }
                }
                if (distance == 3) return@mapNotNull null
                val parent = byIndex[current.parent] ?: return@mapNotNull null
                if (!path.add(parent.index)) return@mapNotNull null
                current = parent
            }
            null
        }.distinct()
    }

    private fun isDescendant(node: GeminiUiNode, ancestor: Int, nodes: Map<Int, GeminiUiNode>): Boolean {
        var current = node
        val seen = mutableSetOf<Int>()
        repeat(4) {
            if (!seen.add(current.index)) return false
            if (current.parent == ancestor) return true
            current = nodes[current.parent] ?: return false
        }
        return false
    }
    private fun role(node: GeminiUiNode, ids: Set<String>, labels: Set<String>): Boolean =
        node.resourceId.substringAfterLast('/') in ids || listOf(node.text, node.description).any { it.trim().lowercase() in labels }
}
