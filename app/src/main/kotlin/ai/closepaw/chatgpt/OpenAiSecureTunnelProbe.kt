package ai.closepaw.chatgpt

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface OpenAiTunnelBinaryResolver {
    fun resolve(): File?
}

internal class AndroidOpenAiTunnelBinaryResolver(
    private val context: Context,
) : OpenAiTunnelBinaryResolver {
    override fun resolve(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        return File(nativeDir, BINARY_NAME).takeIf { it.isFile && it.canExecute() }
    }

    companion object {
        const val BINARY_NAME = "libopenaitunnel.so"
    }
}

internal data class SecureMcpTunnelProbeResult(
    val succeeded: Boolean,
    val summary: String,
)

internal class SecureMcpTunnelProbe(
    private val binaryResolver: OpenAiTunnelBinaryResolver,
) {
    suspend fun run(): SecureMcpTunnelProbeResult = withContext(Dispatchers.IO) {
        val binary = binaryResolver.resolve()
            ?: return@withContext SecureMcpTunnelProbeResult(
                succeeded = false,
                summary = "Secure tunnel runtime not bundled in this build.",
            )

        val process = try {
            ProcessBuilder(binary.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            return@withContext SecureMcpTunnelProbeResult(
                succeeded = false,
                summary = "Runtime start failed: ${error.javaClass.simpleName}",
            )
        }

        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            runCatching { process.destroyForcibly() }
            return@withContext SecureMcpTunnelProbeResult(
                succeeded = false,
                summary = "Runtime probe timed out.",
            )
        }

        val output = runCatching {
            process.inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")

        if (process.exitValue() != 0) {
            return@withContext SecureMcpTunnelProbeResult(
                succeeded = false,
                summary = "Runtime exited ${process.exitValue()}: ${output.take(MAX_OUTPUT_CHARS)}",
            )
        }

        SecureMcpTunnelProbeResult(
            succeeded = true,
            summary = if (output.isBlank()) {
                "Android arm64 runtime executed successfully."
            } else {
                "PASS: ${output.take(MAX_OUTPUT_CHARS)}"
            },
        )
    }

    companion object {
        private const val TIMEOUT_SECONDS = 5L
        private const val MAX_OUTPUT_CHARS = 240
    }
}
