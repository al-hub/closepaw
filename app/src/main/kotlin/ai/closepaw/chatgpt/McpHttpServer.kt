package ai.closepaw.chatgpt

import ai.closepaw.security.ImageRevocationAuthority
import java.io.IOException
import java.io.DataInputStream
import java.io.EOFException
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class McpHttpServer(
    private val port: Int,
    private val handler: McpJsonRpcHandler,
    val mcpPath: String = DEFAULT_MCP_PATH,
) {
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val requestExecutor = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocketChannel? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocketChannel.open()
        socket.socket().reuseAddress = true
        socket.socket().bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), port))
        serverSocket = socket
        acceptExecutor.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    requestExecutor.execute {
                        // A timed out, revoked or disconnected response is terminal.
                        try {
                            handleClient(client)
                        } catch (_: IOException) {
                            // handleClient closes the channel on every exit.
                        }
                    }
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

    private fun handleClient(channel: SocketChannel) {
        channel.use {
            val client = channel.socket()
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
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
                method == "OPTIONS" && path == mcpPath ->
                    McpHttpResponse(204)
                method != "POST" || path != mcpPath ->
                    McpHttpResponse(404, "{\"error\":\"not_found\"}")
                else -> {
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    if (length <= 0 || length > MAX_BODY_BYTES) {
                        McpHttpResponse(400, "{\"error\":\"invalid_content_length\"}")
                    } else {
                        val bodyBytes = ByteArray(length)
                        val complete = try {
                            DataInputStream(input).readFully(bodyBytes)
                            true
                        } catch (_: EOFException) { false }
                        if (!complete) {
                            McpHttpResponse(400, "{\"error\":\"incomplete_body\"}")
                        } else {
                            handler.handle(bodyBytes.toString(StandardCharsets.UTF_8))
                        }
                    }
                }
            }
            writeResponse(SocketMcpResponseTransport(channel), response)
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

    internal fun writeResponse(
        transport: McpResponseTransport,
        response: McpHttpResponse,
        timeoutMillis: Long = SOCKET_TIMEOUT_MS.toLong(),
    ) = transport.use {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val imageEpoch = response.imageEpoch
        val authorized = imageEpoch?.let {
            ImageRevocationAuthority.isConsentStillValid(it)
        } ?: true
        val outgoing = if (authorized) response else McpHttpResponse(403, """{"error":"image_consent_revoked"}""")
        val bodyBytes = outgoing.body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val reason = when (outgoing.statusCode) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 ${outgoing.statusCode} $reason\r\n")
            append("Content-Type: ${outgoing.contentType}\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        val guardedEpoch = imageEpoch?.takeIf { authorized }
        for (bytes in listOf(headers, bodyBytes)) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (Thread.currentThread().isInterrupted) return@use
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw SocketTimeoutException("MCP response write timed out")
                // Only a bounded NONBLOCKING write may run under the authority monitor.
                // Readiness waits and transport close must stay outside it. Checking and
                // writing atomically prevents any new image bytes after revoke returns.
                val count = synchronized(ImageRevocationAuthority) {
                    if (guardedEpoch != null && !ImageRevocationAuthority.isConsentStillValid(guardedEpoch)) {
                        return@use
                    }
                    val limit = buffer.limit()
                    buffer.limit(minOf(limit, buffer.position() + 4096))
                    try {
                        transport.write(buffer)
                    } finally {
                        buffer.limit(limit)
                    }
                }
                if (count == 0) {
                    // Poll consent even if the peer never becomes writable. No flush or
                    // buffered close can deliver queued application bytes after revoke.
                    transport.awaitWritable(minOf(WRITE_POLL_MS, maxOf(1L,
                        TimeUnit.NANOSECONDS.toMillis(remaining))))
                }
            }
        }
    }

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 18424
        const val DEFAULT_MCP_PATH = "/mcp"
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val WRITE_POLL_MS = 25L
        private const val SOCKET_TIMEOUT_MS = 15_000
    }
}
