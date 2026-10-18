package ai.closepaw.chatgpt

import org.junit.Assert.*
import org.junit.Test

/** Two-response native tree observed on the Galaxy: old body/toolbar visible, new body offscreen. */
class GeminiNativeReplyPageParserTest {
    private val pkg = "com.google.android.googlequicksearchbox"
    private val question = "new native question"
    private fun n(i: Int, parent: Int, id: String = "", text: String = "", visible: Boolean = true, action: Boolean = false) =
        GeminiUiNode(i, parent, packageName = pkg, resourceId = "$pkg:id/$id", className = "android.view.View",
            text = text, visible = visible, clickable = action, clickAction = action)
    private fun fixture() = listOf(n(0, -1), n(1, 0, "assistant_robin_chat_history_list").copy(scrollable = true, scrollForward = true, scrollBackward = true),
        n(2, 1, "assistant_robin_content_message_container"), n(3, 2, "assistant_robin_text", "old answer"),
        n(4, 2, text = "복사", action = true), n(5, 2, text = "대답이 마음에 들어요", action = true), n(6, 2, text = "대답이 마음에 들지 않아요", action = true),
        n(7, 1, "assistant_robin_user_message_container"), n(8, 7, "assistant_robin_user_message_text", question),
        n(9, 1, "assistant_robin_content_message_container", visible = false), n(10, 9, "assistant_robin_text", "new answer from first item through last item", visible = false),
        n(11, 9, text = "복사", visible = false, action = true), n(12, 9, text = "대답이 마음에 들어요", visible = false, action = true),
        n(13, 9, text = "대답이 마음에 들지 않아요", visible = false, action = true),
        n(14, 0).copy(composer = true, editable = true))
    private fun parse(nodes: List<GeminiUiNode> = fixture()) = GeminiReplyPageParser.parse(nodes, question, true)
    @Test fun offscreenCurrentBodyIsOwnedByExactQuestionAndOldToolbarCannotCompleteIt() {
        val p = parse()!!
        assertEquals("new answer from first item through last item", p.text)
        assertTrue(p.questionVisible); assertTrue(p.canForward); assertFalse(p.endVisible)
        assertTrue(p.nativeWholeBody)
        assertFalse(p.text.contains("old answer"))
        assertEquals(1, GeminiReplyPageParser.target(fixture(), question, true, p.containerKey, true))
    }
    @Test fun onlyCurrentResponseVisibleToolbarProvesCompletionAfterScroll() {
        val p = parse(fixture().map { if (it.index in 9..13) it.copy(visible = true) else it })!!
        assertTrue(p.endVisible)
    }
    @Test fun clippedScreenGeometryCannotMoveCurrentBodyBeforeQuestionOrSelectOldBody() {
        val p = parse(fixture().map { when (it.index) {
            8 -> it.copy(screenTop = 700, screenLeft = 0)
            10 -> it.copy(screenTop = 0, screenLeft = 0)
            else -> it.copy(screenTop = 100, screenLeft = 0)
        } })!!
        assertEquals("new answer from first item through last item", p.text)
        assertFalse(p.endVisible)
    }
    @Test fun exactHiddenUserAnchorRemainsCorrelatedButDoesNotInventStartVisibility() {
        val p = parse(fixture().map { if (it.index in 7..8) it.copy(visible = false) else it })!!
        assertFalse(p.questionVisible); assertEquals("new answer from first item through last item", p.text)
    }
    @Test fun laterUserTurnOrMultipleResponseOwnersIsAmbiguous() {
        val later = fixture() + n(15, 1, "assistant_robin_user_message_text", "later question")
        assertTrue(parse(later)!!.conflictingTurn)
        assertTrue(parse(fixture() + n(15, 1, "assistant_robin_content_message_container"))!!.conflictingTurn)
    }
    @Test fun passwordDisabledEditableForeignOrMenuAncestorsCannotExposeOffscreenText() {
        val invalid = listOf<(GeminiUiNode)->GeminiUiNode>(
            { it.copy(password = true) }, { it.copy(enabled = false) }, { it.copy(editable = true) },
            { it.copy(packageName = "other.app") }, { it.copy(resourceId = "popup_menu") })
        for (change in invalid) {
            val nodes = fixture().map { if (it.index == 9) change(it) else it }
            assertFalse(parse(nodes)?.text?.contains("new answer") == true)
        }
    }
    @Test fun hiddenRootOrTruncatedTreeCannotExposeAnything() {
        assertNull(parse(fixture().map { if (it.index == 1) it.copy(visible = false) else it }))
        assertNull(GeminiReplyPageParser.parse(fixture(), question, false))
    }
}
