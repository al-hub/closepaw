package ai.closepaw.app

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlUiRestoreTest {
    @Test fun broadcastBeforeKeyguardClearsRestoresWithoutAnotherWindowEvent() = runTest {
        var calls = 0
        val events = mutableListOf<Pair<String, Long>>()
        val result = ControlUiRestore({ currentTime }, { currentTime >= 80 }, { true },
            { calls++ }, { calls > 0 }, { code, elapsed -> events += code to elapsed }).run()
        assertTrue(result)
        assertEquals(1, calls)
        assertTrue(events.any { it.first == "keyguard_wait" })
        assertEquals("attached", events.last().first)
        assertTrue(events.last().second in 80L..150L)
    }

    @Test fun endedOrReplacedSessionNeverRestoresFromAnOldTrigger() = runTest {
        var calls = 0
        val result = ControlUiRestore({ currentTime }, { currentTime >= 100 }, { currentTime < 80 },
            { calls++ }, { false }).run()
        assertFalse(result); assertEquals(0, calls)
    }

    @Test fun attachmentRetryIsBoundedAndDoesNotCallAnAttemptSuccess() = runTest {
        val events = mutableListOf<String>()
        assertFalse(ControlUiRestore({ currentTime }, { true }, { true }, {}, { false },
            { code, _ -> events += code }).run())
        assertTrue(currentTime <= 2_000)
        assertEquals("timeout", events.last())
        assertFalse("attached" in events)
    }

    @Test fun attachmentCompletionIsMeasuredAfterTraversal() = runTest {
        var requested = false
        assertTrue(ControlUiRestore({ currentTime }, { true }, { true }, { requested = true },
            { requested && currentTime >= 32 }).run())
        assertTrue(currentTime >= 32)
    }
}
