package ai.closepaw.platform

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GuardedPasteSequenceTest {
    @Test fun stagedThenGuardedPasteNeverClaimsTextVerification() = runTest {
        var stages = 0
        var pastes = 0
        val seq = GuardedPasteSequence(
            stage = { stages++; GuardedClipboardWriter.Outcome.STAGED_UNVERIFIED },
            paste = { pastes++; true }
        )
        assertThat(seq.execute("safe", { true }, { true }))
            .isEqualTo(GuardedPasteSequence.Outcome.PASTE_UNKNOWN)
        assertThat(stages).isEqualTo(1)
        assertThat(pastes).isEqualTo(1)
    }

    @Test fun revokedBeforeStagingNeverTouchesClipboardOrTarget() = runTest {
        var stages = 0
        var pastes = 0
        val seq = GuardedPasteSequence(
            stage = { stages++; GuardedClipboardWriter.Outcome.STAGED_UNVERIFIED },
            paste = { pastes++; true }
        )
        assertThat(seq.execute("safe", { false }, { true }))
            .isEqualTo(GuardedPasteSequence.Outcome.DENIED)
        assertThat(stages).isEqualTo(0)
        assertThat(pastes).isEqualTo(0)
    }

    @Test fun revokedAfterStagingNeverPastes() = runTest {
        var pastes = 0
        val seq = GuardedPasteSequence(
            stage = { GuardedClipboardWriter.Outcome.STAGED_UNVERIFIED },
            paste = { pastes++; true }
        )
        assertThat(seq.execute("safe", { true }, { false }))
            .isEqualTo(GuardedPasteSequence.Outcome.STOPPED_AFTER_STAGING)
        assertThat(pastes).isEqualTo(0)
    }

    @Test fun uncertainStagingNeverPastes() = runTest {
        var pastes = 0
        val seq = GuardedPasteSequence(
            stage = { GuardedClipboardWriter.Outcome.UNKNOWN },
            paste = { pastes++; true }
        )
        assertThat(seq.execute("safe", { true }, { true }))
            .isEqualTo(GuardedPasteSequence.Outcome.STAGE_UNKNOWN)
        assertThat(pastes).isEqualTo(0)
    }

    @Test fun pasteFailureIsUnknownAndNeverRetries() = runTest {
        var pastes = 0
        val seq = GuardedPasteSequence(
            stage = { GuardedClipboardWriter.Outcome.STAGED_UNVERIFIED },
            paste = { pastes++; false }
        )
        assertThat(seq.execute("safe", { true }, { true }))
            .isEqualTo(GuardedPasteSequence.Outcome.PASTE_UNKNOWN)
        assertThat(pastes).isEqualTo(1)
    }
    @Test fun stageExceptionCannotLeadToPaste() = runTest {
        var pasted = false
        val seq = GuardedPasteSequence(
            stage = { throw SecurityException("denied") },
            paste = { pasted = true; true }
        )
        assertThat(seq.execute("safe", { true }, { true }))
            .isEqualTo(GuardedPasteSequence.Outcome.STAGE_UNKNOWN)
        assertThat(pasted).isFalse()
    }

    @Test fun pasteExceptionDoesNotCauseSecondDispatch() = runTest {
        var attempts = 0
        val seq = GuardedPasteSequence(
            stage = { GuardedClipboardWriter.Outcome.STAGED_UNVERIFIED },
            paste = { attempts++; throw IllegalStateException("disconnect") }
        )
        assertThat(seq.execute("safe", { true }, { true }))
            .isEqualTo(GuardedPasteSequence.Outcome.PASTE_UNKNOWN)
        assertThat(attempts).isEqualTo(1)
    }
}
