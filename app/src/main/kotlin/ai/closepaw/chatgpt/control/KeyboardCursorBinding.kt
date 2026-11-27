package ai.closepaw.chatgpt.control

import ai.closepaw.app.AgentService
import ai.closepaw.platform.GuardedAccessibilityInputMethod
import ai.closepaw.platform.InputTypePrivacyPolicy
import android.graphics.Rect
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** For an already authorized keyboard operation, use only a bounded native
 * read. Never set selection or commit text to establish a caret. */
internal object KeyboardCursorBinding {
    suspend fun prove(service: AgentService, field: ControlNode, node: AccessibilityNodeInfo): Long? =
        proveDetailed(service, field, node).generation

    /**
     * Bounded single-pass native read classifying exact cursor-proof failure cause
     * without hidden retries. Any recovery must go through the outer bounded orchestrator.
     */
    suspend fun proveDetailed(service: AgentService, field: ControlNode, node: AccessibilityNodeInfo): CursorProofOutcome {
        if (Build.VERSION.SDK_INT < 33 || !KeyboardCursorProof.needsProof(field)) return CursorProofOutcome()
        val input = service.inputMethod as? GuardedAccessibilityInputMethod
            ?: return CursorProofOutcome(failure = CursorProofFailure.UNAVAILABLE)
        val generation = input.generation
        val editor = input.currentInputEditorInfo
            ?: return CursorProofOutcome(failure = CursorProofFailure.UNAVAILABLE)
        if (!matchesEditor(service, input, editor, generation, field) || !sameFocusedNode(service, field, node)) {
            return CursorProofOutcome(failure = CursorProofFailure.EDITOR_MISMATCH)
        }
        val screen = service.remoteControlChanges.version()
        val connection = input.currentInputConnection
            ?: return CursorProofOutcome(failure = CursorProofFailure.UNAVAILABLE)
        val sample = withContext(Dispatchers.IO) {
            try { connection.getSurroundingText(1, 1, 0) } catch (_: RuntimeException) { null }
        } ?: return CursorProofOutcome(failure = CursorProofFailure.IPC_NULL)
        if (!KeyboardCursorProof.provesEmpty(
                sample.text.length, sample.selectionStart, sample.selectionEnd, sample.offset,
            )) {
            return CursorProofOutcome(failure = CursorProofFailure.NONEMPTY_OR_SELECTION)
        }
        // Check editor/node identity before remoteControlChanges so a real editor switch is never
        // misclassified as a benign SCREEN_CHANGE_RACE.
        if (!matchesEditor(service, input, editor, generation, field) || !sameFocusedNode(service, field, node)) {
            return CursorProofOutcome(failure = CursorProofFailure.EDITOR_MISMATCH)
        }
        if (service.remoteControlChanges.version() != screen) {
            return CursorProofOutcome(failure = CursorProofFailure.SCREEN_CHANGE_RACE)
        }
        return CursorProofOutcome(generation = generation, failure = null)
    }

    /** Called without suspension at the gesture boundary. A proven empty
     * buffer cannot move its caret while remaining the same empty buffer. */
    fun recheck(service: AgentService, field: ControlNode, live: ControlNode): ControlNode {
        val generation = field.keyboardCursorGeneration ?: return live
        if (Build.VERSION.SDK_INT < 33 || !KeyboardCursorProof.needsProof(live)) return live
        val input = service.inputMethod as? GuardedAccessibilityInputMethod ?: return live
        val editor = input.currentInputEditorInfo ?: return live
        if (!matchesEditor(service, input, editor, generation, live)) return live
        return live.copy(selectionStart = 0, selectionEnd = 0, keyboardCursorGeneration = generation)
    }

    @RequiresApi(33)
    private fun matchesEditor(service: AgentService, input: GuardedAccessibilityInputMethod,
        editor: EditorInfo, generation: Long, field: ControlNode): Boolean {
        if (input.generation != generation || !input.currentInputStarted || input.currentInputEditorInfo !== editor ||
            editor.packageName != field.sourcePackage || editor.fieldId <= 0 ||
            (editor.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT ||
            InputTypePrivacyPolicy.password(editor.inputType) || input.currentInputConnection == null) return false
        val resource = try { service.packageManager.getResourcesForApplication(field.sourcePackage).getResourceName(editor.fieldId) }
            catch (_: Exception) { return false }
        return resource == field.resourceId
    }

    private fun sameFocusedNode(service: AgentService, field: ControlNode, expected: AccessibilityNodeInfo): Boolean {
        val windows = service.windows.orEmpty()
        var root: AccessibilityNodeInfo? = null
        var focused: AccessibilityNodeInfo? = null
        try {
            val window = windows.singleOrNull { it.id == field.sourceWindowId &&
                it.displayId == android.view.Display.DEFAULT_DISPLAY } ?: return false
            root = window.root ?: return false
            val live = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            focused = live
            if (expected != live || !live.refresh() || !live.isVisibleToUser ||
                !live.isFocused || !live.isFocusable || !live.isEditable || !live.isEnabled || live.isPassword ||
                (Build.VERSION.SDK_INT >= 34 && live.isAccessibilityDataSensitive) ||
                live.packageName?.toString() != field.sourcePackage || live.windowId != field.sourceWindowId ||
                live.viewIdResourceName != field.resourceId || live.className?.toString() != field.className ||
                live.textSelectionStart != -1 || live.textSelectionEnd != -1 ||
                (!live.isShowingHintText && !live.text.isNullOrEmpty())) return false
            val bounds = Rect().also { live.getBoundsInScreen(it) }
            return bounds.flattenToString() == field.bounds
        } finally {
            if (focused !== root) focused?.recycle()
            root?.recycle()
            windows.forEach { it.recycle() }
        }
    }
}
