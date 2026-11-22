package ai.closepaw.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpLocalRoutingTest {
    @Test fun productionRetainsExistingPortAndTunnelBehavior() {
        assertEquals(18424, McpLocalRouting.port("ai.closepaw"))
        assertFalse(McpLocalRouting.isCaptureCandidate("ai.closepaw"))
    }

    @Test fun candidateUsesDedicatedLocalPortAndNoTunnel() {
        assertEquals(18427, McpLocalRouting.port("ai.closepaw.capturecandidate"))
        assertTrue(McpLocalRouting.isCaptureCandidate("ai.closepaw.capturecandidate"))
    }

    @Test fun onlyTheExactCandidatePackageGetsDebugIsolation() {
        assertEquals(18424, McpLocalRouting.port("ai.closepaw.capturecandidate.extra"))
        assertFalse(McpLocalRouting.isCaptureCandidate("ai.closepaw.debug"))
    }
}
