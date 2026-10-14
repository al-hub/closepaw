package ai.closepaw.chatgpt

import ai.closepaw.app.AgentService
import ai.closepaw.platform.AccessibilityPlatform
import ai.closepaw.platform.ActionResult
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.util.recycleCompat
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Main-thread, native Gemini-only ACTION_SET_TEXT adapter. Never falls back to coordinate taps. */
internal class AndroidGeminiDraftUi(private val service: AgentService, private val allowed: () -> Boolean) : GeminiDraftUi {
    private val platform = AccessibilityPlatform(service, SessionConfig())
    override suspend fun isLocked(): Boolean = withContext(Dispatchers.Main) {
        service.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !service.getSystemService(PowerManager::class.java).isInteractive
    }
    override suspend fun launch(): Boolean {
        if (isLocked()) return false
        if (withContext(Dispatchers.Main) { platform.getCurrentPackageName() } != PACKAGE) {
            if (platform.launchApp(PACKAGE) is ActionResult.Failure) return false
        }
        repeat(12) {
            if (withContext(Dispatchers.Main) { platform.getCurrentPackageName() } == PACKAGE) {
                delay(350)
                return true
            }
            delay(150)
        }
        return false
    }
    override suspend fun inspect(): DraftComposerState = withContext(Dispatchers.Main) {
        withComposers { nodes, foreground -> DraftComposerState(foreground, nodes.size, nodes.singleOrNull()?.let { if (it.isShowingHintText) "" else it.text?.toString().orEmpty() }.orEmpty()) }
    }
    override suspend fun setIfEmpty(text: String): DraftWriteResult = withContext(Dispatchers.Main) {
        if (!allowed() || isLocked()) return@withContext DraftWriteResult.TARGET_CHANGED
        withComposers { nodes, foreground ->
            val node = nodes.singleOrNull()
            when {
                !foreground || node == null -> DraftWriteResult.TARGET_CHANGED
                !node.refresh() || node.packageName?.toString() != PACKAGE || !node.isVisibleToUser || !node.isEditable || !node.isEnabled || node.isPassword -> DraftWriteResult.TARGET_CHANGED
                node.text?.isNotBlank() == true && !node.isShowingHintText -> DraftWriteResult.EXISTING_DRAFT
                else -> {
                    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                    if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) DraftWriteResult.WRITTEN else DraftWriteResult.REJECTED
                }
            }
        }
    }
    override suspend fun matches(text: String): Boolean {
        repeat(5) {
            delay(150)
            if (isLocked()) return false
            val state = inspect()
            if (!state.foreground) return false
            if (state.candidates == 1 && state.text == text) return true
        }
        return false
    }
    private fun <T> withComposers(block: (List<AccessibilityNodeInfo>, Boolean) -> T): T {
        if (platform.getCurrentPackageName() != PACKAGE) return block(emptyList(), false)
        val root = service.rootInActiveWindow ?: return block(emptyList(), false)
        val owned = mutableListOf<AccessibilityNodeInfo>()
        try {
            if (root.packageName?.toString() != PACKAGE) return block(emptyList(), false)
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            var count = 0
            var incomplete = false
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (++count > 500 || depth > 40) { incomplete = true; return }
                if (node.childCount > 100) incomplete = true
                if (node.packageName?.toString() == PACKAGE && node.isEditable && node.isVisibleToUser && node.isEnabled && !node.isPassword &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } && isComposer(node)) candidates.add(node)
                for (i in 0 until node.childCount.coerceAtMost(100)) {
                    val child = node.getChild(i) ?: continue
                    owned.add(child); visit(child, depth + 1)
                }
            }
            visit(root, 0)
            // An incomplete traversal cannot establish uniqueness.
            if (incomplete) return block(emptyList(), true)
            return block(candidates, true)
        } finally {
            owned.forEach { it.recycleCompat() }; root.recycleCompat()
        }
    }
    private fun isComposer(node: AccessibilityNodeInfo): Boolean {
        val hint = node.hintText?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        return GeminiDraftComposerPolicy.isComposer(hint, description, id)
    }
    companion object { const val PACKAGE = "com.google.android.apps.bard" }
}

internal object GeminiDraftComposerPolicy {
    fun isComposer(hint: String, description: String, resourceId: String): Boolean {
        val labels = listOf("Gemini에게 물어보", "무엇이든 물어보", "Ask Gemini", "Enter a prompt")
        return labels.any { hint.contains(it, true) || description.contains(it, true) } ||
            resourceId in setOf("assistant_robin_input_text", "robin_input_text", "prompt_input")
    }
}
