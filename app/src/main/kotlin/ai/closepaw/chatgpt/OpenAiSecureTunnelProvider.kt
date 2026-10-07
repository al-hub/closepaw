package ai.closepaw.chatgpt

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class OpenAiSecureTunnelProvider(
    private val binaryResolver: OpenAiTunnelBinaryResolver,
    private val configProvider: () -> SecureMcpTunnelConfig?,
    private val readyProbe: (String) -> Boolean = ::probeReady,
) : TunnelProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processRef = AtomicReference<Process?>(null)
    private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Stopped)
    override val status: StateFlow<TunnelStatus> = _status.asStateFlow()
    private var logJob: Job? = null
    private var readyJob: Job? = null

    override fun start(localPort: Int) {
        if (processRef.get() != null) return

        val binary = binaryResolver.resolve()
        if (binary == null) {
            _status.value = TunnelStatus.Failed("secure_tunnel_runtime_unavailable")
            return
        }
        val config = configProvider()
        if (config == null) {
            _status.value = TunnelStatus.Failed("secure_tunnel_not_configured")
            return
        }

        _status.value = TunnelStatus.Starting
        val process = try {
            ProcessBuilder(
                binary.absolutePath,
                "run",
                "--health.listen-addr", HEALTH_LISTEN_ADDR,
                "--log.level=info",
                "--log.format=struct-text",
            ).apply {
                redirectErrorStream(true)
                environment()["CONTROL_PLANE_API_KEY"] = config.runtimeApiKey
                environment()["CONTROL_PLANE_TUNNEL_ID"] = config.tunnelId
                environment()["MCP_SERVER_URL"] = "http://127.0.0.1:$localPort/mcp"
            }.start()
        } catch (error: Exception) {
            _status.value = TunnelStatus.Failed(
                "secure_tunnel_start_failed:${error.javaClass.simpleName}"
            )
            return
        }
        processRef.set(process)

        logJob = scope.launch {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { /* Drain output so the child cannot block on a full pipe. */ }
                }
                if (processRef.compareAndSet(process, null) && _status.value !is TunnelStatus.Stopped) {
                    _status.value = TunnelStatus.Failed("secure_tunnel_exited:${process.exitValue()}")
                }
            } catch (error: Exception) {
                if (processRef.compareAndSet(process, null) && _status.value !is TunnelStatus.Stopped) {
                    _status.value = TunnelStatus.Failed(
                        "secure_tunnel_io_failed:${error.javaClass.simpleName}"
                    )
                }
            }
        }

        readyJob = scope.launch {
            repeat(READY_ATTEMPTS) {
                if (processRef.get() !== process || !process.isAlive) return@launch
                if (readyProbe(READY_URL)) {
                    _status.value = TunnelStatus.Connected(tunnelId = config.tunnelId)
                    return@launch
                }
                delay(READY_POLL_MS)
            }
            if (processRef.get() === process && process.isAlive && _status.value is TunnelStatus.Starting) {
                _status.value = TunnelStatus.Failed("secure_tunnel_not_ready")
            }
        }
    }

    override fun stop() {
        _status.value = TunnelStatus.Stopped
        readyJob?.cancel()
        readyJob = null
        logJob?.cancel()
        logJob = null
        processRef.getAndSet(null)?.let { process ->
            runCatching { process.destroy() }
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
    }

    companion object {
        internal const val HEALTH_LISTEN_ADDR = "127.0.0.1:18425"
        internal const val READY_URL = "http://127.0.0.1:18425/readyz"
        private const val READY_ATTEMPTS = 60
        private const val READY_POLL_MS = 500L

        private fun probeReady(url: String): Boolean = runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 500
                readTimeout = 500
                useCaches = false
            }
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}
