package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserReadTextPolicyTest {
    @Test
    fun visibleNonPasswordNodeIncludesTrimmedReadableFields() {
        val values = BrowserReadTextPolicy.visibleText(
            visible = true,
            password = false,
            text = "  Article heading  ",
            description = "  Article description ",
            hint = "  Search  ",
        )

        assertThat(values).containsExactly(
            "Article heading",
            "Article description",
            "Search",
        ).inOrder()
    }

    @Test
    fun passwordNodeNeverExposesTextDescriptionOrHint() {
        val values = BrowserReadTextPolicy.visibleText(
            visible = true,
            password = true,
            text = "private-password",
            description = "private-description",
            hint = "private-hint",
        )

        assertThat(values).isEmpty()
    }

    @Test
    fun invisibleNodeNeverExposesFields() {
        val values = BrowserReadTextPolicy.visibleText(
            visible = false,
            password = false,
            text = "offscreen-private-text",
            description = "hidden description",
            hint = "hidden hint",
        )

        assertThat(values).isEmpty()
    }

    @Test
    fun blankAndRedactedPlaceholderValuesAreExcluded() {
        val values = BrowserReadTextPolicy.visibleText(
            visible = true,
            password = false,
            text = "[password]",
            description = "   ",
            hint = " Visible link ",
        )

        assertThat(values).containsExactly("Visible link")
    }
}
