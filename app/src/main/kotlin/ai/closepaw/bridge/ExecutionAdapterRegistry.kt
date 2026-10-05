package ai.closepaw.bridge

/** Selects an execution path by capability, in adapter preference order. */
class ExecutionAdapterRegistry(private val adapters: List<ExecutionAdapter>) {
    suspend fun availableAdapter(capability: ExecutionCapability): ExecutionAdapter? {
        for (adapter in adapters) {
            if (capability !in adapter.capabilities) continue
            if (adapter.probe() is AdapterAvailability.Available) return adapter
        }
        return null
    }

    suspend fun execute(request: ExecutionRequest): ExecutionResult {
        val adapter = availableAdapter(request.capability)
            ?: throw NoExecutionAdapterException(request.capability)
        return adapter.execute(request)
    }
}

class NoExecutionAdapterException(val capability: ExecutionCapability) :
    IllegalStateException("No available execution adapter for $capability")
