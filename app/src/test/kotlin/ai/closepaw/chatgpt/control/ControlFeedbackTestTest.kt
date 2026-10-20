package ai.closepaw.chatgpt.control

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlFeedbackTestTest {
    @Test fun holdsIndicatorForHumanTestWithoutClaimingTouchVerification() = runTest {
        var attached = false
        val transitions = mutableListOf<Boolean>()
        val result = ControlFeedbackTest({ testScheduler.currentTime }, { null },
            { attached = it; transitions.add(it) }, { attached }).run(5_000)
        assertTrue(result.success); assertEquals(5_000L, testScheduler.currentTime)
        assertEquals(listOf(true, false), transitions)
        assertEquals(JsonPrimitive(true), result.data["indicator_removed"])
        assertEquals(JsonPrimitive(false), result.data["human_touch_verified"])
    }
    @Test fun lockInterruptsLeaseWithinOneCheckIntervalAndRemovesIndicator() = runTest {
        var attached = false
        val result = ControlFeedbackTest({ testScheduler.currentTime },
            { if (testScheduler.currentTime >= 350) "device_locked" else null },
            { attached = it }, { attached }).run(5_000)
        assertFalse(result.success); assertFalse(attached)
        assertEquals("device_locked", result.data["reason_code"]?.jsonPrimitive?.content)
        assertEquals("interrupted", result.data["state"]?.jsonPrimitive?.content)
        assertEquals(400L, testScheduler.currentTime)
    }
    @Test fun consentRevocationAtFinalBoundaryIsNotReportedAsCompleted() = runTest {
        var attached = false
        val result = ControlFeedbackTest({ testScheduler.currentTime },
            { if (testScheduler.currentTime >= 5_000) "generic_control_disabled" else null },
            { attached = it }, { attached }).run(5_000)
        assertFalse(result.success); assertFalse(attached)
        assertEquals("generic_control_disabled", result.data["reason_code"]?.jsonPrimitive?.content)
    }
    @Test fun cancellationRemovesIndicatorBeforeJobCompletes() = runTest {
        var attached = false
        val job = launch { ControlFeedbackTest({ testScheduler.currentTime }, { null },
            { attached = it; if (!it) delay(10) }, { attached }).run(5_000) }
        testScheduler.runCurrent(); assertTrue(attached)
        job.cancelAndJoin(); assertFalse(attached)
        assertEquals(10L, testScheduler.currentTime)
    }
    @Test fun initialRefusalDoesNotAttachAndMissingAttachmentFailsClosed() = runTest {
        var shows = 0
        val refused = ControlFeedbackTest({ testScheduler.currentTime }, { "device_locked" },
            { if (it) shows++ }, { false }).run(5_000)
        assertFalse(refused.success); assertEquals(0, shows)
        val missing = ControlFeedbackTest({ testScheduler.currentTime }, { null },
            { if (it) shows++ }, { false }).run(5_000)
        assertFalse(missing.success); assertEquals(1, shows)
        assertEquals("indicator_not_attached", missing.data["reason_code"]?.jsonPrimitive?.content)
        assertTrue(testScheduler.currentTime < 300)
    }
}
