package ai.closepaw.platform.virtualdisplay

import org.junit.Assert.*
import org.junit.Test

class ProjectionConsentGenerationTest {
    @Test fun `consent generation changes across new rounds and resets`() {
        val gate = MediaProjectionConsentGate()
        gate.onRequestStarted()
        val first = gate.generation
        gate.onDecision(true, "first")
        gate.onConsumed("first")
        assertEquals(first, gate.generation)
        gate.onRequestStarted()
        assertTrue(gate.generation > first)
        val second = gate.generation
        gate.onDecision(true, "second")
        gate.onConsumed("second")
        assertEquals(ProjectionConsentState.Consumed, gate.state)
        assertTrue(gate.generation != first)
        gate.reset()
        assertTrue(gate.generation > second)
    }
}
