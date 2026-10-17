package ai.closepaw.chatgpt

import ai.closepaw.app.AgentService
import ai.closepaw.util.recycleCompat
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Semantic-only adapter; unsupported accessibility layouts intentionally fail closed. */
internal class AndroidGeminiRoundTripUi(
    private val service: AgentService,
    private val allowed: () -> Boolean,
    private val draft: AndroidGeminiDraftUi = AndroidGeminiDraftUi(service, allowed),
) : GeminiRoundTripUi, GeminiDraftUi by draft {
    private var lastSnapshot: GeminiUiSnapshot? = null

    override suspend fun snapshot(): GeminiUiSnapshot = withContext(Dispatchers.Main) {
        if (!allowed() || draft.isLocked()) return@withContext GeminiUiSnapshot(false, "")
        withTree { nodes, complete -> observe(nodes, complete).also { lastSnapshot = it } }
    }

    override suspend fun sendIfMatches(text: String, targetKey: String): GeminiSendResult = withContext(Dispatchers.Main) {
        if (!allowed() || draft.isLocked()) return@withContext GeminiSendResult.TARGET_CHANGED
        withTree { nodes, complete ->
            val expected = lastSnapshot ?: return@withTree GeminiSendResult.TARGET_CHANGED
            val state = observe(nodes, complete)
            if (state.submittedQuestion != expected.submittedQuestion || state.replies != expected.replies) return@withTree GeminiSendResult.TARGET_CHANGED
            if (!state.foreground || state.targetKey != targetKey) return@withTree GeminiSendResult.TARGET_CHANGED
            if (!state.traversalComplete || state.composerCandidates != 1 || state.composerText != text) return@withTree GeminiSendResult.INPUT_CHANGED
            val send = nodes.filter { isSend(it) }.singleOrNull()
            val composer = nodes.filter { isUsableComposer(it) }.singleOrNull()
            if (send == null || composer == null || !state.sendEnabled || state.generating == true) return@withTree GeminiSendResult.REJECTED
            // Refresh retained nodes immediately before acting. No key events, coordinates or generic search buttons.
            if (!allowed() || !send.refresh() || !composer.refresh() || !isSend(send) || !send.isEnabled ||
                !isUsableComposer(composer) || composer.text?.toString().orEmpty() != text) return@withTree GeminiSendResult.INPUT_CHANGED
            // A fresh foreground acquisition detects a window/app switch without using a stale background node.
            val currentRoot = draft.foregroundRoot()
            val sameTarget = currentRoot?.let { key(it) == targetKey } == true
            currentRoot?.recycleCompat()
            if (!sameTarget || draft.isLocked() || !allowed()) return@withTree GeminiSendResult.TARGET_CHANGED
            if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) GeminiSendResult.CLICKED else GeminiSendResult.REJECTED
        }
    }

    private fun observe(nodes: List<AccessibilityNodeInfo>, complete: Boolean): GeminiUiSnapshot {
        val root = nodes.firstOrNull() ?: return GeminiUiSnapshot(false, "", traversalComplete = complete)
        val pkg = root.packageName?.toString()
        val composers = nodes.filter { isUsableComposer(it) }
        val recognized = GeminiDraftTargetPolicy.recognized(pkg, composers.size)
        if (!recognized) return GeminiUiSnapshot(false, key(root), traversalComplete = complete)
        val sends = nodes.filter { isSend(it) }
        val stop = nodes.any { semantic(it, GeminiRoundTripSemantics::isStop) }
        var replyTraversalComplete = true
        val replies = nodes.filter { node -> semantic(node) { id, _ -> GeminiRoundTripSemantics.isReply(id) } }.mapIndexedNotNull { index, node ->
            val (children, walkedComplete) = descendants(node)
            val subtreeComplete = walkedComplete && children.none { !it.isVisibleToUser && !it.text.isNullOrBlank() }
            replyTraversalComplete = replyTraversalComplete && subtreeComplete
            try {
                val content = node.text?.toString()?.takeIf(String::isNotBlank) ?: children.filter { !it.isEditable && it.isVisibleToUser && !it.isClickable }
                    .mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }.distinct().joinToString("\n").take(24000)
                if (content.length >= 24000) replyTraversalComplete = false
                if (content.isBlank()) null else GeminiReply(
                    // Stable within a conversation while allowing two identical answers as separate turns.
                    id = "${node.viewIdResourceName}:$index",
                    text = content,
                    complete = subtreeComplete && !stop && children.any { semantic(it, GeminiRoundTripSemantics::isCopy) },
                )
            } finally { children.drop(1).forEach { it.recycleCompat() } }
        }
        val questions = nodes.filter { semantic(it) { id, _ -> GeminiRoundTripSemantics.isQuestion(id) } }
        return GeminiUiSnapshot(
            foreground = true, targetKey = key(root), composerCandidates = composers.size,
            composerText = composers.singleOrNull()?.let { if (it.isShowingHintText) "" else it.text?.toString().orEmpty() }.orEmpty(),
            sendCandidates = sends.size, sendEnabled = sends.singleOrNull()?.isEnabled == true,
            generating = when { stop -> true; replies.lastOrNull()?.complete == true -> false; else -> null },
            submittedQuestion = questions.lastOrNull()?.text?.toString(), replies = replies, traversalComplete = complete && replyTraversalComplete,
        )
    }
    private fun key(node: AccessibilityNodeInfo) = "${node.packageName}:${node.windowId}"
    private fun isUsableComposer(node: AccessibilityNodeInfo) = node.isVisibleToUser && node.isEnabled && node.isEditable && !node.isPassword && draft.isComposer(node)
    private fun isSend(node: AccessibilityNodeInfo) = node.isVisibleToUser && !node.isEditable && node.isClickable &&
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } && semantic(node, GeminiRoundTripSemantics::isSend)
    private fun semantic(node: AccessibilityNodeInfo, check: (String, String) -> Boolean): Boolean = node.isVisibleToUser &&
        check(node.viewIdResourceName.orEmpty().substringAfterLast('/'), node.contentDescription?.toString().orEmpty())

    private suspend fun <T> withTree(block: suspend (List<AccessibilityNodeInfo>, Boolean) -> T): T {
        val root = draft.foregroundRoot() ?: return block(emptyList(), true)
        val nodes = mutableListOf(root)
        var complete = true
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || nodes.size >= 500) { complete = false; return }
            if (node.childCount > 100) complete = false
            for (i in 0 until node.childCount.coerceAtMost(100)) {
                if (nodes.size >= 500) { complete = false; break }
                val child = node.getChild(i) ?: continue
                nodes.add(child); visit(child, depth + 1)
            }
        }
        try { visit(root, 0); return block(nodes.filter { it.packageName == root.packageName }, complete) }
        finally { nodes.forEach { it.recycleCompat() } }
    }
    private fun descendants(root: AccessibilityNodeInfo): Pair<List<AccessibilityNodeInfo>, Boolean> {
        val nodes = mutableListOf(root)
        var complete = true
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || nodes.size >= 300) { complete = false; return }
            if (node.childCount > 100) complete = false
            for (i in 0 until node.childCount.coerceAtMost(100)) {
                if (nodes.size >= 300) { complete = false; break }
                val child = node.getChild(i) ?: continue
                nodes.add(child); visit(child, depth + 1)
            }
        }
        visit(root, 0); return nodes to complete
    }
}

/** Exact roles, never generic Search labels. Unknown versions expose no supported response. */
internal object GeminiRoundTripSemantics {
    fun isSend(id: String, description: String) = id in setOf("assistant_robin_send_button", "robin_send_button", "send_button") ||
        description.trim().lowercase() in setOf("send message", "send prompt", "메시지 보내기", "프롬프트 보내기", "보내기", "send")
    fun isStop(id: String, description: String) = id in setOf("assistant_robin_stop_button", "stop_generating_button") ||
        description.trim().lowercase() in setOf("stop response", "stop generating", "답변 중지", "생성 중지")
    fun isCopy(id: String, description: String) = id in setOf("assistant_robin_copy_button", "copy_response_button") ||
        description.trim().lowercase() in setOf("copy response", "답변 복사")
    fun isReply(id: String) = id in setOf("assistant_robin_response_text", "robin_response_text", "assistant_robin_response_container")
    fun isQuestion(id: String) = id in setOf("assistant_robin_query_text", "robin_query_text", "user_query")
}
