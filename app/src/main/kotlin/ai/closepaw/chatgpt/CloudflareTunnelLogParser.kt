package ai.closepaw.chatgpt

internal object CloudflareTunnelLogParser {
    private val quickTunnelUrl = Regex("""https://[a-zA-Z0-9-]+\.trycloudflare\.com""")

    fun extractPublicUrl(line: String): String? = quickTunnelUrl.find(line)?.value
}
