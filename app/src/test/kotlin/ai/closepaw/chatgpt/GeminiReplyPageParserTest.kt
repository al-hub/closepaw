package ai.closepaw.chatgpt

import org.junit.Assert.*
import org.junit.Test

class GeminiReplyPageParserTest {
    private val pkg = "com.google.android.apps.bard"
    private val question = "question"
    private fun node(i: Int, text: String = "", parent: Int = 1) = GeminiUiNode(i, parent, packageName = pkg,
        className = "android.view.View", text = text)
    private fun fixture() = listOf(node(0, parent = -1), node(1, parent = 0).copy(resourceId = "conversation",
        className = "android.widget.ScrollView", scrollable = true, scrollForward = true, scrollBackward = true),
        node(2, "older question"), node(3, "older answer"), node(4, question), node(5, "first paragraph"), node(6, "last paragraph"),
        node(7, "대답이 마음에 들어요").copy(clickable = true), node(8, "대답이 마음에 들지 않아요").copy(clickable = true),
        node(9, "복사").copy(clickable = true), node(10, parent = 0).copy(editable = true, composer = true))
    @Test fun onlyCurrentAnswerInsideConversationIsCaptured() {
        val p = GeminiReplyPageParser.parse(fixture(), question, true)!!
        assertTrue(p.questionVisible); assertTrue(p.endVisible)
        assertEquals("first paragraph\nlast paragraph", p.text)
        assertEquals(1, GeminiReplyPageParser.target(fixture(), question, true, p.containerKey, true))
    }
    @Test fun midPageWithoutAnchorOrToolbarRequiresAlreadyValidatedRegion() {
        val initial = GeminiReplyPageParser.parse(fixture(), question, true)!!
        val middle = fixture().filter { it.index !in 2..4 && it.index !in 7..9 }
        assertNull(GeminiReplyPageParser.parse(middle, question, true))
        val p = GeminiReplyPageParser.parse(middle, question, true, initial.containerKey)!!
        assertFalse(p.questionVisible); assertFalse(p.endVisible); assertEquals(initial.text, p.text)
    }
    @Test fun composerContainingRegionOrAmbiguousScrollersAreRejected() {
        val composerInside = fixture().map { if (it.index == 10) it.copy(parent = 1) else it }
        assertNull(GeminiReplyPageParser.parse(composerInside, question, true))
        val extra = fixture() + node(11, parent = 0).copy(scrollable = true, scrollForward = true) + node(12, question, 11)
        assertNull(GeminiReplyPageParser.parse(extra, question, true))
    }
    @Test fun hiddenBackgroundOrTruncatedTreeCannotProveCoverage() {
        assertNull(GeminiReplyPageParser.parse(fixture(), question, false))
        assertNull(GeminiReplyPageParser.parse(fixture().map { if (it.index == 1) it.copy(visible = false) else it }, question, true))
        assertNull(GeminiReplyPageParser.parse(fixture().map { if (it.index in 1..9) it.copy(packageName = "other") else it }, question, true))
    }
    @Test fun laterKnownUserTurnPreventsScrolling() {
        val nodes = fixture() + node(11, "other question").copy(resourceId = "user_query")
        val p = GeminiReplyPageParser.parse(nodes, question, true)!!
        assertTrue(p.conflictingTurn)
        assertNull(GeminiReplyPageParser.target(nodes, question, true, p.containerKey, true))
    }
    @Test fun footerRequiresCopyAndBothFeedbackRoles() {
        for (missing in listOf(7, 8, 9)) assertFalse(GeminiReplyPageParser.parse(fixture().filter { it.index != missing }, question, true)!!.endVisible)
    }
    @Test fun plainFeedbackWordsWithoutDistinctActionsNeverProveCompletion() {
        val nodes = fixture().map { if (it.index in 7..8) it.copy(clickable = false) else it }
        assertFalse(GeminiReplyPageParser.parse(nodes, question, true)!!.endVisible)
    }
    @Test fun recycledToolbarBeforeQuestionInAttachmentOrderUsesScreenGeometry() {
        val original = fixture().map { n -> when (n.index) {
            2, 3 -> n.copy(visible = false)
            4 -> n.copy(resourceId = "assistant_robin_user_message_text", screenTop = -100, screenLeft = 0)
            5 -> n.copy(screenTop = -50, screenLeft = 0)
            6 -> n.copy(screenTop = 300, screenLeft = 0)
            7, 8, 9 -> n.copy(screenTop = 600, screenLeft = n.index * 30)
            else -> n
        } }
        val attached = original.filter { it.index !in 4..9 } + original.filter { it.index in 7..9 } + original.filter { it.index in 4..6 }
        val p = GeminiReplyPageParser.parse(attached, question, true)!!
        assertTrue(p.questionVisible); assertTrue(p.endVisible)
        assertEquals("first paragraph\nlast paragraph", p.text)
    }
    @Test fun nativeUserMessageAfterCurrentAnswerCannotBeCollectedAsBody() {
        val nodes = fixture() + node(11, "another native question").copy(resourceId = "assistant_robin_user_message_text")
        val p = GeminiReplyPageParser.parse(nodes, question, true)!!
        assertTrue(p.conflictingTurn)
        assertNull(GeminiReplyPageParser.target(nodes, question, true, p.containerKey, true))
    }
    @Test fun menuOrActionableAnswerIsRejected() {
        assertNull(GeminiReplyPageParser.parse(fixture().map { if (it.index == 5) it.copy(clickable = true) else it }, question, true))
        assertNull(GeminiReplyPageParser.parse(fixture().map { if (it.index == 1) it.copy(resourceId = "popup_menu") else it }, question, true))
    }
    @Test fun bottomWithNoFurtherScrollActionsStillYieldsFinalPage() {
        val first = GeminiReplyPageParser.parse(fixture(), question, true)!!
        val p = GeminiReplyPageParser.parse(fixture().map { if (it.index == 1) it.copy(scrollForward = false, scrollBackward = false) else it }, question, true, first.containerKey)!!
        assertTrue(p.endVisible); assertFalse(p.canForward)
        assertNull(GeminiReplyPageParser.target(fixture(), question, true, "wrong", true))
    }
}
