package ai.closepaw.platform.virtualdisplay

import ai.closepaw.security.ImageRevocationAuthority
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Tests the service's synchronous lifecycle transitions, without fabricating OS tokens. */
class ProjectionImageConsentAuthorityTest {
    @Before
    fun closeConsent() {
        ImageRevocationAuthority.revokeConsent()
    }

    private fun approvedGate() = MediaProjectionConsentGate().apply {
        onRequestStarted()
        onDecision(true, "session")
    }

    @Test
    fun `activity approval and token consumption alone do not grant image consent`() {
        val gate = approvedGate()
        assertFalse(ImageRevocationAuthority.isConsentGranted)
        assertTrue(gate.onConsumed("session"))
        assertFalse(ImageRevocationAuthority.isConsentGranted)
    }

    @Test
    fun `denial cancellation and missing approval never grant image consent`() {
        val epoch = ImageRevocationAuthority.currentEpoch
        val denied = MediaProjectionConsentGate().apply {
            onRequestStarted()
            onDecision(false, null)
        }
        val cancelled = MediaProjectionConsentGate().apply {
            onRequestStarted()
            onCancelled()
        }
        listOf(MediaProjectionConsentGate(), denied, cancelled).forEach {
            assertFalse(it.onProjectionObtained("session", epoch))
        }
        assertEquals(epoch, ImageRevocationAuthority.currentEpoch)
        assertFalse(ImageRevocationAuthority.isConsentGranted)
    }

    @Test
    fun `projection success requires the current one-shot token`() {
        val gate = approvedGate()
        val epoch = ImageRevocationAuthority.currentEpoch
        assertFalse(gate.onProjectionObtained("stale", epoch))
        assertFalse(ImageRevocationAuthority.isConsentGranted)
        assertTrue(gate.onProjectionObtained("session", epoch))
        val granted = ImageRevocationAuthority.currentEpoch
        assertTrue(ImageRevocationAuthority.isConsentStillValid(granted))
        assertFalse(gate.onProjectionObtained("session", granted))
        assertEquals(granted, ImageRevocationAuthority.currentEpoch)
    }

    @Test
    fun `stop barrier synchronously invalidates active image consent`() {
        val gate = approvedGate()
        assertTrue(gate.onProjectionObtained("session", ImageRevocationAuthority.currentEpoch))
        val granted = ImageRevocationAuthority.currentEpoch
        gate.revokeImageConsent()
        assertEquals(ProjectionConsentState.Revoked, gate.state)
        assertFalse(ImageRevocationAuthority.isConsentStillValid(granted))
        assertFalse(ImageRevocationAuthority.isConsentGranted)
        val stopped = ImageRevocationAuthority.currentEpoch
        gate.revokeImageConsent() // Repeated teardown/onDestroy remains closed and monotonic.
        assertEquals(stopped + 1, ImageRevocationAuthority.currentEpoch)
    }

    @Test
    fun `revocation during OS setup prevents a late grant`() {
        val gate = approvedGate()
        val setupEpoch = ImageRevocationAuthority.currentEpoch
        ImageRevocationAuthority.revokeConsent()
        assertFalse(gate.onProjectionObtained("session", setupEpoch))
        assertEquals(ProjectionConsentState.Revoked, gate.state)
        assertFalse(ImageRevocationAuthority.isConsentGranted)
    }

    @Test
    fun `racing stop and projection success always finish revoked`() {
        repeat(100) {
            val gate = approvedGate()
            val epoch = ImageRevocationAuthority.currentEpoch
            val start = CountDownLatch(1)
            val setup = thread { start.await(); gate.onProjectionObtained("session", epoch) }
            val stop = thread { start.await(); gate.revokeImageConsent() }
            start.countDown()
            setup.join()
            stop.join()
            assertEquals(ProjectionConsentState.Revoked, gate.state)
            assertFalse(ImageRevocationAuthority.isConsentGranted)
            assertFalse(ImageRevocationAuthority.isConsentStillValid(epoch))
        }
    }
}
