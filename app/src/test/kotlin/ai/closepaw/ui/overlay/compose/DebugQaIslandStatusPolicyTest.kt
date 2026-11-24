package ai.closepaw.ui.overlay.compose

import org.junit.Assert.*
import org.junit.Test

class DebugQaIslandStatusPolicyTest {
    private fun snapshot(state: String, at: Long = 10_000L, epoch: Long = 100_000L,
                         stage: String = "chrome_diagnosis") =
        """{"state":"$state","stage":"$stage","updated_at_elapsed_ms":$at,"updated_at_epoch_ms":$epoch}"""

    @Test fun runningNeedsTwoFreshClockDomains() {
        assertEquals("RUNNING", DebugQaIslandStatusPolicy.decode(
            snapshot("RUNNING"), 12_000L, 102_000L).state)
        assertEquals("UNKNOWN", DebugQaIslandStatusPolicy.decode(
            snapshot("RUNNING"), 16_000L, 102_000L).state)
        assertEquals("UNKNOWN", DebugQaIslandStatusPolicy.decode(
            snapshot("RUNNING"), 11_000L, 106_000L).state)
        assertEquals("UNKNOWN", DebugQaIslandStatusPolicy.decode(
            snapshot("RUNNING"), 9_000L, 99_000L).state)
    }

    @Test fun approvalWaitingAndTerminalAreDifferentFromRunning() {
        assertEquals("WAITING", DebugQaIslandStatusPolicy.decode(
            snapshot("WAITING"), 110_000L, 200_000L).state)
        assertEquals("COMPLETED", DebugQaIslandStatusPolicy.decode(
            snapshot("COMPLETED"), 11_000L, 101_000L).state)
        assertEquals("FAILED", DebugQaIslandStatusPolicy.decode(
            snapshot("FAILED"), 11_000L, 101_000L).state)
        assertEquals("CANCELLED", DebugQaIslandStatusPolicy.decode(
            snapshot("CANCELLED"), 11_000L, 101_000L).state)
        assertEquals("UNKNOWN", DebugQaIslandStatusPolicy.decode(
            snapshot("COMPLETED"), 80_000L, 170_000L).state)
    }

    @Test fun terminalResultAndExportFailureAreVisibleTogether() {
        val value = snapshot("DIAGNOSTIC_COMPLETE").dropLast(1) + ""","export":"FAILED"}"""
        val status = DebugQaIslandStatusPolicy.decode(value, 11_000L, 101_000L)
        assertEquals("DIAGNOSTIC_COMPLETE", status.state)
        assertEquals("FAILED", status.export)
        assertEquals("DENIED", DebugQaIslandStatusPolicy.decode(
            snapshot("DENIED"), 11_000L, 101_000L).state)
        assertEquals(DebugQaIslandStatus.UNKNOWN, DebugQaIslandStatusPolicy.decode(value, 100_000L, 190_000L))
    }

    @Test fun previousProcessCannotShowAnActiveQaRequest() {
        val value = snapshot("WAITING").dropLast(1) + ""","process_start_elapsed_ms":7000}"""
        assertEquals("WAITING", DebugQaIslandStatusPolicy.decode(value, 11_000L, 101_000L, 7000L).state)
        assertEquals("INTERRUPTED", DebugQaIslandStatusPolicy.decode(value, 11_000L, 101_000L, 10500L).state)
    }

    @Test fun corruptedOrForgedPayloadCannotShowRunningOrArbitraryStage() {
        assertEquals("UNKNOWN", DebugQaIslandStatusPolicy.decode(
            null, 11_000L, 101_000L).state)
        assertEquals("UNKNOWN", DebugQaIslandStatusPolicy.decode(
            "{bad", 11_000L, 101_000L).state)
        assertEquals("UNKNOWN", DebugQaIslandStatusPolicy.decode(
            snapshot("CHATGPT_RUNNING"), 11_000L, 101_000L).state)
        val unsafe = DebugQaIslandStatusPolicy.decode(
            snapshot("RUNNING", stage = "https://private"), 11_000L, 101_000L)
        assertEquals("RUNNING", unsafe.state)
        assertNull(unsafe.stage)
    }
}
