package ai.closepaw.chatgpt

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** Sanitized structural reproduction of the code83 timeout metadata; no captured user text. */
class GeminiNativeShortReplyTest {
    private val pkg = "com.google.android.googlequicksearchbox"
    private val question = "synthetic question"
    private fun n(i: Int, parent: Int, id: String = "", text: String = "", action: Boolean = false) =
        GeminiUiNode(i, parent, packageName = pkg, resourceId = "$pkg:id/$id", text = text,
            className = "android.view.View", clickable = action, clickAction = action)
    private fun fixture() = listOf(n(0, -1), n(1, 0, "assistant_robin_chat_history_list"),
        n(2, 1, "assistant_robin_user_message_container"),
        n(3, 2, "assistant_robin_user_message_text", question),
        n(4, 1, "assistant_robin_content_message_container"), n(5, 4, "assistant_robin_text", "42"),
        n(6, 1), n(7, 6, "assistant_robin_message_content_actions_section"),
        n(8, 7, "assistant_robin_content_actions_scroll_view").copy(className = "android.widget.HorizontalScrollView", scrollable = true, scrollForward = true),
        n(9, 8, text = "대답이 마음에 들어요", action = true),
        n(10, 8, text = "대답이 마음에 들지 않아요", action = true), n(11, 8, text = "복사", action = true),
        n(12, 8, "assistant_robin_code_button", action = true),
        n(13, 1, "assistant_robin_mode_boundary_note_body", "non-answer status note"),
        n(14, 0).copy(composer = true, editable = true))
    private fun parse(nodes: List<GeminiUiNode> = fixture(), complete: Boolean = true) =
        GeminiReplyPageParser.parse(nodes, question, complete)
    @Test fun nonScrollableNativeHistoryAndSeparateToolbarExposeOnlyCurrentBody() {
        val p = parse()!!
        assertEquals("42", p.text); assertTrue(p.questionVisible); assertTrue(p.endVisible)
        assertTrue(p.nativeWholeBody); assertFalse(p.canForward); assertFalse(p.canBackward)
        assertNull(GeminiReplyPageParser.target(fixture(), question, true, p.containerKey, true))
    }
    @Test fun completionRequiresThreeDistinctVisibleSafeControls() {
        for (i in 9..11) {
            assertFalse(parse(fixture().map { if (it.index == i) it.copy(visible = false) else it })!!.endVisible)
            assertFalse(parse(fixture().map { if (it.index == i) it.copy(enabled = false) else it })!!.endVisible)
        }
    }
    @Test fun anotherTurnOrAnotherBodyCannotBorrowTheToolbar() {
        assertTrue(parse(fixture() + n(15, 1, "assistant_robin_user_message_text", "later"))!!.conflictingTurn)
        assertTrue(parse(fixture() + n(15, 1, "assistant_robin_content_message_container"))!!.conflictingTurn)
        assertFalse(parse(fixture().map { if (it.index == 6) it.copy(parent = 0) else it })!!.endVisible)
    }
    @Test fun untrustedOrTruncatedOrHiddenHistoryCannotBeRead() {
        assertNull(parse(complete = false))
        assertNull(parse(fixture().map { if (it.index == 1) it.copy(visible = false) else it }))
        assertNull(parse(fixture().map { it.copy(packageName = "other.app") }))
    }
    @Test fun unknownNonScrollableGenericRegionIsNotPromotedToNative() {
        assertNull(parse(fixture().map { if (it.index == 1) it.copy(resourceId = "unknown_list") else it }))
    }
    @Test fun parsedShortReplyCompletesOnlyAfterStabilityAndDoesNotScrollOrSend() = runBlocking {
        val page = parse()!!
        val baseline = GeminiUiSnapshot(true, "test:1")
        val frame = baseline.copy(submittedQuestion = question, replyPage = page)
        var now = 0L
        val ui = object : GeminiRoundTripUi {
            override suspend fun snapshot() = frame
            override suspend fun isLocked() = false
            override suspend fun launch(): Boolean = error("must not launch")
            override suspend fun inspect(): DraftComposerState = error("must not inspect")
            override suspend fun setIfEmpty(text: String): DraftWriteResult = error("must not write")
            override suspend fun matches(text: String) = false
            override suspend fun sendIfMatches(text: String, targetKey: String): GeminiSendResult = error("must not send")
            override suspend fun scrollReply(targetKey: String, containerKey: String, forward: Boolean): Boolean = error("must not scroll")
        }
        val result = GeminiReplyCollector(ui, clockMillis = { now }, pause = { now += it },
            timeoutMs = 1000, stableMs = 400).collect(question, baseline)
        assertEquals("completed", result.state); assertEquals("42", result.answer)
        assertTrue(now >= 400)
        now = 0L
        val stale = GeminiReplyCollector(ui, clockMillis = { now }, pause = { now += it },
            timeoutMs = 1000).collect(question, baseline.copy(observedTextEvidence = page.textEvidence))
        assertEquals("reply_prior_text_overlap", stale.reason)
        assertNotEquals("completed", stale.state)
    }

}
