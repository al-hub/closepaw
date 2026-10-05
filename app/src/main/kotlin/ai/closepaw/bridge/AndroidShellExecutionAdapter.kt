package ai.closepaw.bridge

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** Android toybox shell provider behind the transport-neutral AA-Bridge contract. */
class AndroidShellExecutionAdapter(
    private val timeoutSeconds: Long = 10L,
) : ExecutionAdapter {
    override val id: String = "android-shell"
    override val capabilities: Set<ExecutionCapability> = setOf(ExecutionCapability.ANDROID_SHELL)

    override suspend fun probe(): AdapterAvailability = AdapterAvailability.Available

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        require(request.capability == ExecutionCapability.ANDROID_SHELL) {
            "Android shell adapter only provides ANDROID_SHELL"
        }
        return withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("sh", "-c", request.command)
                    .redirectErrorStream(false)
                    .start()
                val stdout = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
                val stderr = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
                val timeout = minOf(request.timeoutMs, TimeUnit.SECONDS.toMillis(timeoutSeconds))
                if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    stdout.cancel()
                    stderr.cancel()
                    return@withContext ExecutionResult(id, null, "", "Command timed out after ${timeout}ms", true)
                }
                ExecutionResult(id, process.exitValue(), stdout.await(), stderr.await())
            } catch (error: Exception) {
                ExecutionResult(id, null, "", error.message ?: "Android shell execution failed")
            }
        }
    }
}
