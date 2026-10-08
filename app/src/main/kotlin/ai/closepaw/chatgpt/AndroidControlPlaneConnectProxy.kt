package ai.closepaw.chatgpt

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback-only HTTP CONNECT proxy used to let Android's Java networking stack
 * perform DNS resolution for the OpenAI control plane. The bundled tunnel-client
 * runtime is built with CGO disabled and can otherwise inherit an unusable
 * loopback resolver on Android.
 */
internal class AndroidControlPlaneConnectProxy(
    private val listenHost: String = "127.0.0.1",
    private val listenPort: Int = 18426,
    private val allowedHost: String = "api.openai.com",
) {
    private val running = AtomicBoolean(false)
    private val workers: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "closepaw-control-plane-proxy-worker").apply { isDaemon = true }
    }
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    fun start(): String {
        if (running.get()) return "http://$listenHost:$listenPort"
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(listenHost), listenPort), 8)
        }
        serverSocket = server
        running.set(true)
        acceptThread = Thread({
            while (running.get()) {
                try {
                    val client = server.accept()
                    workers.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (running.get()) {
                        // Keep the accept loop alive for transient client errors.
                    }
                }
            }
        }, "closepaw-control-plane-proxy-accept").apply {
            isDaemon = true
            start()
        }
        return "http://$listenHost:$listenPort"
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun handleClient(client: Socket) {
        client.use { inbound ->
            inbound.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(inbound.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                writeResponse(inbound, "405 Method Not Allowed")
                return
            }

            val authority = parts[1]
            val splitAt = authority.lastIndexOf(':')
            if (splitAt <= 0 || splitAt == authority.lastIndex) {
                writeResponse(inbound, "400 Bad Request")
                return
            }
            val host = authority.substring(0, splitAt).trim('[', ']')
            val port = authority.substring(splitAt + 1).toIntOrNull()
            if (!host.equals(allowedHost, ignoreCase = true) || port != 443) {
                writeResponse(inbound, "403 Forbidden")
                return
            }

            while (true) {
                val header = reader.readLine() ?: return
                if (header.isEmpty()) break
            }

            val outbound = Socket()
            try {
                // java.net.Socket(host, port) / InetSocketAddress(host, port) uses
                // Android's platform resolver rather than the child Go runtime's
                // /etc/resolv.conf path.
                outbound.connect(InetSocketAddress(host, port), 10_000)
                outbound.soTimeout = 0
                inbound.soTimeout = 0
                val output = inbound.getOutputStream()
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()

                val upstream = workers.submit {
                    runCatching { inbound.getInputStream().copyTo(outbound.getOutputStream()) }
                    runCatching { outbound.shutdownOutput() }
                }
                runCatching { outbound.getInputStream().copyTo(inbound.getOutputStream()) }
                runCatching { inbound.shutdownOutput() }
                runCatching { upstream.get() }
            } catch (_: Exception) {
                if (!outbound.isConnected) writeResponse(inbound, "502 Bad Gateway")
            } finally {
                runCatching { outbound.close() }
            }
        }
    }

    private fun writeResponse(socket: Socket, status: String) {
        runCatching {
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
        }
    }
}
