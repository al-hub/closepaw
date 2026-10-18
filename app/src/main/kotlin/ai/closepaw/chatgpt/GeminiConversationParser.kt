package ai.closepaw.chatgpt

import java.security.MessageDigest

internal data class GeminiConversationObservation(
    val submittedQuestion: String? = null,
    val replies: List<GeminiReply> = emptyList(),
    val generating: Boolean? = null,
    val traversalComplete: Boolean = true,
)

/** Pure, invocation-local parsing. Generic text requires a submitted-question and response-toolbar boundary. */
internal object GeminiConversationParser {
    private const val MAX_ANSWER_CHARS = 24_000
    private val replyIds = setOf("assistant_robin_response_text", "robin_response_text", "assistant_robin_response_container")
    private val questionIds = setOf("assistant_robin_query_text", "robin_query_text", "user_query", "assistant_robin_user_message_text")
    private val positive = setOf("대답이 마음에 들어요", "good response", "like response")
    private val negative = setOf("대답이 마음에 들지 않아요", "bad response", "dislike response")
    private val toolbarLabels = positive + negative + setOf(
        "복사", "copy", "copy response", "답변 복사", "옵션 다시 실행", "다시 실행", "공유", "옵션 더보기", "듣기",
        "retry", "regenerate", "share", "more options", "listen", "read aloud",
    )

    fun parse(nodes: List<GeminiUiNode>, expectedQuestion: String?, complete: Boolean): GeminiConversationObservation {
        if (nodes.isEmpty()) return GeminiConversationObservation(traversalComplete = complete)
        if (nodes.map { it.index }.distinct().size != nodes.size) return GeminiConversationObservation(traversalComplete = false)
        val tree = Tree(nodes)
        val stop = nodes.any { tree.safe(it) && GeminiUiControlResolver.isStopRole(it) }
        val exactQuestions = if (expectedQuestion.isNullOrBlank()) emptyList() else nodes.filter {
            tree.questionCandidate(it) && it.bodyText == expectedQuestion
        }.let(tree::outermost)
        if (exactQuestions.size > 1) return GeminiConversationObservation(generating = if (stop) true else null, traversalComplete = false)
        val exact = exactQuestions.singleOrNull()
        val explicitQuestions = tree.outermost(nodes.filter { tree.questionCandidate(it) && it.shortId in questionIds })
        val lastExplicit = explicitQuestions.lastOrNull()
        // A newer known user turn must remain visible to the collector as a target change.
        val anchor = if (exact != null && lastExplicit != null && tree.position(lastExplicit) > tree.position(exact)) lastExplicit else exact
        val submitted = anchor?.bodyText ?: lastExplicit?.bodyText
        val explicit = explicitReplies(tree, anchor, submitted, complete, stop)
        if (explicit.replies.isNotEmpty()) return explicit
        if (anchor == null || anchor.bodyText != expectedQuestion) {
            return explicit.copy(submittedQuestion = submitted)
        }
        return genericReply(tree, anchor, anchor.bodyText, complete, stop)
    }

    private fun explicitReplies(tree: Tree, anchor: GeminiUiNode?, submitted: String?, complete: Boolean, stop: Boolean): GeminiConversationObservation {
        val roots = tree.outermost(tree.nodes.filter { tree.safe(it) && it.shortId in replyIds &&
            (anchor == null || tree.position(it) > tree.position(anchor)) })
        var full = complete
        val replies = roots.mapIndexedNotNull { ordinal, root ->
            val scope = tree.nodes.filter { it.index == root.index || tree.descendant(it, root.index) }
            val hidden = scope.any { it.bodyText.isNotBlank() && !it.editable && !tree.safe(it) }
            val fragments = textFragments(tree, scope.filter { tree.safeText(it) && !it.clickable && !it.clickAction && !isToolbar(it) })
            val raw = fragments.joinToString("\n")
            val scopedComplete = complete && !hidden && raw.length <= MAX_ANSWER_CHARS
            if (!scopedComplete) full = false
            if (raw.isBlank()) null else GeminiReply(
                id = "explicit:${digest(submitted.orEmpty())}:${root.shortId}:$ordinal",
                text = raw.take(MAX_ANSWER_CHARS),
                complete = scopedComplete && !stop && scope.any { tree.safe(it) && GeminiUiControlResolver.isCopyRole(it) },
                textEvidence = fragments.map(GeminiUiTextEvidence::hash).toSet(),
            )
        }
        return GeminiConversationObservation(submitted, replies,
            when { stop -> true; replies.lastOrNull()?.complete == true -> false; else -> null }, full)
    }

