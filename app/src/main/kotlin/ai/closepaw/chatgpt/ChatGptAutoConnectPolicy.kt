package ai.closepaw.chatgpt

internal object ChatGptAutoConnectPolicy {
    // Never publish a new Quick Tunnel merely because an unconfigured app was opened.
    // A separately installed candidate has no tunnel: restoring its *local-only*
    // MCP listener after an explicit Connect is safe and avoids repeat setup.
    // Production still requires a configured secure endpoint; an explicit Stop
    // (enabled=false) is honored in both packages.
    fun shouldRestore(configured: Boolean, enabled: Boolean, localOnlyCandidate: Boolean = false): Boolean =
        enabled && (configured || localOnlyCandidate)
}
