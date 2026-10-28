package ai.closepaw.platform.virtualdisplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit tests for [MediaProjectionConsentGate].
 *
 * Contract under test: consent is optional, never cached or reused, deny/cancel/revoke fail
 * closed, and the gate exposes no capture/coordinate capability beyond the one-shot
 * staleness-guard session id.
 */
class MediaProjectionConsentGateTest {

    private fun gate() = MediaProjectionConsentGate()
    private val SESSION = "session-1"

    private fun grant(gate: MediaProjectionConsentGate) {
        gate.onRequestStarted()
        gate.onDecision(granted = true, sessionId = SESSION)
    }

    // ── Initial state ────────────────────────────────────────────

    @Test
    fun `initial state is NotRequested and closed`() {
        val g = gate()
        assertThat(g.state).isEqualTo(ProjectionConsentState.NotRequested)
        assertThat(g.isProbeAllowed()).isFalse()
        assertThat(g.usableSessionId()).isNull()
    }

    // ── Request lifecycle ────────────────────────────────────────

    @Test
    fun `onRequestStarted moves NotRequested to Requesting`() {
        val g = gate()
        assertThat(g.onRequestStarted()).isTrue()
        assertThat(g.state).isEqualTo(ProjectionConsentState.Requesting)
        assertThat(g.isProbeAllowed()).isFalse()
    }

    @Test
    fun `onRequestStarted is ignored while a grant is live`() {
        val g = gate()
        grant(g)
        assertThat(g.onRequestStarted()).isFalse()
        assertThat(g.isProbeAllowed()).isTrue()
    }

    @Test
    fun `onRequestStarted is ignored while already Requesting`() {
        val g = gate()
        assertThat(g.onRequestStarted()).isTrue()
        assertThat(g.onRequestStarted()).isFalse()
        assertThat(g.state).isEqualTo(ProjectionConsentState.Requesting)
    }

    // ── Grant path ───────────────────────────────────────────────

    @Test
    fun `grant from Requesting opens a one-shot usable session`() {
        val g = gate()
        grant(g)
        assertThat(g.state).isEqualTo(ProjectionConsentState.Granted(SESSION))
        assertThat(g.isProbeAllowed()).isTrue()
        assertThat(g.usableSessionId()).isEqualTo(SESSION)
    }

    @Test
    fun `grant without a prior request is ignored and stays closed`() {
        val g = gate()
        assertThat(g.onDecision(granted = true, sessionId = SESSION)).isNull()
        assertThat(g.state).isEqualTo(ProjectionConsentState.NotRequested)
        assertThat(g.isProbeAllowed()).isFalse()
    }

    @Test
    fun `grant with blank sessionId is ignored and stays closed`() {
        val g = gate()
        g.onRequestStarted()
        assertThat(g.onDecision(granted = true, sessionId = "")).isNull()
        assertThat(g.onDecision(granted = true, sessionId = null)).isNull()
        assertThat(g.state).isEqualTo(ProjectionConsentState.Requesting)
        assertThat(g.isProbeAllowed()).isFalse()
    }

    @Test
    fun `grant after terminal round cannot be smuggled in without a new request`() {
        val g = gate()
        grant(g)
        g.onConsumed(SESSION)
        assertThat(g.onDecision(granted = true, sessionId = SESSION)).isNull()
        assertThat(g.isProbeAllowed()).isFalse()
    }

    // ── Deny fails closed ────────────────────────────────────────

    @Test
    fun `deny from Requesting lands terminal Denied`() {
        val g = gate()
        g.onRequestStarted()
        val result = g.onDecision(granted = false, sessionId = null)
        assertThat(result).isEqualTo(ProjectionConsentState.Denied)
        assertThat(g.state).isEqualTo(ProjectionConsentState.Denied)
        assertThat(g.isProbeAllowed()).isFalse()
        assertThat(g.usableSessionId()).isNull()
    }

    @Test
    fun `deny event without an open round is ignored`() {
        val g = gate()
        assertThat(g.onDecision(granted = false, sessionId = null)).isNull()
        assertThat(g.state).isEqualTo(ProjectionConsentState.NotRequested)
    }

    @Test
    fun `after deny a new request round is required for any grant`() {
        val g = gate()
        g.onRequestStarted()
        g.onDecision(granted = false, sessionId = null)

        assertThat(g.isProbeAllowed()).isFalse()
        // Stale grant event without a new round is ignored.
        assertThat(g.onDecision(granted = true, sessionId = SESSION)).isNull()

        // New round succeeds.
        assertThat(g.onRequestStarted()).isTrue()
        assertThat(g.onDecision(granted = true, sessionId = SESSION))
            .isEqualTo(ProjectionConsentState.Granted(SESSION))
        assertThat(g.isProbeAllowed()).isTrue()
    }

    // ── Cancel fails closed ──────────────────────────────────────

    @Test
    fun `cancel from Requesting lands terminal Cancelled`() {
        val g = gate()
        g.onRequestStarted()
        assertThat(g.onCancelled()).isTrue()
        assertThat(g.state).isEqualTo(ProjectionConsentState.Cancelled)
        assertThat(g.isProbeAllowed()).isFalse()
        // No grant event can be retro-fitted after cancel.
        assertThat(g.onDecision(granted = true, sessionId = SESSION)).isNull()
        assertThat(g.isProbeAllowed()).isFalse()
    }

