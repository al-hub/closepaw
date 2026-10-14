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
import android.view.accessibility.AccessibilityWindowInfo
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
    private var lastEvidence = DraftWindowEvidence()
    override fun evidence() = lastEvidence
    override var launchFailureReason: String = "target_not_foreground"
        private set
    override suspend fun launch(): Boolean {
        if (isLocked()) { launchFailureReason = "device_locked"; return false }
        if (inspect().foreground) return true
        if (platform.launchApp(PACKAGE) is ActionResult.Failure) {
            launchFailureReason = "app_launch_failed"
            return false
        }
        repeat(20) {
            delay(150)
            if (inspect().foreground) { delay(200); return true }
        }
        launchFailureReason = when (lastEvidence.reason) {
            "active_root_missing" -> "active_root_missing"
            "google_host_unverified" -> "gemini_host_unverified"
            else -> "foreground_timeout"
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
                !node.refresh() || node.packageName?.toString() != lastEvidence.rootPackage || !node.isVisibleToUser || !node.isEditable || !node.isEnabled || node.isPassword || !isComposer(node) -> DraftWriteResult.TARGET_CHANGED
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
        val root = foregroundRoot() ?: return block(emptyList(), false)
        val owned = mutableListOf<AccessibilityNodeInfo>()
        try {
            val pkg = root.packageName?.toString()
            if (pkg !in setOf(PACKAGE, GOOGLE_HOST)) {
                lastEvidence = lastEvidence.copy(reason = "wrong_foreground")
                return block(emptyList(), false)
            }
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            var count = 0
            var incomplete = false
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (++count > 500 || depth > 40) { incomplete = true; return }
                if (node.childCount > 100) incomplete = true
                if (node.packageName?.toString() == pkg && node.isEditable && node.isVisibleToUser && node.isEnabled && !node.isPassword &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } && isComposer(node)) candidates.add(node)
                for (i in 0 until node.childCount.coerceAtMost(100)) {
                    val child = node.getChild(i) ?: continue
                    owned.add(child); visit(child, depth + 1)
                }
            }
            visit(root, 0)
            // An incomplete traversal cannot establish uniqueness.
            if (incomplete) {
                lastEvidence = lastEvidence.copy(reason = "traversal_incomplete")
                return block(emptyList(), pkg == PACKAGE)
            }
            // Google Search can host Gemini. A generic Google search box is never a target.
            val recognized = GeminiDraftTargetPolicy.recognized(pkg, candidates.size)
            lastEvidence = lastEvidence.copy(reason = when {
                !recognized -> "google_host_unverified"
                candidates.isEmpty() -> "composer_missing"
                candidates.size > 1 -> "composer_ambiguous"
                else -> "gemini_composer"
            })
            return block(candidates, recognized)
        } finally {
            owned.forEach { it.recycleCompat() }; root.recycleCompat()
        }
    }
    /** Use one foreground root for launch, selection, guarded write and verification.
     * Active IME roots may be replaced only by an active/focused APPLICATION window.
     * Never select a background Gemini window just because it exists.
     */
    private fun foregroundRoot(): AccessibilityNodeInfo? {
        val observed = platform.getCurrentPackageName()
        val activeRoot = service.rootInActiveWindow
        val windows = service.windows.orEmpty()
        try {
            val activeWindow = windows.firstOrNull { it.id == activeRoot?.windowId }
            val activeIsIme = activeWindow?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            if (activeRoot != null && !activeIsIme) {
                lastEvidence = DraftWindowEvidence(observed, activeRoot.packageName?.toString(),
                    activeRoot.windowId, activeWindow?.type, activeWindow?.isActive, activeWindow?.isFocused, "active_root")
                return activeRoot
            }
            activeRoot?.recycleCompat()
            val appWindow = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
                .maxByOrNull { it.layer }
            val root = appWindow?.root
            lastEvidence = DraftWindowEvidence(observed, root?.packageName?.toString(), root?.windowId,
                appWindow?.type, appWindow?.isActive, appWindow?.isFocused,
                if (root == null) "active_root_missing" else "foreground_application")
            return root
        } finally { windows.forEach { runCatching { it.recycle() } } }
    }
    private fun isComposer(node: AccessibilityNodeInfo): Boolean {
        val hint = node.hintText?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        return GeminiDraftComposerPolicy.isComposer(hint, description, id, node.packageName?.toString() == GOOGLE_HOST)
    }
    companion object {
        const val PACKAGE = "com.google.android.apps.bard"
        const val GOOGLE_HOST = "com.google.android.googlequicksearchbox"
    }
}

internal object GeminiDraftComposerPolicy {
    fun isComposer(hint: String, description: String, resourceId: String, requireGeminiIdentity: Boolean = false): Boolean {
        if (requireGeminiIdentity) return listOf("Gemini에게 물어보", "Ask Gemini").any {
            hint.contains(it, true) || description.contains(it, true)
        } || resourceId in setOf("assistant_robin_input_text", "robin_input_text")
        val labels = listOf("Gemini에게 물어보", "무엇이든 물어보", "Ask Gemini", "Enter a prompt")
        return labels.any { hint.contains(it, true) || description.contains(it, true) } ||
            resourceId in setOf("assistant_robin_input_text", "robin_input_text", "prompt_input")
    }
}

internal object GeminiDraftTargetPolicy {
    fun recognized(packageName: String?, geminiComposers: Int): Boolean =
        packageName == AndroidGeminiDraftUi.PACKAGE ||
            (packageName == AndroidGeminiDraftUi.GOOGLE_HOST && geminiComposers > 0)
}
