package ai.closepaw.chatgpt

import ai.closepaw.bridge.TermuxJobReadiness
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TermuxJobReadinessGateTest {
    private val offline = TermuxJobReadiness(false, "bridge_daemon_unreachable: offline")
    @Test fun healthyBridgeNeverStartsRecovery() = runTest {
        var recoveries = 0
        val gate = TermuxJobReadinessGate({ TermuxJobReadiness(true) }, { recoveries++; null }, { 0L })
        assertTrue(gate.check().ready); assertEquals(0, recoveries)
    }
    @Test fun concurrentOfflineRequestsRecoverOnceAndReobserve() = runTest {
        var healthy = false; var recoveries = 0
        val gate = TermuxJobReadinessGate({ if (healthy) TermuxJobReadiness(true) else offline },
            { recoveries++; delay(20); healthy = true; null }, { 0L })
        val results = coroutineScope { (1..8).map { async { gate.check() } }.awaitAll() }
        assertTrue(results.all { it.ready }); assertEquals(1, recoveries)
    }
    @Test fun failedRecoveryIsThrottledAndPreservesActionableReason() = runTest {
        var now = 0L; var recoveries = 0
        val gate = TermuxJobReadinessGate({ offline }, { recoveries++; "termux_not_running" }, { now })
        assertEquals("termux_not_running", gate.check().reason)
        assertFalse(gate.check().ready); assertEquals(1, recoveries)
        now = 30_000L; gate.check(); assertEquals(2, recoveries)
    }
    @Test fun authTimeoutAndIncompatibleApiNeverRestartOrRepairPairing() = runTest {
        for (reason in listOf("Local bridge pairing token does not match", "bridge_response_timeout: timeout", "Invalid bridge readiness response")) {
            var recoveries = 0
            val gate = TermuxJobReadinessGate({ TermuxJobReadiness(false, reason) }, { recoveries++; null }, { 0L })
            assertEquals(reason, gate.check().reason); assertEquals(0, recoveries)
        }
    }
    @Test fun recoveryCannotClaimSuccessWithoutAuthenticatedJobProbe() = runTest {
        val gate = TermuxJobReadinessGate({ offline }, { null }, { 0L })
        assertFalse(gate.check().ready)
    }
}
