package ai.closepaw.chatgpt.control

/** Full-display requests never grant authority to another application/overlay. */
internal object DisplayCapturePolicy {
    fun refusal(geometryVerified: Boolean, displayId: Int, rotation: Int, contentWindows: List<Int>, owned: Set<Int>): String? = when {
        !geometryVerified || displayId != 0 || rotation !in 0..3 -> "display_geometry_unverified"
        contentWindows.isEmpty() || contentWindows.distinct().size != contentWindows.size || contentWindows.any { it !in owned } -> "display_unowned_window"
        else -> null
    }
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
