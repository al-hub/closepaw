package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class McpHttpServerPathTest {
    @Test
    fun defaultMcpPathUsesProvenStandardEndpoint() {
        val handler = McpJsonRpcHandler(ClosePawStatusTool("0.1.19", 20))
        val server = McpHttpServer(port = 18424, handler = handler)

        assertThat(server.mcpPath).isEqualTo("/mcp")
        assertThat(server.mcpPath).isEqualTo(McpHttpServer.DEFAULT_MCP_PATH)
    }
}
