package ai.closepaw.chatgpt.control

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlChangeSignalTest {
    @Test fun eventBetweenCheckpointAndSubscriptionIsNotLost() = runTest {
        val signal = ControlChangeSignal()
        val before = signal.version()
        signal.changed()
        assertTrue(signal.awaitAfter(before, 1000))
        assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun newEventWakesImmediatelyInsteadOfWaitingForTheTimeout() = runTest {
        val signal = ControlChangeSignal()
        val wait = async { signal.awaitAfter(signal.version(), 1000) }
        runCurrent()
        advanceTimeBy(12)
        signal.changed()
        runCurrent()
        assertTrue(wait.await())
        assertEquals(12L, testScheduler.currentTime)
    }
    @Test fun absentEventHasBoundedTimeoutAndCancellationDoesNotLeakWaiter() = runTest {
        val signal = ControlChangeSignal()
        assertFalse(signal.awaitAfter(signal.version(), 100))
        assertEquals(100L, testScheduler.currentTime)
        val wait = async { signal.awaitAfter(signal.version(), 1000) }
        runCurrent(); wait.cancel(); runCurrent()
        assertTrue(wait.isCancelled)
        signal.changed()
        assertFalse(signal.awaitAfter(signal.version(), 10))
    }
}
