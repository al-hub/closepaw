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
        assertEquals(listOf(400L), waits)
        time += 550
        throttle.awaitSlot()
        assertEquals(listOf(400L), waits)
        time += 125
        throttle.awaitSlot()
        assertEquals(listOf(400L, 275L), waits)
        throttle.reset()
        throttle.awaitSlot()
        assertEquals(listOf(400L, 275L, 400L), waits)
    }
}
