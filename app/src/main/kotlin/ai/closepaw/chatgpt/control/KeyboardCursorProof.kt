package ai.closepaw.chatgpt.control

internal enum class CursorProofFailure(val code: String) {
    SCREEN_CHANGE_RACE("screen_change_race"),
    IPC_NULL("ipc_null"),
    NONEMPTY_OR_SELECTION("nonempty_or_selection"),
    EDITOR_MISMATCH("editor_mismatch"),
    UNAVAILABLE("unavailable"),
}

internal data class CursorProofOutcome(
    val generation: Long? = null,
    val failure: CursorProofFailure? = null,
)

internal enum class KeyboardContextMismatchKind(val code: String) {
    KEYBOARD_CHANGED("keyboard_changed"),
    INPUT_TARGET_CHANGED("input_target_changed"),
    CURSOR_PROOF_CHANGED("cursor_proof_changed"),
    NONE("none"),
}

internal enum class CursorRaceLocation(val code: String) {
    BEFORE("before"),
    AFTER("after"),
    BOTH("both"),
    NONE("none"),
}

internal data class KeyboardContextMismatch(
    val kind: KeyboardContextMismatchKind,
    val location: CursorRaceLocation,
    val beforeFailure: CursorProofFailure?,
    val afterFailure: CursorProofFailure?,
    val recoverableReadOnlyCursorRace: Boolean,
)

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

    /**
     * Classifies why GenericControlActionPolicy rejected a keyboard visual capture with
     * `keyboard_context_changed`. Read-only recovery is allowed ONLY when strictly one
     * side failed with [CursorProofFailure.SCREEN_CHANGE_RACE] while the opposite side
     * holds a valid proven empty-editor cursor generation, AND full editor/IME/window/
     * touch-context continuity holds (`inputTargetId` equality alone is never sufficient).
     */
    fun classifyContextMismatch(
        expected: ControlFrame,
        fresh: ControlFrame,
    ): KeyboardContextMismatch {
        if (expected.keyboard != fresh.keyboard) {
            return KeyboardContextMismatch(
                kind = KeyboardContextMismatchKind.KEYBOARD_CHANGED,
                location = CursorRaceLocation.NONE,
                beforeFailure = expected.cursorProofFailure,
                afterFailure = fresh.cursorProofFailure,
                recoverableReadOnlyCursorRace = false,
            )
        }
        if (expected.inputTargetId != fresh.inputTargetId) {
            return KeyboardContextMismatch(
                kind = KeyboardContextMismatchKind.INPUT_TARGET_CHANGED,
                location = CursorRaceLocation.NONE,
                beforeFailure = expected.cursorProofFailure,
                afterFailure = fresh.cursorProofFailure,
                recoverableReadOnlyCursorRace = false,
            )
        }
        val beforeGen = expected.inputTarget?.keyboardCursorGeneration
        val afterGen = fresh.inputTarget?.keyboardCursorGeneration
        if (beforeGen == afterGen) {
            return KeyboardContextMismatch(
                kind = KeyboardContextMismatchKind.NONE,
                location = CursorRaceLocation.NONE,
                beforeFailure = expected.cursorProofFailure,
                afterFailure = fresh.cursorProofFailure,
                recoverableReadOnlyCursorRace = false,
            )
        }
        val location = when {
            beforeGen == null && afterGen != null -> CursorRaceLocation.BEFORE
            beforeGen != null && afterGen == null -> CursorRaceLocation.AFTER
            else -> CursorRaceLocation.BOTH
        }
        val oneSidedScreenChangeRace = when (location) {
            CursorRaceLocation.BEFORE ->
                expected.cursorProofFailure == CursorProofFailure.SCREEN_CHANGE_RACE &&
                    afterGen != null && fresh.cursorProofFailure == null
            CursorRaceLocation.AFTER ->
                fresh.cursorProofFailure == CursorProofFailure.SCREEN_CHANGE_RACE &&
                    beforeGen != null && expected.cursorProofFailure == null
            else -> false
        }
        val beforeTarget = expected.inputTarget
        val afterTarget = fresh.inputTarget
        val strictEmptyEditorContinuity = oneSidedScreenChangeRace &&
            expected.reason == null && fresh.reason == null &&
            !expected.truncated && !fresh.truncated &&
            !expected.navigationOnly && !fresh.navigationOnly &&
            expected.configurationRevision == fresh.configurationRevision &&
            expected.packageName == fresh.packageName &&
            expected.windowId == fresh.windowId &&
            expected.foregroundWindowId == fresh.foregroundWindowId &&
            expected.windowContext.isNotBlank() && expected.windowContext == fresh.windowContext &&
            expected.touchContext.isNotBlank() && expected.touchContext == fresh.touchContext &&
            expected.keyboard != null && expected.keyboard == fresh.keyboard &&
            expected.keyboard.readAllowed && expected.keyboard.interactAllowed &&
            beforeTarget != null && afterTarget != null &&
            beforeTarget.id == expected.inputTargetId && afterTarget.id == fresh.inputTargetId &&
            KeyboardEditorContinuity.sameFocusedEditor(expected, fresh, allowAnonymousResize = false) &&
            beforeTarget.empty && beforeTarget.text.isEmpty() && beforeTarget.enabled && beforeTarget.visibleEditor() &&
            afterTarget.empty && afterTarget.text.isEmpty() && afterTarget.enabled && afterTarget.visibleEditor() &&
            when (location) {
                CursorRaceLocation.BEFORE ->
                    beforeTarget.selectionStart == -1 && beforeTarget.selectionEnd == -1 &&
                        afterTarget.selectionStart == 0 && afterTarget.selectionEnd == 0
                CursorRaceLocation.AFTER ->
                    beforeTarget.selectionStart == 0 && beforeTarget.selectionEnd == 0 &&
                        afterTarget.selectionStart == -1 && afterTarget.selectionEnd == -1
                else -> false
            }
        return KeyboardContextMismatch(
            kind = KeyboardContextMismatchKind.CURSOR_PROOF_CHANGED,
            location = location,
            beforeFailure = expected.cursorProofFailure,
            afterFailure = fresh.cursorProofFailure,
            recoverableReadOnlyCursorRace = strictEmptyEditorContinuity,
        )
    }
}
