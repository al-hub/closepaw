package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** TDD: all P2 targets use the same minimal, explicit Android app allowlist. */
class GeneralReadTargetTest {
    @Test fun exactKnownAppsMapToExpectedPackages() {
        val examples = mapOf(
            "samsung_internet" to "com.sec.android.app.sbrowser",
            "chrome" to "com.android.chrome",
            "gemini" to "com.google.android.apps.bard",
        )
        examples.forEach { (app, expectedPackage) ->
            val target = BrowserReadTarget.from(app)
            assertThat(target).isNotNull()
            assertThat(target!!.packageName).isEqualTo(expectedPackage)
        }
    }

    @Test fun onlySafeAliasesAreAccepted() {
        assertThat(BrowserReadTarget.from("Google Gemini")?.id)
            .isEqualTo("gemini")
        assertThat(BrowserReadTarget.from("google_gemini")?.id)
            .isEqualTo("gemini")
        assertThat(BrowserReadTarget.from("google_chrome")?.id)
            .isEqualTo("chrome")
        assertThat(BrowserReadTarget.from("toss")).isNull()
        assertThat(BrowserReadTarget.from("com.google.android.googlequicksearchbox"))
            .isNull()
        assertThat(BrowserReadTarget.from("")).isNull()
    }
}
