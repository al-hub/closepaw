package ai.closepaw.chatgpt.control

/** Global non-node navigation has no semantic target; incomplete node writes always fail closed. */
internal object GenericControlActionPolicy {
    fun requiresCompleteTree(action: String): Boolean = action !in setOf("home", "open_app")
    fun refusal(expected: ControlFrame, fresh: ControlFrame, action: String): String? = when {
        expected.reason != null -> expected.reason
        fresh.reason != null -> fresh.reason
        expected.packageName != fresh.packageName || expected.windowId != fresh.windowId || expected.fingerprint != fresh.fingerprint -> "screen_changed"
        requiresCompleteTree(action) && (expected.truncated || fresh.truncated) -> "truncated_observation"
        else -> null
    }
}
