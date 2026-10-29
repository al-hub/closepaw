package ai.closepaw.termux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MonitoringBridgeRecoveryTest {
    @Test fun `monitoring reuses fixed start and has no setup maintenance or unguarded shell`() = runTest {
        var dispatches = 0
        var recoveryInstalls = 0
        val runner = object : TermuxCommandRunner {
            override suspend fun runShell(command: String, stdinBase64: String?, timeoutMs: Long): RunCommandResult =
                error("monitoring must never use unguarded shell")
            override suspend fun runShellGuarded(command: String, timeoutMs: Long, guard: (() -> Unit) -> Boolean): RunCommandResult {
                assertTrue(command.contains("--supervise"))
                assertFalse(command.contains("apt "))
                assertFalse(command.contains("base64 -d"))
                assertTrue(guard { dispatches++ })
                return RunCommandResult("CLOSEPAW_START=ok", "", 0)
            }
        }
        val manager = TermuxBridgeManager(runner, object : TermuxHealthProbe {
            override suspend fun fetch() = HealthProbe.Unavailable
            override suspend fun installRecovery(bridgePayload: String, bootPayload: String): Boolean { recoveryInstalls++; return true }
        }, TermuxInstallProbe { TermuxInstallState.Available }, { error("no payload deployment") }, backgroundScope)
        manager.recoverForMonitoring { action -> action(); true }
        assertEquals(1, dispatches); assertEquals(0, recoveryInstalls)
    }

    @Test fun `manager cancellation before boundary does not detach a service dispatch`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        var dispatches = 0
        val manager = TermuxBridgeManager(object : TermuxCommandRunner {
            override suspend fun runShell(command: String, stdinBase64: String?, timeoutMs: Long): RunCommandResult = error("unguarded")
            override suspend fun runShellGuarded(command: String, timeoutMs: Long, guard: (() -> Unit) -> Boolean): RunCommandResult {
                entered.complete(Unit); wait.await()
                guard { dispatches++ }
                return RunCommandResult("", "", 0)
            }
        }, TermuxHealthProbe { HealthProbe.Unavailable }, TermuxInstallProbe { TermuxInstallState.Available }, { error("payload") }, backgroundScope)
        val job = async { manager.recoverForMonitoring { action -> action(); true } }
        entered.await(); job.cancel(); job.join(); wait.complete(Unit)
        assertEquals(0, dispatches)
    }

    @Test fun `unverified health refuses monitoring recovery without dispatch`() = runTest {
        for (health in HealthProbe.entries.filter { it != HealthProbe.Unavailable }) {
            val manager = TermuxBridgeManager(object : TermuxCommandRunner {
                override suspend fun runShell(command: String, stdinBase64: String?, timeoutMs: Long): RunCommandResult = error("unguarded")
                override suspend fun runShellGuarded(command: String, timeoutMs: Long, guard: (() -> Unit) -> Boolean): RunCommandResult = error("unexpected dispatch")
            }, TermuxHealthProbe { health }, TermuxInstallProbe { TermuxInstallState.Available }, { error("payload") }, backgroundScope)
            try { manager.recoverForMonitoring { fail("no dispatch permitted"); false }; fail("must refuse") }
            catch (_: IllegalStateException) { }
        }
    }
}
