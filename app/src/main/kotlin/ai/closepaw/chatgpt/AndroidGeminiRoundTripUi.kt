package ai.closepaw.chatgpt

import ai.closepaw.app.AgentService
import ai.closepaw.util.recycleCompat
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Scoped semantic controls; UI text stays invocation-local and never enters diagnostics. */
internal class AndroidGeminiRoundTripUi(
    private val service: AgentService,
    private val allowed: () -> Boolean,
    private val draft: AndroidGeminiDraftUi = AndroidGeminiDraftUi(service, allowed),
) : GeminiRoundTripUi, GeminiDraftUi by draft {
    private var pagingContainerKey: String? = null
    private var expectedQuestion: String? = null
    private var lastSnapshot: GeminiUiSnapshot? = null
    private var lastInspection = GeminiUiSnapshot(false, "")
    private var lastNodes: List<GeminiUiNode> = emptyList()

    override suspend fun setIfEmpty(text: String): DraftWriteResult {
        val result = draft.setIfEmpty(text)
        if (result == DraftWriteResult.WRITTEN) { expectedQuestion = text; pagingContainerKey = null }
        return result
    }

    override suspend fun snapshot(): GeminiUiSnapshot = withContext(Dispatchers.Main) {
        if (!allowed() || draft.isLocked()) {
            lastNodes = emptyList(); lastInspection = GeminiUiSnapshot(false, "")
            return@withContext lastInspection
        }
        withTree { _, nodes, complete -> observe(nodes, complete).also { lastSnapshot = it } }
    }

    override suspend fun scrollReply(targetKey: String, containerKey: String, forward: Boolean): Boolean = withContext(Dispatchers.Main) {
        if (!allowed() || draft.isLocked() || pagingContainerKey != containerKey) return@withContext false
        withTree { handles, nodes, complete ->
            val state = observe(nodes, complete)
            if (!state.foreground || state.targetKey != targetKey || state.composerText.isNotBlank() || state.generating == true)
                return@withTree false
            val index = GeminiReplyPageParser.target(nodes, expectedQuestion, complete, containerKey, forward)
                ?: return@withTree false
            val node = handles[index]
            if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || !allowed() || draft.isLocked()) return@withTree false
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.actionList.none { it.id == action }) return@withTree false
            node.performAction(action)
        }
    }

    fun metadataSnapshot(): JsonObject = GeminiUiMetadata.build(lastNodes, draft.evidence(), lastInspection)

    override suspend fun sendIfMatches(text: String, targetKey: String): GeminiSendResult = withContext(Dispatchers.Main) {
        if (!allowed() || draft.isLocked()) return@withContext GeminiSendResult.TARGET_CHANGED
        val expected = lastSnapshot ?: return@withContext GeminiSendResult.TARGET_CHANGED
        withTree outer@{ handles, nodes, complete ->
            val state = observe(nodes, complete)
            if (!sameConversation(state, expected) || !state.foreground || state.targetKey != targetKey)
                return@outer GeminiSendResult.TARGET_CHANGED
            if (!validInput(state, text)) return@outer GeminiSendResult.INPUT_CHANGED
            if (state.generating == true) return@outer GeminiSendResult.REJECTED
            val targetIndex = GeminiUiControlResolver.sendTargets(nodes).singleOrNull()
                ?: return@outer GeminiSendResult.REJECTED
            val target = handles.getOrNull(targetIndex) ?: return@outer GeminiSendResult.REJECTED
            val composerIndex = nodes.singleOrNull { it.composer && it.packageName == nodes.first().packageName }?.index
                ?: return@outer GeminiSendResult.INPUT_CHANGED
            val composer = handles[composerIndex]
            if (!target.refresh() || !composer.refresh() || !target.isEnabled || !allowed())
                return@outer GeminiSendResult.INPUT_CHANGED
            // Reacquire the complete semantic path, including a child label, immediately before the one click.
            withTree fresh@{ currentHandles, currentNodes, currentComplete ->
                val current = observe(currentNodes, currentComplete)
                if (!current.foreground || current.targetKey != targetKey || !sameConversation(current, expected))
                    return@fresh GeminiSendResult.TARGET_CHANGED
                if (!validInput(current, text)) return@fresh GeminiSendResult.INPUT_CHANGED
                if (current.generating == true) return@fresh GeminiSendResult.REJECTED
                val freshIndex = GeminiUiControlResolver.sendTargets(currentNodes).singleOrNull()
                    ?: return@fresh GeminiSendResult.REJECTED
                val freshTarget = currentHandles[freshIndex]
                if (freshTarget != target || !freshTarget.isEnabled || draft.isLocked() || !allowed())
                    return@fresh GeminiSendResult.TARGET_CHANGED
                if (freshTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) GeminiSendResult.CLICKED
                else GeminiSendResult.REJECTED
            }
        }
    }

    private fun sameConversation(a: GeminiUiSnapshot, b: GeminiUiSnapshot) =
        a.submittedQuestion == b.submittedQuestion && a.replies == b.replies
    private fun validInput(state: GeminiUiSnapshot, text: String) = state.traversalComplete &&
        state.composerCandidates == 1 && state.composerText == text

    private fun observe(nodes: List<GeminiUiNode>, complete: Boolean): GeminiUiSnapshot {
        lastNodes = nodes
        val root = nodes.firstOrNull()
        if (root == null) return GeminiUiSnapshot(false, "", traversalComplete = complete).also { lastInspection = it }
        val composers = nodes.filter { it.composer && it.packageName == root.packageName }
        val recognized = GeminiDraftTargetPolicy.recognized(root.packageName, composers.size)
        if (!recognized) return GeminiUiSnapshot(false, key(root), traversalComplete = complete).also { lastInspection = it }
        val sends = GeminiUiControlResolver.sendTargets(nodes)
        val conversation = GeminiConversationParser.parse(nodes, expectedQuestion, complete)
        val page = GeminiReplyPageParser.parse(nodes, expectedQuestion, complete, pagingContainerKey)
        if (page != null) pagingContainerKey = page.containerKey
        return GeminiUiSnapshot(
            replyPage = page,
            // Exact visible question + complete response/footer already proves a short turn's scope.
            // An unrelated or composer-containing scroller must not downgrade that verified reply.
            replyCoverageComplete = page != null || (conversation.submittedQuestion == expectedQuestion &&
                conversation.replies.isNotEmpty() && conversation.replies.all { it.complete } && conversation.traversalComplete) || nodes.none { it.visible && it.scrollable &&
                (it.scrollForward || it.scrollBackward) && !it.className.contains("Horizontal", true) },
            foreground = true, targetKey = key(root), composerCandidates = composers.size,
            composerText = composers.singleOrNull()?.let { if (it.showingHint) "" else it.text }.orEmpty(),
            sendCandidates = sends.size, sendEnabled = sends.singleOrNull()?.let { nodes[it].enabled } == true,
            generating = conversation.generating, submittedQuestion = conversation.submittedQuestion,
            replies = conversation.replies, traversalComplete = conversation.traversalComplete,
            observedTextEvidence = nodes.filter { node ->
                node.visible && !node.editable && !node.password && !node.composer &&
                    node.packageName == root.packageName && generateSequence(node) { current ->
                        nodes.getOrNull(current.parent)
                    }.take(42).none { it.editable || it.password || it.composer }
            }.map { it.text.ifBlank { it.description }.trim() }.filter { it.isNotEmpty() }
                .map { GeminiUiTextEvidence.hash(it) }.toSet(),
        ).also { lastInspection = it }
    }
    private fun key(node: GeminiUiNode) = "${node.packageName}:$lastWindowId"
    private var lastWindowId = -1
    private fun isComposer(node: AccessibilityNodeInfo) = node.isVisibleToUser && node.isEnabled && node.isEditable &&
        !node.isPassword && draft.isComposer(node)

    private suspend fun <T> withTree(block: suspend (List<AccessibilityNodeInfo>, List<GeminiUiNode>, Boolean) -> T): T {
        val root = draft.foregroundRoot() ?: return block(emptyList(), emptyList(), true)
        lastWindowId = root.windowId
        val handles = mutableListOf(root)
        val parents = mutableListOf(-1)
        var complete = true
        fun visit(node: AccessibilityNodeInfo, depth: Int, parent: Int) {
            if (depth > 40 || handles.size >= 500) { complete = false; return }
            if (node.childCount > 100) complete = false
            for (i in 0 until node.childCount.coerceAtMost(100)) {
                if (handles.size >= 500) { complete = false; break }
                val child = node.getChild(i) ?: continue
                val index = handles.size
                handles.add(child); parents.add(parent); visit(child, depth + 1, index)
            }
        }
        try {
            visit(root, 0, 0)
            val nodes = handles.mapIndexed { index, node ->
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                GeminiUiNode(
                index = index, parent = parents[index], packageName = node.packageName?.toString().orEmpty(),
                resourceId = node.viewIdResourceName.orEmpty(), className = node.className?.toString().orEmpty(),
                text = node.text?.toString().orEmpty(), description = node.contentDescription?.toString().orEmpty(),
                visible = node.isVisibleToUser, enabled = node.isEnabled, editable = node.isEditable,
                password = node.isPassword, clickable = node.isClickable,
                clickAction = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
                composer = isComposer(node), showingHint = node.isShowingHintText,
                scrollable = node.isScrollable,
                scrollForward = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD },
                scrollBackward = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD },
                screenTop = bounds.takeUnless { it.isEmpty }?.top,
                screenLeft = bounds.takeUnless { it.isEmpty }?.left,
            ) }
            return block(handles, nodes, complete)
        } finally { handles.forEach { it.recycleCompat() } }
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
