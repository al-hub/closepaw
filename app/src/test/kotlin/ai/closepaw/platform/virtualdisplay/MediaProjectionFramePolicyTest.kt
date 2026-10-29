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
        assertFalse(policy.openSurface()) // one-shot surface stays consumed even after graceful close
        assertFalse(policy.invalidate()) // close already made the policy permanently invalid
        assertFalse(policy.acquireAllowed())
        assertFalse(policy.invalidate()) // idempotent repeat reports no new transition
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
        val allowed = BooleanArray(3)
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
    fun `clock gap cannot establish which source timestamp is fresh`() {
        val policy = policy()
        // The same numeric timestamp could be a fresh uptime timestamp or an
        // old elapsed-realtime timestamp. The clock gap alone cannot distinguish them.
        val timestamp = 89_500L
        val uptime = 90_000L
        val elapsed = 100_000L
        assertTrue(policy.isFrameFresh(timestamp, uptime))
        assertFalse(policy.isFrameFresh(timestamp, elapsed))
        // The current fail-closed path uses elapsed, including at delivery.
        assertFalse(policy.isDeliveryFresh(timestamp, elapsed))
    }

    @Test
    fun `suspend makes uptime-only delivery age insufficient`() {
        val policy = policy()
        val captureUptime = 90_000L
        val captureElapsed = 100_000L
        // During suspend uptime does not advance, but elapsed realtime does.
        assertTrue(policy.isDeliveryFresh(captureUptime, captureUptime + 100))
        assertFalse(policy.isDeliveryFresh(captureElapsed, captureElapsed + 60_100))
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
    @Test fun `delivery expires independently of observation lifetime`() {
        val policy = policy()
        assertTrue(policy.isDeliveryFresh(500, 10_500))
        assertFalse(policy.isDeliveryFresh(500, 10_501))
        assertFalse(policy.isDeliveryFresh(1_000, 999))
        assertFalse(policy.isDeliveryFresh(-1, 100))
    }

    @Test
    fun `device clock samples reproduce the projection freshness refusal`() {
        // Observed with user-approved v0.1.99 on Galaxy Android 16.
        // These samples are evidence, not a general producer clock contract.
        val policy = policy()
        val samples = listOf(
            Triple(596111006200151L, 792317482L, 596111022L),
            Triple(596112508350502L, 792318976L, 596112516L),
        )
        for ((timestampNs, elapsedMs, uptimeMs) in samples) {
            val sourceMs = timestampNs / 1_000_000L
            assertTrue(policy.isFrameFresh(sourceMs, uptimeMs))
            assertFalse(policy.isFrameFresh(sourceMs, elapsedMs))
            assertFalse(policy.isDeliveryFresh(sourceMs, elapsedMs))
        }
        // Mapping remains prohibited without a trusted clock contract.
        assertFalse(policy.isFrameFresh(-1, samples.last().second))
    }

}
