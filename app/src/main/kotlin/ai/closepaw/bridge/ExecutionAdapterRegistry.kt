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
        val probeResults = mutableListOf<AdapterProbeResult>()
        for (adapter in adapters) {
            if (request.capability !in adapter.capabilities) continue
            val availability = adapter.probe()
            probeResults += AdapterProbeResult(adapter.id, availability)
            if (availability is AdapterAvailability.Available) return adapter.execute(request)
        }
        throw NoExecutionAdapterException(request.capability, probeResults)
    }
}

data class AdapterProbeResult(
    val adapterId: String,
    val availability: AdapterAvailability,
)

class NoExecutionAdapterException(
    val capability: ExecutionCapability,
    val probeResults: List<AdapterProbeResult> = emptyList(),
) : IllegalStateException(
    "No available execution adapter for " + capability +
        if (probeResults.isEmpty()) "" else ": " + probeResults.joinToString("; ") { result ->
            result.adapterId + "=" + when (val state = result.availability) {
                AdapterAvailability.Available -> "available"
                is AdapterAvailability.NeedsSetup -> "needs_setup(" + state.reason + ")"
                is AdapterAvailability.Unavailable -> "unavailable(" + state.reason + ")"
            }
        }
)
