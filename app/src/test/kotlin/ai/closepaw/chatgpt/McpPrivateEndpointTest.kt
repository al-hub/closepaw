package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import org.junit.Test

class McpPrivateEndpointTest {
    @Test fun bareMcpPathDoesNotExposeToolsAtPrivateEndpoint() {
        val port = ServerSocket(0).use { it.localPort }
        val path = "/mcp/test-private-device-key"
        val server = McpHttpServer(port, McpJsonRpcHandler(ClosePawStatusTool("test",1)), path)
        server.start()
        try {
            fun post(requestPath: String): Int {
                val connection = URL("http://127.0.0.1:$port$requestPath").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.connectTimeout = 3000; connection.readTimeout = 3000
                val bytes = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".toByteArray()
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                return try { connection.responseCode } finally { connection.disconnect() }
            }
            assertThat(post("/mcp")).isEqualTo(404)
            assertThat(post(path)).isEqualTo(200)
        } finally { server.stop() }
    }
}
