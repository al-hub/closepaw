package ai.closepaw.chatgpt

internal object ChatGptAutoConnectPolicy {
    // Never publish a new Quick Tunnel merely because an unconfigured app was opened.
    fun shouldRestore(configured: Boolean, enabled: Boolean): Boolean = configured && enabled
}
