package ai.closepaw.bridge

interface ExecutionAdapter {
    val id: String
    val capabilities: Set<ExecutionCapability>
    suspend fun probe(): AdapterAvailability
    suspend fun execute(request: ExecutionRequest): ExecutionResult
}

sealed interface AdapterAvailability {
    data object Available : AdapterAvailability
    data class NeedsSetup(val reason: String) : AdapterAvailability
    data class Unavailable(val reason: String) : AdapterAvailability
}

data class ExecutionRequest(
    val capability: ExecutionCapability,
    val command: String,
    val workingDirectory: String? = null,
    val timeoutMs: Long = 120_000L,
)

data class ExecutionResult(
    val adapterId: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)
