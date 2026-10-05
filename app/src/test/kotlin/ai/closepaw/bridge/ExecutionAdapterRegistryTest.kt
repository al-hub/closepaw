package ai.closepaw.bridge

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun localBridgeCanBePreferredWithoutProbingRunCommand() = runTest {
        val local = FakeAdapter("termux-local-bridge", AdapterAvailability.Available)
        val runCommand = FakeAdapter(
            "termux-run-command",
            AdapterAvailability.NeedsSetup("Termux must be running before RUN_COMMAND can be used")
        )
        val registry = ExecutionAdapterRegistry(listOf(local, runCommand))

        val result = registry.execute(ExecutionRequest(ExecutionCapability.LINUX_SHELL, "echo ok"))

        assertEquals("termux-local-bridge", result.adapterId)
        assertEquals(1, local.probeCount)
        assertEquals(0, runCommand.probeCount)
    }

    @Test
    fun ignoresAdaptersForOtherCapabilities() = runTest {
        val android = FakeAdapter("android-shell", AdapterAvailability.Available, setOf(ExecutionCapability.ANDROID_SHELL))
        val registry = ExecutionAdapterRegistry(listOf(android))
        assertNull(registry.availableAdapter(ExecutionCapability.LINUX_SHELL))
    }

    @Test
    fun `execution failure reports every matching adapter probe result`() = runTest {
        val runCommand = FakeAdapter(
            "termux-run-command",
            AdapterAvailability.NeedsSetup("Termux must be running before RUN_COMMAND can be used")
        )
        val localBridge = FakeAdapter(
            "termux-local-bridge",
            AdapterAvailability.NeedsSetup("local bridge is not running or paired")
        )
        val registry = ExecutionAdapterRegistry(listOf(runCommand, localBridge))

        val error = assertThrows(NoExecutionAdapterException::class.java) {
            kotlinx.coroutines.runBlocking {
                registry.execute(ExecutionRequest(ExecutionCapability.LINUX_SHELL, "echo ok"))
            }
        }

        assertEquals(2, error.probeResults.size)
        assertEquals("termux-run-command", error.probeResults[0].adapterId)
        assertEquals(
            AdapterAvailability.NeedsSetup("Termux must be running before RUN_COMMAND can be used"),
            error.probeResults[0].availability
        )
        assertEquals("termux-local-bridge", error.probeResults[1].adapterId)
        assertEquals(
            AdapterAvailability.NeedsSetup("local bridge is not running or paired"),
            error.probeResults[1].availability
        )
        assert(error.message!!.contains("termux-run-command"))
        assert(error.message!!.contains("termux-local-bridge"))
    }

    private class FakeAdapter(
        override val id: String,
        private val availability: AdapterAvailability,
        override val capabilities: Set<ExecutionCapability> = setOf(ExecutionCapability.LINUX_SHELL),
    ) : ExecutionAdapter {
        var probeCount: Int = 0
            private set

        override suspend fun probe(): AdapterAvailability {
            probeCount++
            return availability
        }
        override suspend fun execute(request: ExecutionRequest): ExecutionResult =
            ExecutionResult(id, 0, "ok", "")
    }
}
