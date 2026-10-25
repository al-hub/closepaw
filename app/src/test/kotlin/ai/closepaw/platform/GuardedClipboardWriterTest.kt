package ai.closepaw.platform

import android.content.ClipboardManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class GuardedClipboardWriterTest {
    @Test fun deniedConsentDoesNotReadOrWriteClipboard() {
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        val writer = GuardedClipboardWriter(clipboard)
        assertThat(writer.stage("non-sensitive", consent = false, safeTarget = true))
            .isEqualTo(GuardedClipboardWriter.Outcome.DENIED)
        assertThat(writer.stage("non-sensitive", consent = true, safeTarget = false))
            .isEqualTo(GuardedClipboardWriter.Outcome.DENIED)
        assertThat(writer.stage("", consent = true, safeTarget = true))
            .isEqualTo(GuardedClipboardWriter.Outcome.DENIED)
        verify(exactly = 0) { clipboard.setPrimaryClip(any()) }
        verify(exactly = 0) { clipboard.primaryClip }
    }

    @Test fun exceptionIsUnknownNotSafeToRetry() {
        val clipboard = mockk<ClipboardManager>()
        every { clipboard.setPrimaryClip(any()) } throws SecurityException("denied")
        assertThat(GuardedClipboardWriter(clipboard).stage("test", true, true))
            .isEqualTo(GuardedClipboardWriter.Outcome.UNKNOWN)
        verify(exactly = 1) { clipboard.setPrimaryClip(any()) }
    }
}
