package ai.closepaw.chatgpt.control

/** Full-display requests never grant authority to another application/overlay. */
internal object DisplayCapturePolicy {
    fun interveningChange(before: Long, after: Long): String? =
        if (before < 0 || before != after) "display_window_changed_during_capture" else null
    fun refusal(geometryVerified: Boolean, displayId: Int, rotation: Int, contentWindows: List<Int>, owned: Set<Int>): String? = when {
        !geometryVerified || displayId != 0 || rotation !in 0..3 -> "display_geometry_unverified"
        contentWindows.isEmpty() || contentWindows.distinct().size != contentWindows.size || contentWindows.any { it !in owned } -> "display_unowned_window"
        else -> null
    }
}

/**
 * Shared fail-closed pre-execution observation recovery and target recertification policy
 * used by both production GenericControl flows and Debug QA flows.
 *
 * Guarantees:
 * - Never relaxes DisplayCapturePolicy ownership, geometry, pixel, or consent guards.
 * - Never recovers after any dispatched/attempted action or UNKNOWN outcome.
 * - Caps read-only pre-execution recovery at [MAX_READ_ONLY_RECOVERIES] (3).
 * - Requires fresh observation and full target/editor/IME/display continuity recertification.
 */
internal object DisplayObservationRecoveryPolicy {
    const val MAX_READ_ONLY_RECOVERIES = 3

    private val readOnlyObservationRefusals = setOf(
        "display_window_changed_during_capture",
        "visual_post_screen_changed",
    )

    private val preDispatchNoEffectRefusals = readOnlyObservationRefusals + setOf(
        "display_window_changed_since_observation",
        "screen_changed",
    )

    fun isRecoverableReadOnlyObservationRefusal(reason: String?): Boolean =
        reason != null && reason in readOnlyObservationRefusals

    fun isRecoverablePreDispatchRefusal(
        reason: String?,
        visualSourceStage: String? = null,
        visualSourceCondition: String? = null,
    ): Boolean {
        if (reason == null) return false
        if (reason in preDispatchNoEffectRefusals) return true
        return reason == VISUAL_SOURCE_REFUSED &&
            visualSourceStage == VisualSourceStage.NATIVE_ACTION_VALIDATION.code &&
            visualSourceCondition == VisualSourceCondition.DISPLAY_WINDOW_CHANGED.code
    }

    fun mayRecoverPreExecution(
        reason: String?,
        recoveriesUsed: Int,
        actionAttempted: Boolean,
        outcomeUnknown: Boolean,
        authorityActive: Boolean,
        maxRecoveries: Int = MAX_READ_ONLY_RECOVERIES,
        visualSourceStage: String? = null,
        visualSourceCondition: String? = null,
        observationOnly: Boolean = false,
    ): Boolean {
        if (actionAttempted || outcomeUnknown || !authorityActive) return false
        if (maxRecoveries !in 0..MAX_READ_ONLY_RECOVERIES || recoveriesUsed !in 0 until maxRecoveries) return false
        return if (observationOnly) {
            isRecoverableReadOnlyObservationRefusal(reason)
        } else {
            isRecoverablePreDispatchRefusal(reason, visualSourceStage, visualSourceCondition)
        }
    }

    /**
     * Re-authenticates target app, window, overlay/rotation touch context, IME authorization,
     * focused editor identity, and editor value/caret on a freshly acquired observation.
     */
    fun recertificationRefusal(
        baseline: ControlFrame,
        fresh: ControlFrame,
        requireUnchangedTouchContext: Boolean = true,
        requireUnchangedKeyboard: Boolean = true,
        requireUnchangedEditorValue: Boolean = true,
        allowAnonymousResize: Boolean = false,
    ): String? = when {
        fresh.reason != null -> fresh.reason
        fresh.truncated || fresh.navigationOnly -> "fresh_frame_incomplete"
        fresh.configurationRevision != baseline.configurationRevision -> "configuration_revision_changed"
        fresh.packageName != baseline.packageName -> "target_package_changed"
        fresh.windowId != baseline.windowId || fresh.foregroundWindowId != baseline.foregroundWindowId ->
            "target_window_changed"
        fresh.windowContext.isBlank() || fresh.windowContext != baseline.windowContext ->
            "window_context_changed"
        requireUnchangedTouchContext &&
            (fresh.touchContext.isBlank() || fresh.touchContext != baseline.touchContext) ->
            "touch_context_or_rotation_changed"
        requireUnchangedKeyboard && (
            baseline.keyboard == null ||
                fresh.keyboard != baseline.keyboard ||
                !fresh.keyboard.readAllowed ||
                !fresh.keyboard.interactAllowed
            ) -> "keyboard_changed_or_unauthorized"
        !KeyboardEditorContinuity.sameFocusedEditor(
            baseline, fresh, allowAnonymousResize = allowAnonymousResize,
        ) -> "editor_identity_changed"
        requireUnchangedEditorValue && !KeyboardEditorContinuity.sameValue(baseline, fresh) ->
            "editor_value_or_caret_changed"
        else -> null
    }

    /** Rejects stale visual IDs, revoked proofs, and non-advancing display generations. */
    fun isFreshDisplayProof(
        previousProofId: String?,
        previousDisplayGeneration: Long,
        candidate: VisualTargetProof?,
        expectedScope: String = "keyboard",
    ): Boolean {
        if (candidate == null) return false
        if (candidate.scope != expectedScope || candidate.sourceDisplayId != 0) return false
        if (!candidate.sourceDisclosureAllowed()) return false
        if (previousProofId != null && candidate.id == previousProofId) return false
        if (previousDisplayGeneration >= 0L && candidate.displayGeneration <= previousDisplayGeneration) return false
        return true
    }
}
// Redacted local Debug metadata: deliberately contains no IDs, package strings,
// titles, pixel values, absolute layers or coordinates. Not an authority token.
internal enum class CensusKind { APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, OTHER }
internal enum class CensusOwner { SYSTEM_EXCLUDED, CURRENT_CONTROL_OVERLAY, OWNED_IME, OWNED_APP, UNOWNED }
internal enum class CensusLayer { UNKNOWN, BELOW, ABOVE, SAME }
internal data class CensusRow(val kind: CensusKind, val owner: CensusOwner, val included: Boolean,
    val imeLayer: CensusLayer, val overlapsImeBounds: Boolean?, val active: Boolean, val focused: Boolean)
internal data class DisplayWindowCensus(val total: Int, val content: Int, val duplicates: Int,
    val unowned: Int, val rows: List<CensusRow>) {
    companion object { const val MAX_ROWS = 64 }
}
internal object VisualCaptureFailure {
    fun native(code: Int): String = when(code) {
        1 -> "screenshot_internal_error"
        2 -> "screenshot_permission_denied"
        3 -> "screenshot_throttled"
        4 -> "screenshot_invalid_display"
        5 -> "screenshot_invalid_window"
        6 -> "screenshot_secure_window"
        else -> "screenshot_native_failure"
    }
}
internal data class VisualCaptureResult(val proof: VisualTargetProof? = null, val reason: String? = null)
