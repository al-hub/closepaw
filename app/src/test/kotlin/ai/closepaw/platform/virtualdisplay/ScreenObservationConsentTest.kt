package ai.closepaw.platform.virtualdisplay

import ai.closepaw.security.ImageRevocationAuthority
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class ScreenObservationConsentTest {
    @After fun revoke() { ImageRevocationAuthority.revokeConsent() }

    @Test fun ordinaryProjectionConsentNeverGrantsScreenObservation() {
        val gate = MediaProjectionConsentGate()
        gate.onRequestStarted()
        gate.onDecision(true, "ordinary")
        assertTrue(gate.onProjectionObtained("ordinary", ImageRevocationAuthority.currentEpoch))
        assertFalse(gate.screenObservationAuthorized())
        // A new request invalidates the old round; it cannot upgrade its authority.
        assertTrue(gate.onRequestStarted(screenObservation = true))
        assertFalse(gate.screenObservationAuthorized())
    }

    @Test fun screenObservationRequiresTheSeparateConsumedConsentRound() {
        val gate = MediaProjectionConsentGate()
        assertFalse(gate.screenObservationAuthorized())
        assertTrue(gate.onRequestStarted(screenObservation = true))
        assertFalse(gate.screenObservationAuthorized())
        gate.onDecision(true, "screen")
        assertFalse(gate.screenObservationAuthorized())
        assertFalse(gate.onProjectionObtained("other", ImageRevocationAuthority.currentEpoch))
        assertFalse(gate.screenObservationAuthorized())
        assertTrue(gate.onProjectionObtained("screen", ImageRevocationAuthority.currentEpoch))
        assertTrue(gate.screenObservationAuthorized())
        gate.revokeImageConsent()
        assertFalse(gate.screenObservationAuthorized())
        assertTrue(gate.onRequestStarted())
        gate.onDecision(true, "next")
        assertTrue(gate.onProjectionObtained("next", ImageRevocationAuthority.currentEpoch))
        assertFalse(gate.screenObservationAuthorized())
    }

    @Test fun deniedCancelledAndResetRoundsCannotAuthorizeObservation() {
        for (end in listOf("deny", "cancel", "reset", "revoke")) {
            val gate = MediaProjectionConsentGate()
            gate.onRequestStarted(screenObservation = true)
            when (end) {
                "deny" -> gate.onDecision(false, null)
                "cancel" -> gate.onCancelled()
                "reset" -> gate.reset()
                else -> gate.revokeImageConsent()
            }
            assertFalse(gate.screenObservationAuthorized())
            assertFalse(gate.onProjectionObtained("missing", ImageRevocationAuthority.currentEpoch))
        }
    }
}
