package ai.closepaw.chatgpt.control

internal object LocalImeGeometryFixture {
    const val CHROME = "com.android.chrome"
    const val SAMSUNG = "com.samsung.android.honeyboard"

    fun refusal(frame: ControlFrame): String? {
        if (frame.reason != null || frame.truncated || frame.navigationOnly)
            return "complete_owned_scene_required"
        if (frame.packageName != CHROME || frame.keyboard?.packageName != SAMSUNG ||
            frame.keyboard.readAllowed != true) return "chrome_samsung_read_required"
        val editor = frame.inputTarget ?: return "blank_editor_required"
        if (editor.id != frame.inputTargetId || editor.sourceWindowId != frame.windowId ||
            editor.sourcePackage != CHROME || !editor.enabled || !editor.focusable || !editor.focused ||
            !editor.editable || editor.password || !editor.empty || editor.text.isNotEmpty() ||
            editor.selectionStart != 0 || editor.selectionEnd != 0) return "blank_editor_required"
        return null
    }
}
