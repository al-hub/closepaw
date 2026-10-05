package ai.closepaw.bridge

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecutionAdapterRegistryTest {
    @Test
    fun selectsFirstAvailableAdapterForCapability() = runTest {
        val unavailable = FakeAdapter("run-command", AdapterAvailability.Unavailable("unsupported"))
        val local = FakeAdapter("local-bridge", AdapterAvailability.Available)
        val registry = ExecutionAdapterRegistry(listOf(unavailable, local))
        val result = registry.execute(ExecutionRequest(ExecutionCapability.LINUX_SHELL, "echo ok"))
        assertEquals("local-bridge", result.adapterId)
        assertEquals("ok", result.stdout)
    }

    @Test
    fun ignoresAdaptersForOtherCapabilities() = runTest {
        val android = FakeAdapter("android-shell", AdapterAvailability.Available, setOf(ExecutionCapability.ANDROID_SHELL))
        val registry = ExecutionAdapterRegistry(listOf(android))
        assertNull(registry.availableAdapter(ExecutionCapability.LINUX_SHELL))
    }

    private class FakeAdapter(
        override val id: String,
        private val availability: AdapterAvailability,
        override val capabilities: Set<ExecutionCapability> = setOf(ExecutionCapability.LINUX_SHELL),
    ) : ExecutionAdapter {
        override suspend fun probe(): AdapterAvailability = availability
        override suspend fun execute(request: ExecutionRequest): ExecutionResult =
            ExecutionResult(id, 0, "ok", "")
    }
}
