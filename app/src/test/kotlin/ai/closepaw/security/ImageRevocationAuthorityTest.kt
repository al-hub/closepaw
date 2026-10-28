package ai.closepaw.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class ImageRevocationAuthorityTest {
    @Before
    fun closeConsent() {
        // Revoke instead of resetting: test setup must not reuse old epochs either.
        ImageRevocationAuthority.revokeConsent()
    }

    @Test
    fun `revoked state denies even the current epoch`() {
        assertFalse(ImageRevocationAuthority.isConsentGranted)
        assertFalse(ImageRevocationAuthority.isConsentStillValid(ImageRevocationAuthority.currentEpoch))
        assertEquals(ImageRevocationAuthority.currentEpoch, ImageRevocationAuthority.getCurrentEpochValue())
    }

    @Test
    fun `grant is immediately visible and advances epoch`() {
        val before = ImageRevocationAuthority.currentEpoch
        val granted = ImageRevocationAuthority.grantConsent()
        assertEquals(before + 1, granted)
        assertTrue(ImageRevocationAuthority.isConsentGranted)
        assertTrue(ImageRevocationAuthority.isConsentStillValid(granted))
        assertFalse(ImageRevocationAuthority.isConsentStillValid(before))
    }

    @Test
    fun `revoke synchronously invalidates an outstanding epoch`() {
        val granted = ImageRevocationAuthority.grantConsent()
        val revoked = ImageRevocationAuthority.revokeConsent()
        assertEquals(granted + 1, revoked)
        assertFalse(ImageRevocationAuthority.isConsentGranted)
        assertFalse(ImageRevocationAuthority.isConsentStillValid(granted))
        assertFalse(ImageRevocationAuthority.isConsentStillValid(revoked))
    }

    @Test
    fun `repeated grants and revokes never reuse epochs`() {
        val first = ImageRevocationAuthority.grantConsent()
        val second = ImageRevocationAuthority.grantConsent()
        val third = ImageRevocationAuthority.revokeConsent()
        val fourth = ImageRevocationAuthority.revokeConsent()
        val fifth = ImageRevocationAuthority.grantConsent()
        assertEquals(listOf(first + 1, first + 2, first + 3, first + 4),
            listOf(second, third, fourth, fifth))
        listOf(first, second, third, fourth).forEach {
            assertFalse(ImageRevocationAuthority.isConsentStillValid(it))
        }
        assertTrue(ImageRevocationAuthority.isConsentStillValid(fifth))
    }

    @Test
    fun `late grant cannot overwrite a revoke`() {
        val setupEpoch = ImageRevocationAuthority.currentEpoch
        val revoked = ImageRevocationAuthority.revokeConsent()
        assertFalse(ImageRevocationAuthority.grantConsentIfCurrent(setupEpoch))
        assertEquals(revoked, ImageRevocationAuthority.currentEpoch)
        assertFalse(ImageRevocationAuthority.isConsentGranted)
    }

    @Test
    fun `fresh conditional grant succeeds exactly once`() {
        val epoch = ImageRevocationAuthority.currentEpoch
        assertTrue(ImageRevocationAuthority.grantConsentIfCurrent(epoch))
        assertFalse(ImageRevocationAuthority.grantConsentIfCurrent(epoch))
        assertTrue(ImageRevocationAuthority.isConsentStillValid(epoch + 1))
    }

    @Test
    fun `concurrent transitions each advance the epoch exactly once`() {
        val before = ImageRevocationAuthority.currentEpoch
        val start = CountDownLatch(1)
        val epochs = ConcurrentLinkedQueue<Long>()
        val threads = List(8) { worker ->
            thread {
                start.await()
                repeat(100) { index ->
                    epochs.add(if ((worker + index) % 2 == 0)
                        ImageRevocationAuthority.grantConsent()
                    else ImageRevocationAuthority.revokeConsent())
                }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals((1L..800L).map { before + it }, epochs.sorted())
        assertEquals(before + 800, ImageRevocationAuthority.currentEpoch)
    }
}
