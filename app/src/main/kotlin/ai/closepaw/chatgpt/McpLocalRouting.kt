package ai.closepaw.chatgpt

/**
 * An explicitly installed capture-candidate runs alongside the production app.
 * Its MCP server must never bind the production port or publish a second tunnel.
 * The local-only candidate still uses McpExecutionSettings' random private path
 * and the normal Android consent / operation authorization checks.
 */
internal object McpLocalRouting {
    private const val CAPTURE_CANDIDATE_PACKAGE = "ai.closepaw.capturecandidate"
    private const val CAPTURE_CANDIDATE_PORT = 18428

    fun isCaptureCandidate(packageName: String): Boolean =
        packageName == CAPTURE_CANDIDATE_PACKAGE

    fun port(packageName: String): Int =
        if (isCaptureCandidate(packageName)) CAPTURE_CANDIDATE_PORT else McpHttpServer.DEFAULT_PORT
}
