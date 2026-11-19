package ai.closepaw.chatgpt.control

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalImeGeometryLeaseTest {
    @Test fun missingNativeCallbackTimesOutAndReleasesLease() = runTest {
        val mutex = Mutex()
        var cleanedUp = false
        val result = LocalImeGeometryLease.capture(mutex, timeoutMs = 100,
            busy = { "busy" }, timedOut = { "deadline" }) {
            assertTrue(mutex.isLocked)
            try { awaitCancellation() } finally { cleanedUp = true }
        }
        assertEquals("deadline", result)
        assertTrue(cleanedUp)
        assertTrue(mutex.tryLock())
        mutex.unlock()
    }

    @Test fun busyDoesNotTakeLeaseOrDispatch() = runTest {
        val mutex = Mutex(locked = true)
        try {
            val result = LocalImeGeometryLease.capture(mutex, timeoutMs = 100,
                busy = { "busy" }, timedOut = { "deadline" }) { "unexpected_dispatch" }
            assertEquals("busy", result)
            assertTrue(mutex.isLocked)
        } finally { mutex.unlock() }
    }
}
