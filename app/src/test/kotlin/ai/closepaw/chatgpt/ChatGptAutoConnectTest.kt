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

    private class FakeTunnel : TunnelProvider {
        override val status = MutableStateFlow<TunnelStatus>(TunnelStatus.Stopped)
        var starts = 0
        override fun start(localPort: Int) { starts++; status.value = TunnelStatus.Starting }
        override fun stop() { status.value = TunnelStatus.Stopped }
    }
}
