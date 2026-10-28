package ai.closepaw.platform.virtualdisplay

import ai.closepaw.security.ImageRevocationAuthority
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises the ownership barrier used by onStop, posted teardown, and destruction. */
class MediaProjectionSessionOwnerTest {
    private val gate = MediaProjectionConsentGate()

    @Before
    fun closeConsent() {
        ImageRevocationAuthority.revokeConsent()
    }

    private fun approvedProjection(id: String): MediaProjectionSessionOwner {
        assertTrue(gate.onRequestStarted())
        gate.onDecision(true, id)
        val owner = MediaProjectionSessionOwner(gate)
        assertTrue(gate.onProjectionObtained(id, ImageRevocationAuthority.currentEpoch))
        return owner
    }

    @Test
    fun `old projection callback cannot revoke a newly approved capture`() {
        val old = approvedProjection("old")
        gate.revokeImageConsent()
        val current = approvedProjection("new")
        val epoch = ImageRevocationAuthority.currentEpoch
        val policy = MediaProjectionFramePolicy()
        assertTrue(policy.openSurface())

        assertFalse(old.runIfCurrent(current) {
            gate.revokeImageConsent()
            policy.invalidate()
            fail("Stale callback must not post teardown")
        })

        assertEquals(ProjectionConsentState.Consumed, gate.state)
        assertTrue(ImageRevocationAuthority.isConsentStillValid(epoch))
        assertTrue(policy.acquireAllowed())
    }

    @Test
    fun `new consent generation rejects old owner even before service replacement`() {
        val old = approvedProjection("old")
        gate.revokeImageConsent()
        approvedProjection("new")
        val epoch = ImageRevocationAuthority.currentEpoch

        assertFalse(old.runIfCurrent(old) { gate.revokeImageConsent() })

        assertTrue(ImageRevocationAuthority.isConsentStillValid(epoch))
        assertEquals(ProjectionConsentState.Consumed, gate.state)
    }

    @Test
    fun `projection identity prevents callback from another owner in the same generation`() {
        val old = approvedProjection("session")
        val replacement = MediaProjectionSessionOwner(gate)
        val epoch = ImageRevocationAuthority.currentEpoch

        assertFalse(old.runIfCurrent(replacement) { gate.revokeImageConsent() })
        assertTrue(ImageRevocationAuthority.isConsentStillValid(epoch))
    }

    @Test
    fun `posted teardown revalidates identity after synchronous current stop`() {
        val old = approvedProjection("session")
        var current: MediaProjectionSessionOwner? = old
        var teardown: (() -> Unit)? = null
        var stopped = false
        assertTrue(old.runIfCurrent(current) {
            gate.revokeImageConsent()
            teardown = { old.runIfCurrent(current) { stopped = true }; Unit }
        })
        assertFalse(ImageRevocationAuthority.isConsentGranted)

        // A different projection owner appears while the main-handler task is queued.
        current = MediaProjectionSessionOwner(gate)
        requireNotNull(teardown).invoke()
        assertFalse(stopped)
    }

    @Test
    fun `posted teardown revalidates generation even if old service still owns its projection`() {
        val old = approvedProjection("old")
        var stopped = false
        var teardown: (() -> Unit)? = null
        old.runIfCurrent(old) {
            gate.revokeImageConsent()
            teardown = { old.runIfCurrent(old) { stopped = true }; Unit }
        }
        approvedProjection("new")
        val epoch = ImageRevocationAuthority.currentEpoch

        requireNotNull(teardown).invoke()

        assertFalse(stopped)
        assertTrue(ImageRevocationAuthority.isConsentStillValid(epoch))
    }

    @Test
    fun `current OS stop revokes synchronously and permits posted cleanup`() {
        val owner = approvedProjection("current")
        val policy = MediaProjectionFramePolicy()
        assertTrue(policy.openSurface())
        var teardown: (() -> Unit)? = null
        var stopped = false

        assertTrue(owner.runIfCurrent(owner) {
            gate.revokeImageConsent()
            policy.invalidate()
            teardown = { owner.runIfCurrent(owner) { stopped = true }; Unit }
        })

        assertEquals(ProjectionConsentState.Revoked, gate.state)
        assertFalse(ImageRevocationAuthority.isConsentGranted)
        assertFalse(policy.acquireAllowed())
        assertFalse(stopped)
        requireNotNull(teardown).invoke()
        assertTrue(stopped)
    }

    @Test
    fun `callback after resource detachment cannot revoke consent again`() {
        val owner = approvedProjection("old")
        owner.runIfCurrent(owner) { gate.revokeImageConsent() }
        val epoch = ImageRevocationAuthority.currentEpoch

        assertFalse(owner.runIfCurrent(null) { gate.revokeImageConsent() })
        assertEquals(epoch, ImageRevocationAuthority.currentEpoch)
    }
}
