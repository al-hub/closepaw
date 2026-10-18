package ai.closepaw.chatgpt

import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class McpBodyCompatibilityTest {
    @Test fun fragmentedBodyIsReadCompletely() {
        withServer { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3000
                val body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"
                val output = socket.getOutputStream()
                output.write("POST /mcp HTTP/1.1\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n".toByteArray())
                body.chunked(3).forEach { output.write(it.toByteArray()); output.flush() }
                val response = socket.getInputStream().bufferedReader().readText()
                assertTrue(response.startsWith("HTTP/1.1 200")); assertTrue(response.contains("tools"))
            }
        }
    }
    @Test fun truncatedBodyReturnsBadRequestWithoutDispatch() {
        withServer { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("POST /mcp HTTP/1.1\r\nContent-Length: 100\r\n\r\n{}".toByteArray())
                socket.getOutputStream().flush(); socket.shutdownOutput()
                val response = socket.getInputStream().bufferedReader().readText()
                assertTrue(response.startsWith("HTTP/1.1 400")); assertTrue(response.contains("incomplete_body"))
            }
        }
    }
    private fun withServer(block: (Int) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val server = McpHttpServer(port, McpJsonRpcHandler(ClosePawStatusTool("test", 1)))
        server.start()
        try { block(port) } finally { server.stop() }
    }
}
