package ai.closepaw.chatgpt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MonitoringRecoveryGateTest {
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
