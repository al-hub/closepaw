package ai.closepaw.chatgpt.control

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlPostObserverTest {
    private val before = ControlFrame("example.app", 1, "before", emptyList())
    private fun input(action: String = "click") = buildJsonObject {
        put("action", action); put("package_name", "target.app")
    }
    @Test fun changedCompleteCaptureReturnsWithoutAnyDwell() = runTest {
        var waits = 0
        val expected = before.copy(fingerprint = "after")
        val observer = ControlPostObserver({ testScheduler.currentTime }, { 1L }, { _, _ -> waits++; false }, { expected })
        assertEquals(expected, observer.observe(before, input(), 0))
        assertEquals(0, waits)
        assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun verifiedHostedTargetDoesNotWaitOrRelaunch() = runTest {
        var waits = 0; var reads = 0
        val expected = before.copy(packageName = "host.app", fingerprint = "host",
            verifiedApplicationPackage = "target.app")
        val observer = ControlPostObserver({ testScheduler.currentTime }, { 1L }, { _, _ -> waits++; false }, { reads++; expected })
        assertEquals(expected, observer.observe(before, input("open_app"), 0))
        assertEquals(1, reads); assertEquals(0, waits)
    }
    @Test fun launchWaitsForActualTargetEvenIfTheOldAppContentChanged() = runTest {
        var reads = 0
        val expected = before.copy(packageName = "target.app", fingerprint = "target")
        val observer = ControlPostObserver({ testScheduler.currentTime }, { 1L }, { _, _ -> true }, {
            reads++
            if (reads == 1) before.copy(fingerprint = "old-app-refreshed") else expected
        })
        assertEquals(expected, observer.observe(before, input("open_app"), 0))
        assertEquals(2, reads)
    }
    @Test fun transientIncompleteTreeWaitsForEventThenRunsFreshSecurityScan() = runTest {
        var reads = 0
        val expected = before.copy(fingerprint = "after")
        val observer = ControlPostObserver({ testScheduler.currentTime }, { 1L }, { _, _ -> delay(7); true }, {
            reads++; if (reads == 1) before.copy(truncated = true) else expected
        })
        assertEquals(expected, observer.observe(before, input(), 0))
        assertEquals(7L, testScheduler.currentTime)
        assertEquals(2, reads)
    }
    @Test fun protectedOrLockedCaptureStopsWithoutWaitingOrReadingAgain() = runTest {
        for (reason in listOf("device_locked", "secure_window", "authentication_boundary", "blocked_or_cautious_app", "secure_window_probe_failed")) {
            var reads = 0; var waits = 0
            val observer = ControlPostObserver({ testScheduler.currentTime }, { 1L }, { _, _ -> waits++; true }, {
                reads++; before.copy(reason = reason)
            })
            assertEquals(reason, observer.observe(before, input(), 0).reason)
            assertEquals(1, reads); assertEquals(0, waits)
        }
    }
    @Test fun noEventReturnsBoundedUnchangedEvidenceWithoutReplayingTheAction() = runTest {
        var reads = 0
        val observer = ControlPostObserver({ testScheduler.currentTime }, { 0L }, { _, ms -> delay(ms); false }, { reads++; before })
        assertEquals(before, observer.observe(before, input(), 0))
        assertEquals(250L, testScheduler.currentTime)
        assertEquals(1, reads)
    }
    @Test fun eventStormCannotCreateAnUnboundedCaptureLoop() = runTest {
        var reads = 0
        val observer = ControlPostObserver({ testScheduler.currentTime }, { reads.toLong() }, { _, _ -> true }, { reads++; before })
        assertEquals(before, observer.observe(before, input(), 0))
        assertEquals(4, reads)
    }
    @Test fun disappearingTransitionWithoutAnotherEventGetsOneBoundedFreshRead() = runTest {
        var reads = 0
        val expected = before.copy(fingerprint = "after")
        val observer = ControlPostObserver({ testScheduler.currentTime }, { 0L },
            { _, ms -> delay(ms); false }, {
                reads++; if (reads == 1) before.copy(reason = "secure_window_changed") else expected
            })
        assertEquals(expected, observer.observe(before, input(), 0))
        assertEquals(2, reads); assertEquals(100L, testScheduler.currentTime)
    }

}
