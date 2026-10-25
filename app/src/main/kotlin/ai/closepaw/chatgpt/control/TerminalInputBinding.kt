package ai.closepaw.chatgpt.control

import ai.closepaw.app.AgentService
import ai.closepaw.platform.GuardedAccessibilityInputMethod
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/** Main-thread Android binding proof. Never substitutes missing editor metadata,
 * disabled capability, unknown selection/composition or an unreadable terminal. */
internal class TerminalInputBinding private constructor(
    val generation: Long, val fieldId: Int,
    private val input: GuardedAccessibilityInputMethod
) {
    fun evidence(node: AccessibilityNodeInfo): TerminalEvidence? {
        if (!node.isFocused || !node.isEnabled || !node.isVisibleToUser || node.isPassword ||
            (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) ||
            !TerminalInputPolicy.supported(node.packageName?.toString().orEmpty(), node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty())) return null
        val echo = TerminalInputPolicy.echo(node.contentDescription?.toString().orEmpty()) ?: return null
        return TerminalEvidence(echo, generation, fieldId)
    }
    fun commit(text: String) {
        // Caller performs final session/window/node/permission check immediately before this.
        if (Build.VERSION.SDK_INT >= 33) {
            check(input.generation == generation && input.currentInputStarted)
            val editor = input.currentInputEditorInfo ?: error("terminal_editor_missing")
            check(editor.packageName == "com.termux" && editor.fieldId == fieldId && TerminalInputPolicy.inputTypeAllowed(editor.inputType))
            val connection = input.currentInputConnection ?: error("terminal_connection_missing")
            connection.commitText(text, 1, null) // void dispatch; exact echo must still be observed.
        } else throw UnsupportedOperationException("terminal_input_api_unavailable")
    }
    companion object {
        fun capture(service: AgentService): TerminalInputBinding? {
            if (Build.VERSION.SDK_INT < 33) return null
            val input = service.inputMethod as? GuardedAccessibilityInputMethod ?: return null
            val generation = input.generation
            val editor = input.currentInputEditorInfo ?: return null
            if (!input.currentInputStarted || editor.packageName != "com.termux" || editor.fieldId == 0 || !TerminalInputPolicy.inputTypeAllowed(editor.inputType)) return null
            val resource = try { service.packageManager.getResourcesForApplication(editor.packageName).getResourceName(editor.fieldId) }
                catch (_: Exception) { return null }
            if (resource != "com.termux:id/terminal_view") return null
            val connection = input.currentInputConnection ?: return null
            val around = connection.getSurroundingText(1, 1, 0) ?: return null
            if (around.text.isNotEmpty() || around.selectionStart != 0 || around.selectionEnd != 0 || input.generation != generation) return null
            return TerminalInputBinding(generation, editor.fieldId, input)
        }
    }
}
