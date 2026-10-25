package ai.closepaw.chatgpt.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ClipboardInputTransactionTest {
    @Test fun revokedTargetAfterStagingNeverReportsNoEffectOrRetries() = runBlocking {
        var stages = 0; var pastes = 0; var finishes = 0
        val result = ClipboardInputTransaction({ stages++; true }, {
            pastes++; ControlEffect(false, false, "target_changed")
        }, { finishes++ }).execute()
        assertTrue(result.attempted); assertFalse(result.acknowledged)
        assertEquals("target_changed", result.reason)
        assertEquals(1, stages); assertEquals(1, pastes); assertEquals(1, finishes)
    }
    @Test fun stageExceptionOrUnprovenProvenanceNeverPastes() = runBlocking {
        for (throws in listOf(false, true)) {
            var pastes = 0; var finishes = 0
            val result = ClipboardInputTransaction({ if (throws) error("private") else false }, {
                pastes++; ControlEffect(true, true, "ack")
            }, { finishes++ }).execute()
            assertTrue(result.attempted); assertFalse(result.acknowledged)
            assertFalse(result.reason.contains("private")); assertEquals(0, pastes); assertEquals(1, finishes)
        }
    }
    @Test fun cancellationPropagatesAndNeverBecomesPermissionToReplay() = runBlocking {
        var pastes = 0; var finishes = 0
        try {
            ClipboardInputTransaction({ true }, {
                pastes++; throw CancellationException("X")
            }, { finishes++ }).execute()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, pastes); assertEquals(1, finishes)
    }
}
