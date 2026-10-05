package ai.closepaw.bridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityExecutionGatewayTest {
    @Test
    fun gateway_selects_provider_by_capability_not_product_name() = runBlocking {
        val adapter = RecordingAdapter()
        val gateway = CapabilityExecutionGateway(ExecutionAdapterRegistry(listOf(adapter)))

        val result = gateway.execute(ExecutionCapability.ANDROID_SHELL, "printf closepaw")

        assertEquals("recording-android-shell", result.adapterId)
        assertEquals(ExecutionCapability.ANDROID_SHELL, adapter.request?.capability)
        assertEquals("printf closepaw", adapter.request?.command)
    }

    private class RecordingAdapter : ExecutionAdapter {
        var request: ExecutionRequest? = null
        override val id = "recording-android-shell"
        override val capabilities = setOf(ExecutionCapability.ANDROID_SHELL)
        override suspend fun probe() = AdapterAvailability.Available
        override suspend fun execute(request: ExecutionRequest): ExecutionResult {
            this.request = request
            return ExecutionResult(id, 0, "ok", "")
        }
    }
}