    @Test
    fun `cancel with no open round is ignored`() {
        val g = gate()
        assertThat(g.onCancelled()).isFalse()
        assertThat(g.state).isEqualTo(ProjectionConsentState.NotRequested)
    }

    @Test
    fun `cancel does not touch a live grant (revoke governs that path)`() {
        val g = gate()
        grant(g)
        assertThat(g.onCancelled()).isFalse()
        assertThat(g.isProbeAllowed()).isTrue()
    }

    // ── Revoke fails closed ──────────────────────────────────────

    @Test
    fun `revoke from Granted lands terminal Revoked immediately`() {
        val g = gate()
        grant(g)
        assertThat(g.onRevoked()).isTrue()
        assertThat(g.state).isEqualTo(ProjectionConsentState.Revoked)
        assertThat(g.isProbeAllowed()).isFalse()
        assertThat(g.usableSessionId()).isNull()
    }

    @Test
    fun `revoke from Requesting also fails closed`() {
        val g = gate()
        g.onRequestStarted()
        assertThat(g.onRevoked()).isTrue()
        assertThat(g.state).isEqualTo(ProjectionConsentState.Revoked)
        assertThat(g.isProbeAllowed()).isFalse()
    }

    @Test
    fun `revoke is idempotent and nothing resurrects consent`() {
        val g = gate()
        grant(g)
        assertThat(g.onRevoked()).isTrue()
        assertThat(g.onRevoked()).isFalse()
        assertThat(g.onDecision(granted = true, sessionId = SESSION)).isNull()
        assertThat(g.onConsumed(SESSION)).isFalse()
        assertThat(g.state).isEqualTo(ProjectionConsentState.Revoked)
    }

    @Test
    fun `revoke with no live round is ignored`() {
        val g = gate()
        assertThat(g.onRevoked()).isFalse()
        assertThat(g.state).isEqualTo(ProjectionConsentState.NotRequested)
    }

    // ── One-shot consumption (no reuse) ──────────────────────────

    @Test
    fun `consume with matching sessionId closes the grant permanently`() {
        val g = gate()
        grant(g)
        g.requireUsableSessionId() // consumers take the id, then consume

        assertThat(g.onConsumed(SESSION)).isTrue()
        assertThat(g.state).isEqualTo(ProjectionConsentState.Consumed)
        assertThat(g.isProbeAllowed()).isFalse()
        assertThat(g.usableSessionId()).isNull()
    }

    @Test
    fun `consume with wrong or stale sessionId is rejected without state change`() {
        val g = gate()
        grant(g)
        assertThat(g.onConsumed("other-session")).isFalse()
        assertThat(g.onConsumed(null)).isFalse()
        assertThat(g.state).isEqualTo(ProjectionConsentState.Granted(SESSION))
        assertThat(g.isProbeAllowed()).isTrue()
    }

    @Test
    fun `consumed consent can never be reused or re-granted without a new round`() {
        val g = gate()
        grant(g)
        g.onConsumed(SESSION)

        assertThat(g.isProbeAllowed()).isFalse()
        assertThat(g.onDecision(granted = true, sessionId = SESSION)).isNull()
        assertThat(g.onConsumed(SESSION)).isFalse()

        // Stale session id from the old round must not survive a new round: it stays a
        // staleness guard, so consuming the fresh grant with the OLD id fails.
        assertThat(g.onRequestStarted()).isTrue()
        val fresh = "session-2"
        assertThat(g.onDecision(granted = true, sessionId = fresh))
            .isEqualTo(ProjectionConsentState.Granted(fresh))
        assertThat(g.onConsumed(SESSION)).isFalse()
        assertThat(g.onConsumed(fresh)).isTrue()
        assertThat(g.isProbeAllowed()).isFalse()
    }

    // ── requireUsableSessionId / reset ───────────────────────────

    @Test
    fun `requireUsableSessionId fails fast when no usable grant exists`() {
        val closedStates =
            listOf(
                ProjectionConsentState.NotRequested,
                ProjectionConsentState.Denied,
                ProjectionConsentState.Cancelled,
                ProjectionConsentState.Revoked,
                ProjectionConsentState.Consumed
            )
        for (expected in closedStates) {
            val g = gate()
            // Drive each terminal state through its real transition.
            when (expected) {
                ProjectionConsentState.NotRequested -> {}
                ProjectionConsentState.Denied -> {
                    g.onRequestStarted()
                    g.onDecision(granted = false, sessionId = null)
                }
                ProjectionConsentState.Cancelled -> {
                    g.onRequestStarted()
                    g.onCancelled()
                }
                ProjectionConsentState.Revoked -> {
                    g.onRequestStarted()
                    g.onRevoked()
                }
                ProjectionConsentState.Consumed -> {
                    grant(g)
                    g.onConsumed(SESSION)
                }
                else -> {}
            }
            try {
                g.requireUsableSessionId()
                org.junit.Assert.fail("expected ConsentNotUsableException for $expected")
            } catch (e: ConsentNotUsableException) {
                assertThat(e.message).contains("not usable")
            }
        }
    }

    @Test
    fun `requireUsableSessionId returns the live one-shot session id`() {
        val g = gate()
        grant(g)
        assertThat(g.requireUsableSessionId()).isEqualTo(SESSION)
    }

    @Test
    fun `reset never resurrects a grant`() {
        val g = gate()
        grant(g)
        g.onConsumed(SESSION)
        g.reset()
        assertThat(g.state).isEqualTo(ProjectionConsentState.NotRequested)
        assertThat(g.isProbeAllowed()).isFalse()
    }
}
