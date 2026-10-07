package ai.closepaw.chatgpt

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class McpHttpServer(
    private val port: Int,
    private val handler: McpJsonRpcHandler,
) {
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val requestExecutor = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), port))
        serverSocket = socket
        acceptExecutor.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    requestExecutor.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptExecutor.shutdownNow()
        requestExecutor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val headerBytes = readHeaders(input) ?: return
            val headerText = headerBytes.toString(StandardCharsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(" ") ?: return
            if (requestLine.size < 2) return
            val method = requestLine[0]
            val path = requestLine[1]
            val headers = lines.drop(1)
                .mapNotNull { line ->
                    val index = line.indexOf(':')
                    if (index <= 0) null
                    else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
                }.toMap()

            val response = when {
                method == "GET" && path == "/health" ->
                    McpHttpResponse(200, "{\"status\":\"ok\"}")
                method == "OPTIONS" && path == "/mcp" ->
                    McpHttpResponse(204)
                method != "POST" || path != "/mcp" ->
                    McpHttpResponse(404, "{\"error\":\"not_found\"}")
                else -> {
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    if (length <= 0 || length > MAX_BODY_BYTES) {
                        McpHttpResponse(400, "{\"error\":\"invalid_content_length\"}")
                    } else {
                        val bodyBytes = input.readNBytes(length)
                        if (bodyBytes.size != length) {
                            McpHttpResponse(400, "{\"error\":\"incomplete_body\"}")
                        } else {
                            handler.handle(bodyBytes.toString(StandardCharsets.UTF_8))
                        }
                    }
                }
            }
            writeResponse(output, response)
        }
    }

    private fun readHeaders(input: BufferedInputStream): ByteArray? {
        val buffer = ArrayList<Byte>()
        var matched = 0
        val marker = byteArrayOf(13, 10, 13, 10)
        while (buffer.size < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return null
            val byte = value.toByte()
            buffer.add(byte)
            if (byte == marker[matched]) {
                matched++
                if (matched == marker.size) return buffer.toByteArray()
            } else {
                matched = if (byte == marker[0]) 1 else 0
            }
        }
        return null
    }

    private fun writeResponse(output: BufferedOutputStream, response: McpHttpResponse) {
        val bodyBytes = response.body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val reason = when (response.statusCode) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 ${response.statusCode} $reason\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        output.write(headers)
        output.write(bodyBytes)
        output.flush()
    }

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 18424
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val SOCKET_TIMEOUT_MS = 15_000
    }
}