    private fun genericReply(tree: Tree, anchor: GeminiUiNode, question: String, complete: Boolean, stop: Boolean): GeminiConversationObservation {
        fun empty() = GeminiConversationObservation(question, generating = if (stop) true else null, traversalComplete = complete)
        val after = tree.nodes.drop(tree.position(anchor) + 1).filterNot { tree.descendant(it, anchor.index) }
        // Composer ends the conversation scope. Navigation/model chrome following it is unrelated.
        val composerBoundary = after.indexOfFirst { it.composer || it.editable }.let { if (it < 0) after.size else it }
        val boundary = after.take(composerBoundary).indexOfFirst { isDisclaimer(it.bodyText) }.let {
            if (it < 0) composerBoundary else it
        }
        val scope = after.take(boundary)
        val positiveTargets = scope.filter { tree.safe(it) && labels(it).any(positive::contains) }.mapNotNull { tree.actionTarget(it) }.distinct()
        val negativeTargets = scope.filter { tree.safe(it) && labels(it).any(negative::contains) }.mapNotNull { tree.actionTarget(it) }.distinct()
        val copyTargets = scope.filter { tree.safe(it) && GeminiUiControlResolver.isCopyRole(it) }.mapNotNull { tree.actionTarget(it) }.distinct()
        if (positiveTargets.size != 1 || negativeTargets.size != 1 || copyTargets.size != 1) return empty()
        if ((positiveTargets + negativeTargets + copyTargets).distinct().size != 3) return empty()
        val controls = scope.filter { tree.safe(it) && isToolbar(it) }
        val toolbarStart = controls.indexOfFirst { labels(it).any(positive::contains) || labels(it).any(negative::contains) || GeminiUiControlResolver.isCopyRole(it) }
            .takeIf { it >= 0 }?.let { controls[it] } ?: return empty()
        val targets = (positiveTargets + negativeTargets + copyTargets).toSet()
        val startPosition = minOf(tree.position(toolbarStart), tree.nodes.filter { it.index in targets }.minOf { tree.position(it) })
        val textScope = scope.filter { tree.position(it) < startPosition }
        // Never absorb popup/model menus or unknown actionable elements into a response.
        if (textScope.any { tree.safe(it) && isMenu(it) }) return empty()
        if (textScope.any { tree.safe(it) && (it.clickable || it.clickAction) && !isToolbar(it) }) return empty()
        // Another question/answer after the first toolbar would make a generic turn boundary ambiguous.
        if (scope.any { tree.position(it) >= startPosition && tree.safeText(it) && !isToolbar(it) && !tree.descendantOfAny(it, controls) }) return empty()
        // Between a footer and composer accept only known footer/attachment controls. A later turn
        // in this interval remains ambiguous; text beyond composer is never response evidence.
        if (after.take(composerBoundary).drop(boundary).any { tree.safeText(it) && !isDisclaimer(it.bodyText) &&
                !isFooterControl(it) && !GeminiUiControlResolver.isStopRole(it) }) return empty()
        val hidden = textScope.any { it.bodyText.isNotBlank() && !it.editable && !tree.safe(it) }
        val fragments = textFragments(tree, textScope.filter { tree.safeText(it) && !isToolbar(it) && !isDisclaimer(it.bodyText) })
        val raw = fragments.joinToString("\n")
        if (raw.isBlank()) return empty()
        val full = complete && !hidden && raw.length <= MAX_ANSWER_CHARS
        return GeminiConversationObservation(question, listOf(GeminiReply(
            "toolbar:${digest(question)}", raw.take(MAX_ANSWER_CHARS), full && !stop,
            textEvidence = fragments.map(GeminiUiTextEvidence::hash).toSet(),
        )), if (stop) true else if (full) false else null, full)
    }

