package ai.closepaw.tool.action

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnifiedTextInputPolicyTest {
    private fun evidence() = UnifiedTextInputPolicy.Evidence(
        fresh = true, allowed = true, targetVerified = true, directSupported = true,
        clipboardAllowed = true, pasteSupported = true, imeKeysVerified = true,
        sensitive = false, sessionActive = true
    )

    @Test fun directFirstAndOrderedFallback() {
        val p = UnifiedTextInputPolicy
        val e = evidence()
        assertThat(p.first(e)).isEqualTo(p.Channel.DIRECT)
        assertThat(p.next(p.Channel.DIRECT, p.Result.DEFINITELY_UNSUPPORTED, e))
            .isEqualTo(p.Channel.CLIPBOARD_PASTE)
        assertThat(p.next(p.Channel.CLIPBOARD_PASTE, p.Result.DEFINITELY_UNSUPPORTED, e))
            .isEqualTo(p.Channel.VISIBLE_IME)
        assertThat(p.next(p.Channel.VISIBLE_IME, p.Result.DEFINITELY_UNSUPPORTED, e)).isNull()
    }

    @Test fun unknownPartialOrCancelledNeverFallback() {
        val p = UnifiedTextInputPolicy
        listOf(p.Result.UNKNOWN, p.Result.PARTIAL, p.Result.CANCELLED, p.Result.VERIFIED,
            p.Result.NOT_ATTEMPTED).forEach { result ->
            assertThat(p.next(p.Channel.DIRECT, result, evidence())).isNull()
        }
    }

    @Test fun staleSensitiveOrEndedCannotChooseOrFallback() {
        val p = UnifiedTextInputPolicy
        val blocked = listOf(
            evidence().copy(fresh = false), evidence().copy(allowed = false),
            evidence().copy(targetVerified = false), evidence().copy(sensitive = true),
            evidence().copy(sessionActive = false)
        )
        blocked.forEach {
            assertThat(p.first(it)).isNull()
            assertThat(p.next(p.Channel.DIRECT, p.Result.DEFINITELY_UNSUPPORTED, it)).isNull()
        }
    }

    @Test fun noKeyboardOrClipboardEvidenceCannotGuessSupport() {
        val p = UnifiedTextInputPolicy
        val e = evidence().copy(directSupported = false, pasteSupported = false, imeKeysVerified = false)
        assertThat(p.first(e)).isNull()
        assertThat(p.next(p.Channel.DIRECT, p.Result.DEFINITELY_UNSUPPORTED, e)).isNull()
    }

    @Test fun clipboardRequiresBothPermissionAndPasteCapability() {
        val p = UnifiedTextInputPolicy
        assertThat(p.first(evidence().copy(directSupported = false, clipboardAllowed = false)))
            .isEqualTo(p.Channel.VISIBLE_IME)
        assertThat(p.first(evidence().copy(directSupported = false, pasteSupported = false)))
            .isEqualTo(p.Channel.VISIBLE_IME)
    }
}
