package ai.closepaw.bridge

/**
 * Agent-facing execution gateway. Callers declare what they need; provider selection stays here.
 */
class CapabilityExecutionGateway(
    private val registry: ExecutionAdapterRegistry,
) {
    suspend fun execute(
        capability: ExecutionCapability,
        command: String,
        workingDirectory: String? = null,
        timeoutMs: Long = 120_000L,
    ): ExecutionResult =
        registry.execute(
            ExecutionRequest(
                capability = capability,
                command = command,
                workingDirectory = workingDirectory,
                timeoutMs = timeoutMs,
            )
        )
}
