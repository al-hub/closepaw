package ai.closepaw.chatgpt

import org.junit.Assert.*
import org.junit.Test

class GeminiReadIdentityTest {
    private val pkg = AndroidGeminiDraftUi.GOOGLE_HOST
    private fun ids(vararg names: String) = names.map { "$pkg:id/$it" }.toSet()
    @Test fun composerlessSidebarNeedsBothNativeShellIdentifiers() {
        assertTrue(GeminiReadIdentity.nativeShell(pkg, ids("assistant_robin_main_activity", "assistant_robin_side_nav_host_content_container")))
        assertFalse(GeminiReadIdentity.nativeShell(pkg, ids("assistant_robin_main_activity")))
        assertFalse(GeminiReadIdentity.nativeShell(pkg, ids("search_box", "Gemini")))
        assertFalse(GeminiReadIdentity.nativeShell("other.app", ids("assistant_robin_main_activity", "assistant_robin_chat_fragment_container")))
    }
    @Test fun visibleSidebarDoesNotRequireItsStructuralActivityMarkerToBeVisible() {
        val all = ids("assistant_robin_main_activity", "assistant_robin_side_nav_host_content_container")
        val visible = ids("assistant_robin_side_nav_host_content_container")
        assertTrue(GeminiReadIdentity.nativeShell(pkg, all, visible))
    }
    @Test fun hiddenGeminiPaneCannotIdentifyForegroundSearch() {
        val all = ids("assistant_robin_main_activity", "assistant_robin_side_nav_host_content_container")
        assertFalse(GeminiReadIdentity.nativeShell(pkg, all, ids("search_box")))
        assertFalse(GeminiReadIdentity.nativeShell(pkg, ids("search_box"), ids("assistant_robin_side_nav_host_content_container")))
    }
}