    private fun textFragments(tree: Tree, candidates: List<GeminiUiNode>): List<String> {
        val selected = mutableListOf<GeminiUiNode>()
        for (node in candidates) if (selected.none { tree.descendant(node, it.index) }) selected.add(node)
        return selected.map { it.bodyText }
    }
    private fun labels(node: GeminiUiNode) = listOf(node.text, node.description).map { it.trim().lowercase() }
    private fun isToolbar(node: GeminiUiNode) = labels(node).any(toolbarLabels::contains) || GeminiUiControlResolver.isCopyRole(node) || GeminiUiControlResolver.isStopRole(node)
    private fun isFooterControl(node: GeminiUiNode) = labels(node).any { it in setOf(
        "첨부파일 추가", "첨부 파일 추가", "add files", "add attachment", "마이크", "microphone", "음성 입력",
        "open gemini live", "gemini live 열기",
    ) }
    private fun isDisclaimer(text: String) = text.startsWith("Gemini는 AI이며 실수를 할 수 있습니다.") ||
        text.startsWith("Gemini can make mistakes") || text.startsWith("Gemini may display inaccurate info")
    private fun isMenu(node: GeminiUiNode): Boolean {
        val id = node.shortId.lowercase()
        val klass = node.className.lowercase()
        return "menu" in id || "model" in id || "picker" in id || "popup" in id || "menu" in klass || "dialog" in klass
    }
    private fun digest(question: String): String = MessageDigest.getInstance("SHA-256").digest(question.toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    // Android may expose a noneditable paragraph only through contentDescription. Never let a
    // conflicting description replace real text, and never concatenate both representations.
    private val GeminiUiNode.bodyText get() = text.takeIf { it.isNotBlank() } ?: description
    private val GeminiUiNode.shortId get() = resourceId.substringAfterLast('/')

    private class Tree(val nodes: List<GeminiUiNode>) {
        private val byIndex = nodes.associateBy { it.index }
        private val positions = nodes.mapIndexed { position, node -> node.index to position }.toMap()
        private val pkg = nodes.first().packageName
        fun position(node: GeminiUiNode) = positions.getValue(node.index)
        fun safe(node: GeminiUiNode): Boolean {
            var current = node
            val seen = mutableSetOf<Int>()
            repeat(60) {
                if (!seen.add(current.index) || !current.visible || !current.enabled || current.password || current.packageName != pkg) return false
                if (current.parent < 0) return true
                current = byIndex[current.parent] ?: return false
            }
            return false
        }
        fun safeText(node: GeminiUiNode) = safe(node) && !node.editable && !node.composer && !node.showingHint && node.bodyText.isNotBlank() &&
            !ancestors(node).any { it.composer || it.editable || it.password }
        fun questionCandidate(node: GeminiUiNode) = safeText(node) && !isMenu(node) && !ancestors(node).any { isMenu(it) } &&
            (node.shortId in questionIds || (!isToolbar(node) && !isFooterControl(node) && !GeminiUiControlResolver.isSendRole(node)))
        fun descendant(node: GeminiUiNode, ancestor: Int) = ancestors(node).any { it.index == ancestor }
        fun descendantOfAny(node: GeminiUiNode, ancestors: List<GeminiUiNode>) = ancestors.any { descendant(node, it.index) }
        fun outermost(candidates: List<GeminiUiNode>) = candidates.filter { node -> candidates.none { it.index != node.index && descendant(node, it.index) } }
        private fun ancestors(node: GeminiUiNode): List<GeminiUiNode> {
            val result = mutableListOf<GeminiUiNode>(); var current = node
            val seen = mutableSetOf(node.index)
            repeat(60) {
                val parent = byIndex[current.parent] ?: return result
                if (!seen.add(parent.index)) return result
                result.add(parent); current = parent
            }
            return result
        }
        fun actionTarget(label: GeminiUiNode): Int? {
            var current = label
            repeat(4) {
                if (!safe(current) || current.composer || current.editable || isMenu(current)) return null
                if ((current.clickable || current.clickAction) && current.parent >= 0) return current.index
                current = byIndex[current.parent] ?: return null
            }
            return null
        }
    }
}
