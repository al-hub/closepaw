package ai.closepaw.chatgpt.control

/** Global non-node navigation has no semantic target; incomplete node writes always fail closed. */
internal object GenericControlActionPolicy {
    fun requiresCompleteTree(action: String): Boolean = action !in setOf("home", "open_app")
    fun validationScope(action: String): String = if (requiresCompleteTree(action)) "full_screen_fingerprint" else "foreground_app_window"
    fun refusal(expected: ControlFrame, fresh: ControlFrame, action: String): String? = when {
        expected.configurationRevision != fresh.configurationRevision -> "configuration_revision_changed"
        expected.reason != null -> expected.reason
        fresh.reason != null -> fresh.reason
        expected.packageName != fresh.packageName || expected.windowId != fresh.windowId ||
            expected.foregroundWindowId != fresh.foregroundWindowId -> "screen_changed"
        requiresCompleteTree(action) && expected.fingerprint != fresh.fingerprint -> "screen_changed"
        requiresCompleteTree(action) && (expected.truncated || fresh.truncated) -> "truncated_observation"
        action == "key" && (expected.keyboard != fresh.keyboard || expected.inputTargetId != fresh.inputTargetId) -> "keyboard_context_changed"
        (action in setOf("tap", "key") || action.startsWith("visual_")) && (expected.touchContext.isEmpty() || fresh.touchContext.isEmpty()) -> "touch_window_unavailable"
        (action in setOf("tap", "key") || action.startsWith("visual_")) && expected.touchContext != fresh.touchContext -> "touch_window_changed"
        else -> null
    }
}
