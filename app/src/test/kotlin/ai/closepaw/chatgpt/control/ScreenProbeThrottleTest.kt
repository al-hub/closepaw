package ai.closepaw.chatgpt.control

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScreenProbeThrottleTest {
    @Test fun elapsedWorkAvoidsFixedWaitButRapidProbesKeepApiSpacing() = runBlocking {
        var time = 1000L
        val waits = mutableListOf<Long>()
        val throttle = ScreenProbeThrottle({ time }, { waits.add(it); time += it })
        throttle.awaitSlot()
        assertTrue(waits.isEmpty())
        time += 550
        throttle.awaitSlot()
        assertTrue(waits.isEmpty())
        time += 125
        throttle.awaitSlot()
        assertEquals(listOf(275L), waits)
        // Immediate second probe (including retry of an explicit Android rate-limit refusal).
        throttle.awaitSlot()
        assertEquals(listOf(275L, 400L), waits)
        throttle.reset()
        throttle.awaitSlot()
        assertEquals(listOf(275L, 400L), waits)
    }
}
