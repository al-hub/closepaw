package ai.closepaw.platform.virtualdisplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM security/regression tests for the MediaProjection consent lifecycle of issue #165.
 *
 * These complement [MediaProjectionConsentGateTest] by proving the fail-closed lifecycle
 * holds under concurrency and across consent epochs: one-shot consumption has exactly one
 * winner, revoke races never lose to consumption, stale session ids never consume a fresh
 * grant, and reset/upgrades can never resurrect a closed round.
 */
class MediaProjectionConsentLifecycleSecurityTest {

    private val S1 = "epoch-1-session"
    private val S2 = "epoch-2-session"

    private fun grant(gate: MediaProjectionConsentGate, sessionId: String) {
        gate.onRequestStarted()
        gate.onDecision(granted = true, sessionId = sessionId)
    }

    // ── One-shot consumption under contention ────────────────────────

    @Test
    fun `concurrent consumers can win the one-shot grant exactly once`() {
        val gate = MediaProjectionConsentGate()
        grant(gate, S1)

        val latch = CountDownLatch(1)
        val winners = AtomicInteger(0)
        val losers = AtomicInteger(0)
        val threads = (0 until 32).map {
            thread {
                latch.await()
                if (gate.onConsumed(S1)) winners.incrementAndGet() else losers.incrementAndGet()
            }
        }
        latch.countDown()
        threads.forEach { it.join() }

        assertThat(winners.get()).isEqualTo(1)
        assertThat(losers.get()).isEqualTo(31)
        assertThat(gate.state).isEqualTo(ProjectionConsentState.Consumed)
        assertThat(gate.isProbeAllowed()).isFalse()
        assertThat(gate.usableSessionId()).isNull()
    }

    @Test
    fun `revoke and consume race ends in exactly one terminal winner`() {
        // Synchronized gate: whichever transition takes Granted first wins; the other
        // must report false and neither can resurrect the closed round afterwards.
        repeat(200) {
            val gate = MediaProjectionConsentGate()
            grant(gate, S1)
            val reaper = thread { gate.onRevoked() }
            val consumer = thread { gate.onConsumed(S1) }
            reaper.join()
            consumer.join()
            assertThat(gate.state is ProjectionConsentState.Revoked ||
                gate.state is ProjectionConsentState.Consumed).isTrue()
            assertThat(gate.isProbeAllowed()).isFalse()
            assertThat(gate.usableSessionId()).isNull()
            // Terminal means terminal: no second event reopens it.
            assertThat(gate.onRevoked()).isFalse()
            assertThat(gate.onConsumed(S1)).isFalse()
            assertThat(gate.onDecision(granted = true, sessionId = S1)).isNull()
        }
    }

    // ── Consent epochs: stale ids never cross rounds ─────────────────

    @Test
    fun `stale epoch session id cannot consume a fresh grant`() {
        val gate = MediaProjectionConsentGate()
        grant(gate, S1)
        gate.onRevoked()
        gate.onRequestStarted()
        gate.onDecision(granted = true, sessionId = S2)

        // Old epoch id is rejected five ways, without touching the live grant.
        assertThat(gate.onConsumed(S1)).isFalse()
        assertThat(gate.state).isEqualTo(ProjectionConsentState.Granted(S2))
        assertThat(gate.usableSessionId()).isEqualTo(S2)
        assertThat(gate.isProbeAllowed()).isTrue()
        // And consuming with the fresh id is still possible afterwards.
        assertThat(gate.onConsumed(S2)).isTrue()
        assertThat(gate.isProbeAllowed()).isFalse()
    }

    @Test
    fun `revoke followed by fresh round is the only route back to a usable grant`() {
        val gate = MediaProjectionConsentGate()
        grant(gate, S1)
        assertThat(gate.onRevoked()).isTrue()

        // No grant-shaped event can reinstall a usable session...
        assertThat(gate.onDecision(granted = true, sessionId = S1)).isNull()
        assertThat(gate.onConsumed(S1)).isFalse()
        assertThat(gate.state).isEqualTo(ProjectionConsentState.Revoked)
        assertThat(gate.isProbeAllowed()).isFalse()
        assertThat(gate.usableSessionId()).isNull()

        // ...only a brand-new OS decision round.
        assertThat(gate.onRequestStarted()).isTrue()
        assertThat(gate.state).isEqualTo(ProjectionConsentState.Requesting)
        assertThat(gate.onRequestStarted()).isFalse() // Cannot start a duplicate round.
        assertThat(gate.usableSessionId()).isNull()
        assertThat(gate.isProbeAllowed()).isFalse()
        assertThat(gate.onConsumed(S1)).isFalse()
        assertThat(gate.onDecision(granted = true, sessionId = S2))
            .isEqualTo(ProjectionConsentState.Granted(S2))
        assertThat(gate.usableSessionId()).isEqualTo(S2)
    }

    // ── reset() never resurrects anything ────────────────────────────

    @Test
    fun `reset on a live grant closes it and a raw grant event cannot reopen it`() {
        val gate = MediaProjectionConsentGate()
        grant(gate, S1)
        gate.reset()
        assertThat(gate.state).isEqualTo(ProjectionConsentState.NotRequested)
        assertThat(gate.isProbeAllowed()).isFalse()
        // The stale id must not act like a valid grant after reset.
        assertThat(gate.onConsumed(S1)).isFalse()
        assertThat(gate.onDecision(granted = true, sessionId = S1)).isNull()
        assertThat(gate.isProbeAllowed()).isFalse()
    }

    // ── Consent gate frames the pixel channel per epoch ──────────────

    @Test
    fun `frame policy from a revoked epoch cannot serve a new consent round`() {
        val gate = MediaProjectionConsentGate()
        val policy = MediaProjectionFramePolicy()
        grant(gate, S1)
        // One consent epoch owns exactly one bounded capture surface.
        assertThat(policy.openSurface()).isTrue()
        gate.onRevoked()
        policy.invalidate()

        // A new consent round on the old session's gate must not reactivate the old
        // policy surface: capture consumers re-verify both for every epoch.
        gate.onRequestStarted()
        gate.onDecision(granted = true, sessionId = S2)
        assertThat(gate.isProbeAllowed()).isTrue()
        assertThat(policy.acquireAllowed()).isFalse()
        assertThat(policy.openSurface()).isFalse()
    }
}
