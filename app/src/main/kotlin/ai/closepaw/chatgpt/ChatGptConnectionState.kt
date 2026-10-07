package ai.closepaw.chatgpt

internal data class ChatGptConnectionState(
    val running: Boolean = false,
    val phase: String = "Stopped",
    val publicMcpUrl: String? = null,
    val tunnelId: String? = null,
    val transport: String? = null,
    val error: String? = null,
)
