package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class McpAccessPathTest {
    @Test
    fun generatedPathIsNotPublicDefaultMcpPath() {
        val path = McpAccessPath.generate()

        assertThat(path).startsWith("/mcp/")
        assertThat(path).isNotEqualTo(McpHttpServer.DEFAULT_MCP_PATH)
        assertThat(path.length).isGreaterThan("/mcp/".length + 20)
    }

    @Test
    fun generatedPathsAreDifferent() {
        assertThat(McpAccessPath.generate()).isNotEqualTo(McpAccessPath.generate())
    }
}
