package ai.closepaw.chatgpt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MonitoringRecoveryGateTest {
    private fun MonitoringRecoveryGate.tryConsume(action: String, now: Long, active: Boolean, unlocked: Boolean) =
        tryDispatch(action, { MonitoringRecoveryGate.State(now, active, unlocked) }) {}

    @Test fun `state is sampled at dispatch and unknown state stops authority`() {
        val gate = MonitoringRecoveryGate("restart", 1000)
        var invoked = 0
        assertFalse(gate.tryDispatch("restart", { throw IllegalStateException("unavailable") }) { invoked++ })
        assertFalse(gate.tryDispatch("restart", { MonitoringRecoveryGate.State(1, true, true) }) { invoked++ })
        org.junit.Assert.assertEquals(0, invoked)

        val expired = MonitoringRecoveryGate("restart", 1000)
        assertFalse(expired.tryDispatch("restart", { MonitoringRecoveryGate.State(1000, true, true) }) { invoked++ })
        org.junit.Assert.assertEquals(0, invoked)
    }

    @Test fun `dispatch exception consumes authority and never replays`() {
        val gate = MonitoringRecoveryGate("restart", 1000)
        var invoked = 0
        try {
            gate.tryDispatch("restart", { MonitoringRecoveryGate.State(1, true, true) }) {
                invoked++
                throw IllegalStateException("unknown outcome")
            }
            org.junit.Assert.fail("dispatch exception expected")
        } catch (_: IllegalStateException) {
            // Outcome is unknown; authority stays consumed.
        }
        assertFalse(gate.tryDispatch("restart", { MonitoringRecoveryGate.State(2, true, true) }) { invoked++ })
        org.junit.Assert.assertEquals(1, invoked)
    }

    @Test fun `stopped gate does not sample or dispatch`() {
        val gate = MonitoringRecoveryGate("restart", 1000)
        var calls = 0
        gate.stop()
        assertFalse(gate.tryDispatch("restart", { calls++; MonitoringRecoveryGate.State(1, true, true) }) { calls++ })
        org.junit.Assert.assertEquals(0, calls)
    }

    @Test fun `only approved action within active unlocked period can be consumed`() {
        val gate = MonitoringRecoveryGate("bounded_restart", 1000)
        assertFalse(gate.tryConsume("other", 500, true, true))
        assertTrue(gate.tryConsume("bounded_restart", 999, true, true))
        assertFalse(gate.tryConsume("bounded_restart", 999, true, true))
    }

    @Test fun `expiry and stop prevent later dispatch`() {
        assertFalse(MonitoringRecoveryGate("restart", 1000).tryConsume("restart", 1000, true, true))
        val gate = MonitoringRecoveryGate("restart", 1000)
        gate.stop()
        assertFalse(gate.tryConsume("restart", 1, true, true))
        for ((active, unlocked) in listOf(false to true, true to false)) {
            val interrupted = MonitoringRecoveryGate("restart", 1000)
            assertFalse(interrupted.tryConsume("restart", 1, active, unlocked))
            assertFalse(interrupted.tryConsume("restart", 2, true, true))
        }
        val unknownClock = MonitoringRecoveryGate("restart", 1000)
        assertFalse(unknownClock.tryConsume("restart", -1, true, true))
        assertFalse(unknownClock.tryConsume("restart", 1, true, true))
    }

    @Test fun `concurrent attempts have at most one winner`() {
        val gate = MonitoringRecoveryGate("restart", 1000)
        val latch = CountDownLatch(1)
        val successes = AtomicInteger()
        val workers = (1..16).map {
            thread {
                latch.await()
                if (gate.tryConsume("restart", 1, true, true)) successes.incrementAndGet()
            }
        }
        latch.countDown()
        workers.forEach { it.join() }
        org.junit.Assert.assertEquals(1, successes.get())
    }
}
