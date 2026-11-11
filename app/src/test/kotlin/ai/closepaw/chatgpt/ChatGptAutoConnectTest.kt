package ai.closepaw.chatgpt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatGptAutoConnectTest {
    @Test fun configuredInstallDefaultsToAutoConnectButFreshInstallDoesNot() {
        assertTrue(ChatGptAutoConnectPolicy.shouldRestore(configured = true, enabled = true))
        assertFalse(ChatGptAutoConnectPolicy.shouldRestore(configured = false, enabled = true))
    }

    @Test fun explicitStopSurvivesActivityResumeAndBootRestore() {
        assertFalse(ChatGptAutoConnectPolicy.shouldRestore(configured = true, enabled = false))
    }

    @Test fun processFailureRetriesAndDuplicateStartDoesNotCreateAnotherLoop() = runTest {
        val tunnel = FakeTunnel()
        val controller = TunnelReconnectController(backgroundScope, tunnel, { true }, nowMs = { testScheduler.currentTime })
        controller.start(18424)
        controller.start(18424)
        runCurrent()
        assertEquals(1, tunnel.starts)
        tunnel.status.value = TunnelStatus.Failed("secure_tunnel_exited:1")
        runCurrent()
        advanceTimeBy(1999)
        runCurrent()
        assertEquals(1, tunnel.starts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, tunnel.starts)
        controller.stop()
    }

    @Test fun stopDuringBackoffCancelsReconnect() = runTest {
        val tunnel = FakeTunnel()
        var enabled = true
        val controller = TunnelReconnectController(backgroundScope, tunnel, { enabled })
        controller.start(18424)
        runCurrent()
        tunnel.status.value = TunnelStatus.Failed("secure_tunnel_not_ready")
        runCurrent()
        enabled = false
        controller.stop()
        advanceTimeBy(120000)
        runCurrent()
        assertEquals(1, tunnel.starts)
        assertEquals(TunnelStatus.Stopped, tunnel.status.value)
    }

    @Test fun persistentFailureUsesCappedBackoffAndRecoversWhenNetworkReturns() = runTest {
        val tunnel = FakeTunnel()
        val controller = TunnelReconnectController(backgroundScope, tunnel, { true }, nowMs = { testScheduler.currentTime })
        controller.start(18424)
        runCurrent()
        for (delay in listOf(2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L)) {
            val before = tunnel.starts
            tunnel.status.value = TunnelStatus.Failed("secure_tunnel_io_failed:IOException")
            runCurrent()
            advanceTimeBy(delay - 1)
            runCurrent()
            assertEquals(before, tunnel.starts)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(before + 1, tunnel.starts)
        }
        tunnel.status.value = TunnelStatus.Connected(tunnelId = "test")
        runCurrent()
        advanceTimeBy(60000)
        tunnel.status.value = TunnelStatus.Failed("secure_tunnel_exited:1")
        runCurrent()
        val before = tunnel.starts
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(before + 1, tunnel.starts)
        controller.stop()
    }

    @Test fun missingRuntimeDoesNotRestartForever() = runTest {
        val tunnel = FakeTunnel()
        val controller = TunnelReconnectController(backgroundScope, tunnel, { true })
        controller.start(18424)
        runCurrent()
        tunnel.status.value = TunnelStatus.Failed("secure_tunnel_runtime_unavailable")
        runCurrent()
        advanceTimeBy(3600000)
        runCurrent()
        assertEquals(1, tunnel.starts)
        controller.stop()
    }

    @Test fun serviceScopeCancellationStopsTheTunnel() = runTest {
        val tunnel = FakeTunnel()
        val owner = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob(coroutineContext[kotlinx.coroutines.Job]))
        val controller = TunnelReconnectController(owner, tunnel, { true })
        controller.start(18424)
        runCurrent()
        owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        runCurrent()
        assertEquals(TunnelStatus.Stopped, tunnel.status.value)
    }

    @Test fun startPermissionFailureDoesNotEscapeOrRetry() = runTest {
        val tunnel = FakeTunnel().apply { startFailure = SecurityException("private fixture detail") }
        val failures = mutableListOf<String>()
        val controller = TunnelReconnectController(backgroundScope, tunnel, { true },
            onFailure = { failures += it })
        controller.start(18424)
        runCurrent()
        advanceTimeBy(3600000)
        runCurrent()
        assertEquals(1, tunnel.starts)
        assertEquals(listOf("tunnel_start_failed:SecurityException"), failures)
        assertEquals(TunnelStatus.Stopped, tunnel.status.value)
    }

    @Test fun permanentFailuresStopInsteadOfRetryingAndReportTheirReason() = runTest {
        for (reason in listOf("secure_tunnel_not_configured", "cloudflared_unavailable",
            "secure_tunnel_start_failed:SecurityException", "secure_tunnel_start_failed:IllegalArgumentException")) {
            val tunnel = FakeTunnel()
            val failures = mutableListOf<String>()
            val controller = TunnelReconnectController(backgroundScope, tunnel, { true },
                onFailure = { failures += it })
            controller.start(18424)
            runCurrent()
            tunnel.status.value = TunnelStatus.Failed(reason)
            runCurrent()
            advanceTimeBy(3600000)
            runCurrent()
            assertEquals(reason, 1, tunnel.starts)
            assertEquals(listOf(reason), failures)
            controller.stop()
        }
    }

    @Test fun transientStartFailureUsesExistingBackoffAndRecovers() = runTest {
        val tunnel = FakeTunnel().apply { startFailure = java.io.IOException("private fixture detail") }
        val retries = mutableListOf<Pair<Long, String>>()
        val controller = TunnelReconnectController(backgroundScope, tunnel, { true },
            onRetry = { delay, reason -> retries += delay to reason })
        controller.start(18424)
        runCurrent()
        assertEquals(listOf(2000L to "tunnel_start_failed:IOException"), retries)
        tunnel.startFailure = null
        advanceTimeBy(1999)
        runCurrent()
        assertEquals(1, tunnel.starts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, tunnel.starts)
        tunnel.status.value = TunnelStatus.Connected(tunnelId = "synthetic")
        runCurrent()
        controller.stop()
    }

    @Test fun stopImmediatelyFollowedByStartWaitsForOldCancellation() = runTest {
        val tunnel = FakeTunnel()
        val controller = TunnelReconnectController(backgroundScope, tunnel, { true })
        controller.start(18424)
        runCurrent()
        controller.stop()
        controller.start(18424)
        controller.start(18424)
        runCurrent()
        assertEquals(2, tunnel.starts)
        assertEquals(TunnelStatus.Starting, tunnel.status.value)
        controller.stop()
    }

    @Test fun cancellationDuringThrowingStartCannotReportRecoveryOrRetry() = runTest {
        for (cancelOwner in listOf(false, true)) {
            val owner = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext +
                kotlinx.coroutines.SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job]))
            val failures = mutableListOf<String>()
            val retries = mutableListOf<Long>()
            val tunnel = FakeTunnel().apply { startFailure = java.io.IOException("synthetic") }
            val controller = TunnelReconnectController(owner, tunnel, { true },
                onFailure = { failures += it }, onRetry = { wait, _ -> retries += wait })
            tunnel.onStart = {
                if (cancelOwner) owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                else controller.stop()
            }
            controller.start(18424)
            runCurrent()
            advanceTimeBy(120000)
            runCurrent()
            assertEquals(1, tunnel.starts)
            assertTrue(failures.isEmpty())
            assertTrue(retries.isEmpty())
            assertEquals(TunnelStatus.Stopped, tunnel.status.value)
            owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        }
    }

    @Test fun disabledIntentNeverStartsTheTunnel() = runTest {
        val tunnel = FakeTunnel()
        val controller = TunnelReconnectController(backgroundScope, tunnel, { false })
        controller.start(18424)
        runCurrent()
        advanceTimeBy(120000)
        runCurrent()
        assertEquals(0, tunnel.starts)
    }

    private class FakeTunnel : TunnelProvider {
        override val status = MutableStateFlow<TunnelStatus>(TunnelStatus.Stopped)
        var starts = 0
        var startFailure: Exception? = null
        var onStart: () -> Unit = {}
        override fun start(localPort: Int) {
            starts++
            onStart()
            startFailure?.let { throw it }
            status.value = TunnelStatus.Starting
        }
        override fun stop() { status.value = TunnelStatus.Stopped }
    }
}
