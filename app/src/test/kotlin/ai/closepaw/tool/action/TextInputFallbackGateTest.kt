package ai.closepaw.tool.action

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextInputFallbackGateTest {
    @Test fun explicitMissingNodePermitsFallback() {
        assertThat(TextInputFallbackGate.afterDirectSetFailure("no node at point"))
            .isEqualTo(TextInputFallbackGate.Decision.NEXT_CHANNEL)
    }

    @Test fun emptyOrUnknownFailureNeverReplays() {
        listOf(null, "", "ACTION_SET_TEXT failed", "Gesture timed out", "value did not change",
            "transport disconnected", "unexpected error", "no node at point: pending write")
            .forEach {
                assertThat(TextInputFallbackGate.afterDirectSetFailure(it))
                    .isEqualTo(TextInputFallbackGate.Decision.STOP_UNKNOWN)
            }
    }

    @Test fun onlyExactAllowlistedUndispatchedReasonsPermitFallback() {
        assertThat(TextInputFallbackGate.afterDirectSetFailure(" UNSUPPORTED ACTION_SET_TEXT "))
            .isEqualTo(TextInputFallbackGate.Decision.NEXT_CHANNEL)
        assertThat(TextInputFallbackGate.afterDirectSetFailure("no node, action attempted"))
            .isEqualTo(TextInputFallbackGate.Decision.STOP_UNKNOWN)
    }
}
