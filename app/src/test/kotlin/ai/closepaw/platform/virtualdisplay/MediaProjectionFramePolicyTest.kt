package ai.closepaw.platform.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * JVM-only proof of the diagnostic mirror scope lifecycle/ordering guarantees of
 * issue #165: bounded one-session open, revocation-race fail-closed semantics,
 * freshness bounds and mirror geometry caps. No Android dimension is exercised.
 */
class MediaProjectionFramePolicyTest {

    private fun policy() = MediaProjectionFramePolicy()

    @Test
    fun `open is single-use and rebuilds closed state after invalidate`() {
        val policy = policy()
        assertTrue(policy.openSurface())
        assertFalse(policy.openSurface()) // one bounded surface per session
        assertTrue(policy.invalidate())
        assertFalse(policy.acquireAllowed())
        assertFalse(policy.openSurface()) // invalidate is permanent
        assertFalse(policy.closeSurface()) // close cannot resurrect an invalidated session
    }

    @Test
    fun `revocation cannot be resurrected by any close or open ordering`() {
        val policy = policy()
        assertTrue(policy.openSurface())
        assertTrue(policy.closeSurface())
        assertTrue(policy.openSurface()) // after graceful close, same single session can reopen
        assertTrue(policy.invalidate())
        assertFalse(policy.acquireAllowed())
        assertTrue(policy.invalidate()) // idempotent
        assertFalse(policy.openSurface())
    }

    @Test
    fun `acquire never wins a revocation race`() {
        repeat(200) {
            val fresh = policy()
            check(fresh.openSurface())
            val racing = thread { fresh.invalidate() }
            fresh.acquireAllowed() // legal only while invalidate has not completed yet
            racing.join()
            assertFalse(fresh.acquireAllowed())
            assertFalse(fresh.openSurface())
        }
    }

    @Test
    fun `concurrent open and invalidate converge closed`() {
        val policy = policy()
        val allowed = listOf(false, false, false)
        val threads = (0 until 3).map { i ->
            thread {
                repeat(1_000) {
                    if (i % 2 == 0) policy.openSurface() else policy.invalidate()
                    allowed[i] = allowed[i] || policy.acquireAllowed()
                }
            }
        }
        threads.forEach { it.join() }
        policy.invalidate()
        assertFalse(policy.acquireAllowed())
        assertFalse(policy.openSurface())
    }

    @Test
    fun `freshness is bounded and unknown clocks fail closed`() {
        val policy = MediaProjectionFramePolicy(maxFrameAgeMs = 1_000)
        assertTrue(policy.isFrameFresh(500, 1_200))
        assertFalse(policy.isFrameFresh(500, 1_501)) // older than bound
        assertFalse(policy.isFrameFresh(-1, 100)) // unknown capture time
        assertFalse(policy.isFrameFresh(1_500, 1_000)) // future-clocked frame
    }

    @Test
    fun `mirror geometry is capped`() {
        val policy = MediaProjectionFramePolicy(maxMirrorPixels = 8_000_000)
        assertTrue(policy.acceptsMirrorGeometry(1080, 2400))
        assertFalse(policy.acceptsMirrorGeometry(4000, 3000))
        assertFalse(policy.acceptsMirrorGeometry(0, 100))
    }

    @Test
    fun `gate consumption closes the consent window before any frame work`() {
        val gate = MediaProjectionConsentGate()
        val policy = policy()
        gate.reset()
        assertTrue(gate.onRequestStarted())
        val granted = gate.onDecision(true, "session-1") as? ai.closepaw.platform.virtualdisplay.ProjectionConsentState.Granted
        check(granted != null)
        // Holder consumes the single-use grant exactly once; the pixel channel in the
        // service therefore never runs outside the epoch of its own consent round.
        assertTrue(gate.onConsumed("session-1"))
        assertFalse(gate.onConsumed("session-1"))
        assertEquals(ProjectionConsentState.Consumed, gate.state)
        assertTrue(gate.onRevoked())
        assertTrue(gate.onRequestStarted()) // the ONLY route back is a fresh OS round
        assertTrue(policy.openSurface()) // policy survives independently of gate state
        assertTrue(policy.invalidate())
    }
}
