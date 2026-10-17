package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadAppForegroundPolicyTest {
    private val gemini = BrowserReadTarget.from("gemini")!!
    private val google = "com.google.android.googlequicksearchbox"

    @Test fun acceptsNativeGemini() {
        assertThat(ReadAppForegroundPolicy.accepts(gemini, gemini.packageName, false)).isTrue()
    }

    @Test fun acceptsGoogleHostedGeminiOnlyAfterComposerIdentityVerification() {
        assertThat(ReadAppForegroundPolicy.accepts(gemini, google, true)).isTrue()
    }

    @Test fun rejectsGoogleSearchWithoutGeminiIdentity() {
        assertThat(ReadAppForegroundPolicy.accepts(gemini, google, false)).isFalse()
        assertThat(GeminiDraftComposerPolicy.isComposer("검색", "Google 검색", "search_box", true)).isFalse()
        assertThat(GeminiDraftComposerPolicy.isComposer("무엇이든 물어보세요", "", "prompt_input", true)).isFalse()
    }

    @Test fun verifiedHostNeverAllowsAnUnrelatedPackageOrAnotherTarget() {
        assertThat(ReadAppForegroundPolicy.accepts(gemini, "com.android.systemui", true)).isFalse()
        assertThat(ReadAppForegroundPolicy.accepts(gemini, null, true)).isFalse()
        assertThat(ReadAppForegroundPolicy.accepts(BrowserReadTarget.from("chrome")!!, google, true)).isFalse()
        assertThat(ReadAppForegroundPolicy.accepts(BrowserReadTarget.from("samsung_internet")!!, google, true)).isFalse()
    }

    @Test fun hostedComposerRequiresSpecificGeminiHintOrResourceIdentity() {
        assertThat(GeminiDraftComposerPolicy.isComposer("Gemini에게 물어보세요", "", "", true)).isTrue()
        assertThat(GeminiDraftComposerPolicy.isComposer("Ask Gemini", "", "", true)).isTrue()
        assertThat(GeminiDraftComposerPolicy.isComposer("", "", "assistant_robin_input_text", true)).isTrue()
    }
}
