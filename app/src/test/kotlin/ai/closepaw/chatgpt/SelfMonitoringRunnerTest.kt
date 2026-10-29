package ai.closepaw.chatgpt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SelfMonitoringRunnerTest {
    @Before fun reset() { SelfMonitoringAuthority.stop() }
    private fun request(now: Long = 0, id: String = "monitor") {
        assertTrue(SelfMonitoringAuthority.request(id, "session", 1, 10_000, now))
    }
    private fun approve() { assertTrue(SelfMonitoringAuthority.approve("monitor", 0)) }
    private fun state(now: Long = 1, active: Boolean = true, unlocked: Boolean = true) =
        MonitoringRecoveryGate.State(now, active, unlocked)

    @Test fun `only matching unexpired local approval activates authority`() {
        request(100)
        assertFalse(SelfMonitoringAuthority.approve("wrong", 101))
        assertEquals("awaiting_consent", SelfMonitoringAuthority.snapshot()!!.phase)
        assertFalse(SelfMonitoringAuthority.approve("monitor", 99))
        assertFalse(SelfMonitoringAuthority.approve("monitor", 100))
        request(100)
        assertFalse(SelfMonitoringAuthority.approve("monitor", 30_100))
        request(100)
        assertTrue(SelfMonitoringAuthority.approve("monitor", 101))
        assertFalse(SelfMonitoringAuthority.approve("monitor", 102))
        assertFalse(SelfMonitoringAuthority.request("other", "session", 1, 10_000, 102))
    }

    @Test fun `pending consent has no probes or recovery and expires`() = runTest {
        request()
        var probes = 0
        var recoveries = 0
        val job = async { SelfMonitoringRunner({ state(testScheduler.currentTime) }, { probes++; "ready" }, { recoveries++ }).run("monitor") }
        advanceUntilIdle()
        job.await()
        assertEquals(0, probes); assertEquals(0, recoveries)
        assertEquals("expired", SelfMonitoringAuthority.snapshot()!!.phase)
    }

    @Test fun `runtime observes offline dispatches once and independently checks health`() = runTest {
        request(); approve()
        var probes = 0
        var dispatches = 0
        SelfMonitoringRunner({ state() }, { if (++probes == 1) "offline_recoverable" else "ready" }, { guard ->
            assertTrue(guard { dispatches++ })
            assertFalse(guard { dispatches++ })
        }).run("monitor")
        assertEquals(2, probes); assertEquals(1, dispatches)
        assertEquals("finished", SelfMonitoringAuthority.snapshot()!!.phase)
        assertEquals("ready", SelfMonitoringAuthority.snapshot()!!.postCheck)
        assertFalse(SelfMonitoringAuthority.beginRecovery("monitor"))
        assertFalse(SelfMonitoringAuthority.approve("monitor", 2))
    }

    @Test fun `unknown dispatch consumes authority and never replays`() = runTest {
        request(); approve()
        var dispatches = 0
        SelfMonitoringRunner({ state() }, { "offline_recoverable" }, { guard ->
            guard { dispatches++; error("unknown transport result") }
        }).run("monitor")
        assertEquals(1, dispatches)
        assertEquals("dispatch_unknown_post_offline_recoverable", SelfMonitoringAuthority.snapshot()!!.postCheck)
        assertFalse(SelfMonitoringAuthority.dispatch("monitor", { state() }) { dispatches++ })
    }

    @Test fun `stop lock expiry and revoked permission prevent final service dispatch`() = runTest {
        for (finalState in listOf(state(active = false), state(unlocked = false), state(now = 10_000))) {
            request(); approve()
            var safe = state()
            var dispatches = 0
            SelfMonitoringRunner({ safe }, { "offline_recoverable" }, { guard ->
                safe = finalState
                assertFalse(guard { dispatches++ })
            }).run("monitor")
            assertEquals(0, dispatches)
            SelfMonitoringAuthority.stop()
        }
        request(); approve()
        SelfMonitoringRunner({ state() }, { "offline_recoverable" }, { guard ->
            SelfMonitoringAuthority.stop()
            assertFalse(guard { fail("dispatch after Stop") })
        }).run("monitor")
    }

    @Test fun `cancelled runtime owns recovery and performs no later post check`() = runTest {
        request(); approve()
        var probes = 0
        val entered = CompletableDeferred<Unit>()
        val job = async { SelfMonitoringRunner({ state() }, { probes++; "offline_recoverable" }, {
            entered.complete(Unit); CompletableDeferred<Unit>().await()
        }).run("monitor") }
        entered.await(); job.cancel(); runCurrent()
        assertEquals(1, probes)
        assertEquals("interrupted", SelfMonitoringAuthority.snapshot()!!.phase)
        assertFalse(SelfMonitoringAuthority.dispatch("monitor", { state() }) { fail("dispatch after cancellation") })
    }

    @Test fun `concurrent stop returning prevents further external dispatch`() {
        request(); approve(); assertTrue(SelfMonitoringAuthority.beginRecovery("monitor"))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val stopped = CountDownLatch(1)
        val worker = thread {
            SelfMonitoringAuthority.dispatch("monitor", { state() }) {
                entered.countDown(); check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val stopper = thread { SelfMonitoringAuthority.stop(); stopped.countDown() }
        assertFalse(stopped.await(50, java.util.concurrent.TimeUnit.MILLISECONDS))
        release.countDown(); worker.join(2000); stopper.join(2000)
        assertTrue(stopped.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(SelfMonitoringAuthority.dispatch("monitor", { state() }) { fail("late dispatch") })
    }
}
