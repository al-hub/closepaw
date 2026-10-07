package ai.closepaw.chatgpt

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class CloudflareQuickTunnelProvider(
    private val binaryResolver: CloudflaredBinaryResolver,
) : TunnelProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processRef = AtomicReference<Process?>(null)
    private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Stopped)
    override val status: StateFlow<TunnelStatus> = _status.asStateFlow()
    private var logJob: Job? = null

    override fun start(localPort: Int) {
        if (processRef.get() != null) return
        val binary = binaryResolver.resolve()
        if (binary == null) {
            _status.value = TunnelStatus.Failed("cloudflared_unavailable")
            return
        }

        _status.value = TunnelStatus.Starting
        val process = try {
            ProcessBuilder(buildCommand(binary, localPort))
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            _status.value = TunnelStatus.Failed("cloudflared_start_failed:${error.javaClass.simpleName}")
            return
        }
        processRef.set(process)

        logJob = scope.launch {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        CloudflareTunnelLogParser.extractPublicUrl(line)?.let { url ->
                            _status.value = TunnelStatus.Connected(url)
                        }
                    }
                }
                if (processRef.compareAndSet(process, null) && _status.value !is TunnelStatus.Stopped) {
                    _status.value = TunnelStatus.Failed("cloudflared_exited:${process.exitValue()}")
                }
            } catch (error: Exception) {
                if (processRef.compareAndSet(process, null) && _status.value !is TunnelStatus.Stopped) {
                    _status.value = TunnelStatus.Failed("cloudflared_io_failed:${error.javaClass.simpleName}")
                }
            }
        }
    }

    override fun stop() {
        _status.value = TunnelStatus.Stopped
        logJob?.cancel()
        logJob = null
        processRef.getAndSet(null)?.let { process ->
            runCatching { process.destroy() }
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
    }

    internal companion object {
        fun buildCommand(binary: File, localPort: Int): List<String> = listOf(
            binary.absolutePath,
            "tunnel",
            "--url", "http://127.0.0.1:$localPort",
            "--no-autoupdate",
        )
    }
}
