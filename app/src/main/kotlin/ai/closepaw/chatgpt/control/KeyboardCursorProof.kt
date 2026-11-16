package ai.closepaw.chatgpt.control

/** Only the empty-field case is recovered. An unknown absolute offset, a
 * selection, or any surrounding text is not proof of an empty caret. */
internal object KeyboardCursorProof {
    fun needsProof(field: ControlNode): Boolean =
        field.empty && field.text.isEmpty() && field.selectionStart == -1 && field.selectionEnd == -1 &&
            field.enabled && field.visibleEditor() && field.sourcePackage.isNotEmpty() && field.sourceWindowId >= 0 &&
            field.resourceId.isNotEmpty()

    private fun ControlNode.visibleEditor() = editable && focused && focusable && !password

    fun provesEmpty(textLength: Int, selectionStart: Int, selectionEnd: Int, offset: Int): Boolean =
        textLength == 0 && selectionStart == 0 && selectionEnd == 0 && offset == 0
}
