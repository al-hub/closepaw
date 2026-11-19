package ai.closepaw.chatgpt.control

internal object LocalImeGeometryFixture {
    const val CHROME = "com.android.chrome"
    const val SAMSUNG = "com.samsung.android.honeyboard"

    fun emptyCaret(editor: ControlNode, bindingProven: Boolean): Boolean =
        editor.empty && editor.text.isEmpty() &&
            ((editor.selectionStart == 0 && editor.selectionEnd == 0) ||
                (bindingProven && editor.selectionStart == -1 && editor.selectionEnd == -1))

    fun refusal(frame: ControlFrame, bindingProven: Boolean = false): String? {
        if (frame.reason != null || frame.truncated || frame.navigationOnly)
            return "complete_owned_scene_required"
        if (frame.packageName != CHROME || frame.keyboard?.packageName != SAMSUNG ||
            frame.keyboard.readAllowed != true) return "chrome_samsung_read_required"
        val editor = frame.inputTarget ?: return "blank_editor_required"
        if (editor.id != frame.inputTargetId || editor.sourceWindowId != frame.windowId ||
            editor.sourcePackage != CHROME || !editor.enabled || !editor.focusable || !editor.focused ||
            !editor.editable || editor.password || !emptyCaret(editor, bindingProven)) return "blank_editor_required"
        return null
    }
}
