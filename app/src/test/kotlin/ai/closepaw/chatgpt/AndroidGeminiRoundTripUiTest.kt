package ai.closepaw.chatgpt

import ai.closepaw.app.AgentService
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import ai.closepaw.chatgpt.operations.OperationInvocation
import kotlinx.serialization.json.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercise the real bounded tree extraction and guarded click, rather than a fake snapshot. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidGeminiRoundTripUiTest {
    private val service = mockk<AgentService>(relaxed = true)
    private val draft = mockk<AndroidGeminiDraftUi>(relaxed = true)
    private var root: AccessibilityNodeInfo? = null
    private val native = AndroidGeminiDraftUi.PACKAGE
    private val google = AndroidGeminiDraftUi.GOOGLE_HOST

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { draft.isLocked() } returns false
        every { draft.foregroundRoot() } answers { root }
        every { draft.evidence() } answers { DraftWindowEvidence(rootPackage = root?.packageName?.toString(), windowId = 7) }
        every { draft.isComposer(any()) } answers { firstArg<AccessibilityNodeInfo>().viewIdResourceName?.endsWith("/assistant_robin_input_text") == true }
    }
    @After fun teardown() { unmockkAll(); Dispatchers.resetMain() }
    private fun node(
        id: String = "", text: String? = null, children: List<AccessibilityNodeInfo> = emptyList(),
        pkg: String = native, editable: Boolean = false, clickable: Boolean = false, visible: Boolean = true,
    ): AccessibilityNodeInfo = mockk<AccessibilityNodeInfo>(relaxed = true).also { n ->
        every { n.packageName } returns pkg
        every { n.windowId } returns 7
        every { n.viewIdResourceName } returns "$pkg:id/$id"
        every { n.className } returns "android.view.View"
        every { n.contentDescription } returns null
        every { n.text } returns text
        every { n.isVisibleToUser } returns visible
        every { n.isEnabled } returns true
        every { n.isEditable } returns editable
        every { n.isClickable } returns clickable
        every { n.isPassword } returns false
        every { n.isShowingHintText } returns false
        every { n.childCount } returns children.size
        every { n.getChild(any()) } answers { children.getOrNull(firstArg()) }
        every { n.refresh() } returns true
        val click = mockk<AccessibilityNodeInfo.AccessibilityAction>()
        every { click.id } returns AccessibilityNodeInfo.ACTION_CLICK
        every { n.actionList } returns if (clickable) listOf(click) else emptyList()
        every { n.performAction(any()) } returns true
    }
    private fun composer(pkg: String = native) = node("assistant_robin_input_text", "question", pkg = pkg, editable = true)
    private fun adapter() = AndroidGeminiRoundTripUi(service, { true }, draft)

    @Test fun genericGoogleSearchCannotSupplyComposerOrSend() = runBlocking {
        val search = node("search_box", "query", pkg = google, editable = true)
        val send = node("send_button", pkg = google, clickable = true)
        root = node(children = listOf(search, send), pkg = google)
        val ui = adapter()
        assertFalse(ui.snapshot().foreground)
        assertEquals(GeminiSendResult.TARGET_CHANGED, ui.sendIfMatches("query", "$google:7"))
        verify(exactly = 0) { send.performAction(any()) }
    }
    @Test fun wholeRoundTripUsesHostedChildSendAndQuestionAnchoredReplyToolbar() = runBlocking {
        val question = "short test question"
        val input = node("assistant_robin_input_text", "", pkg = google, editable = true)
        val label = node(text = "보내기", pkg = google)
        val send = node("compose_button", children = listOf(label), pkg = google, clickable = true)
        root = node(children = listOf(input, send), pkg = google)
        coEvery { draft.launch() } returns true
        coEvery { draft.setIfEmpty(question) } answers {
            every { input.text } returns question
            DraftWriteResult.WRITTEN
        }
        coEvery { draft.matches(question) } returns true
        every { send.performAction(AccessibilityNodeInfo.ACTION_CLICK) } answers {
            every { input.text } returns ""
            root = node(children = listOf(
                node(text = question, pkg = google),
                node(text = "verified answer", pkg = google),
                node(text = "대답이 마음에 들어요", pkg = google, clickable = true),
                node(text = "대답이 마음에 들지 않아요", pkg = google, clickable = true),
                node(text = "복사", pkg = google, clickable = true),
                node(text = "Gemini는 AI이며 실수를 할 수 있습니다.", pkg = google),
                input, node(text = "Gemini Argon", pkg = google), node(text = "새 채팅", pkg = google),
            ), pkg = google)
            true
        }
        val ui = adapter(); var time = 0L
        val events = mutableListOf<String>()
        val op = GeminiRoundTripOperation(ui, { true }, { _, stage, _ -> events.add(stage); Unit }, { timeout ->
            GeminiReplyCollector(ui, clockMillis = { time }, pause = { time += it }, timeoutMs = timeout,
                pollMs = 100, stableMs = 100)
        })
        val result = op.invoke(OperationInvocation("hosted-flow", buildJsonObject { put("text", question) }))
        assertTrue(result.summary, result.success)
        assertEquals("verified answer", result.stdout)
        assertTrue(result.data.getValue("sent").jsonPrimitive.boolean)
        assertEquals("semantic_complete_and_stable", result.data.getValue("completion_evidence").jsonPrimitive.content)
        assertTrue(events.indexOf("submitting") < events.indexOf("send_action_invoked"))
        verify(exactly = 1) { send.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { label.performAction(any()) }
        val metadata = ui.metadataSnapshot().toString()
        assertFalse(metadata.contains(question)); assertFalse(metadata.contains("verified answer"))
    }
    @Test fun actualAdapterScrollsOnlyVerifiedConversationAndCollectsLongReply() = runBlocking {
        val question = "long test question"
        val input = node("assistant_robin_input_text", "", pkg = google, editable = true)
        val send = node("send_button", pkg = google, clickable = true)
        val firstPage = node("conversation", children = listOf(node(text = question, pkg = google),
            node(text = "first paragraph\nshared paragraph", pkg = google)), pkg = google)
        val lastPage = node("conversation", children = listOf(node(text = "shared paragraph\nlast paragraph", pkg = google),
            node(text = "대답이 마음에 들어요", pkg = google, clickable = true),
            node(text = "대답이 마음에 들지 않아요", pkg = google, clickable = true),
            node(text = "복사", pkg = google, clickable = true)), pkg = google)
        val forward = mockk<AccessibilityNodeInfo.AccessibilityAction>()
        every { forward.id } returns AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val backward = mockk<AccessibilityNodeInfo.AccessibilityAction>()
        every { backward.id } returns AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        every { firstPage.isScrollable } returns true
        every { firstPage.actionList } returns listOf(forward)
        every { lastPage.isScrollable } returns true
        every { lastPage.actionList } returns listOf(backward)
        root = node(children = listOf(input, send), pkg = google)
        coEvery { draft.launch() } returns true
        coEvery { draft.setIfEmpty(question) } answers { every { input.text } returns question; DraftWriteResult.WRITTEN }
        coEvery { draft.matches(question) } returns true
        every { send.performAction(AccessibilityNodeInfo.ACTION_CLICK) } answers {
            every { input.text } returns ""
            root = node(children = listOf(firstPage, input), pkg = google); true
        }
        every { firstPage.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } answers {
            root = node(children = listOf(lastPage, input), pkg = google); true
        }
        val ui = adapter(); var time = 0L
        val op = GeminiRoundTripOperation(ui, { true }, { _, _, _ -> }, { timeout ->
            GeminiReplyCollector(ui, clockMillis = { time }, pause = { time += it }, timeoutMs = timeout,
                pollMs = 100, stableMs = 100)
        })
        val result = op.invoke(OperationInvocation("paged-flow", buildJsonObject { put("text", question) }))
        assertTrue(result.summary, result.success)
        assertEquals("first paragraph\nshared paragraph\nlast paragraph", result.stdout)
        assertEquals("complete", result.data.getValue("coverage").jsonPrimitive.content)
        assertEquals(2, result.data.getValue("pages_collected").jsonPrimitive.int)
        verify(exactly = 1) { send.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 1) { firstPage.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
        verify(exactly = 0) { input.performAction(any()) }
    }

    @Test fun completeVisibleShortTurnRemainsValidWhenOuterScrollerContainsComposer() = runBlocking {
        val question = "short visible turn"
        val input = node("assistant_robin_input_text", "", pkg = google, editable = true)
        coEvery { draft.setIfEmpty(question) } returns DraftWriteResult.WRITTEN
        val ui = adapter(); ui.setIfEmpty(question)
        root = node(children = listOf(node(text = question, pkg = google), node(text = "short answer", pkg = google),
            node(text = "대답이 마음에 들어요", pkg = google, clickable = true),
            node(text = "대답이 마음에 들지 않아요", pkg = google, clickable = true),
            node(text = "복사", pkg = google, clickable = true), input), pkg = google)
        every { root!!.isScrollable } returns true
        val back = mockk<AccessibilityNodeInfo.AccessibilityAction>()
        every { back.id } returns AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        every { root!!.actionList } returns listOf(back)
        val state = ui.snapshot()
        assertNull(state.replyPage)
        assertTrue(state.replies.single().complete)
        assertTrue(state.replyCoverageComplete)
    }

    @Test fun nativeOffscreenNewResponseIsCollectedBeforeScrollingToItsOwnToolbar() = runBlocking {
        val question = "new offscreen native test"
        val input = node("assistant_robin_input_text", "", pkg = google, editable = true)
        val send = node("send_button", pkg = google, clickable = true)
        val old = node("assistant_robin_content_message_container", children = listOf(
            node("assistant_robin_text", "old answer", pkg = google), node(text = "복사", pkg = google, clickable = true),
            node(text = "대답이 마음에 들어요", pkg = google, clickable = true), node(text = "대답이 마음에 들지 않아요", pkg = google, clickable = true)), pkg = google)
        val body = node("assistant_robin_text", "new answer first item through last item", pkg = google, visible = false)
        val copy = node(text = "복사", pkg = google, clickable = true, visible = false)
        val up = node(text = "대답이 마음에 들어요", pkg = google, clickable = true, visible = false)
        val down = node(text = "대답이 마음에 들지 않아요", pkg = google, clickable = true, visible = false)
        val reply = node("assistant_robin_content_message_container", children = listOf(body, copy, up, down), pkg = google, visible = false)
        val history = node("assistant_robin_chat_history_list", children = listOf(old,
            node("assistant_robin_user_message_text", question, pkg = google), reply), pkg = google)
        val forward = mockk<AccessibilityNodeInfo.AccessibilityAction>()
        every { forward.id } returns AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val backward = mockk<AccessibilityNodeInfo.AccessibilityAction>()
        every { backward.id } returns AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        every { history.isScrollable } returns true
        every { history.actionList } returns listOf(forward, backward)
        every { history.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } answers {
            every { old.isVisibleToUser } returns false
            listOf(reply, body, copy, up, down).forEach { every { it.isVisibleToUser } returns true }
            every { history.actionList } returns listOf(backward)
            true
        }
        root = node(children = listOf(input, send), pkg = google)
        coEvery { draft.launch() } returns true
        coEvery { draft.setIfEmpty(question) } answers { every { input.text } returns question; DraftWriteResult.WRITTEN }
        coEvery { draft.matches(question) } returns true
        every { send.performAction(AccessibilityNodeInfo.ACTION_CLICK) } answers {
            every { input.text } returns ""; root = node(children = listOf(history, input), pkg = google); true
        }
        val ui = adapter(); var time = 0L
        val op = GeminiRoundTripOperation(ui, { true }, { _, _, _ -> }, { timeout ->
            GeminiReplyCollector(ui, clockMillis = { time }, pause = { time += it }, timeoutMs = timeout,
                pollMs = 100, stableMs = 100)
        })
        val result = op.invoke(OperationInvocation("native-offscreen-flow", buildJsonObject { put("text", question) }))
        assertTrue(result.summary, result.success)
        assertEquals("new answer first item through last item", result.stdout)
        assertFalse(result.stdout.contains("old answer"))
        verify(exactly = 1) { send.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 1) { history.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
        verify(exactly = 0) { copy.performAction(any()) }
    }

    @Test fun exactlyOneSemanticSendIsClickedOnce() = runBlocking {
        val send = node("assistant_robin_send_button", clickable = true)
        root = node(children = listOf(composer(), send))
        val ui = adapter(); val snapshot = ui.snapshot()
        assertEquals(1, snapshot.sendCandidates)
        assertEquals(GeminiSendResult.CLICKED, ui.sendIfMatches("question", snapshot.targetKey))
        verify(exactly = 1) { send.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
    @Test fun visibleTextSendInGoogleHostedGeminiIsRecognizedAndClicked() = runBlocking {
        val send = node(text = "보내기", pkg = google, clickable = true)
        root = node(children = listOf(composer(google), send), pkg = google)
        val ui = adapter(); val state = ui.snapshot()
        assertEquals(1, state.sendCandidates)
        assertEquals(GeminiSendResult.CLICKED, ui.sendIfMatches("question", state.targetKey))
        verify(exactly = 1) { send.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
    @Test fun semanticLabelChildResolvesOnlyItsNearestClickableParent() = runBlocking {
        val label = node(text = "보내기")
        val button = node("compose_button", children = listOf(label), clickable = true)
        root = node(children = listOf(composer(), button))
        val ui = adapter(); val state = ui.snapshot()
        assertEquals(1, state.sendCandidates)
        assertEquals(GeminiSendResult.CLICKED, ui.sendIfMatches("question", state.targetKey))
        verify(exactly = 1) { button.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { label.performAction(any()) }
    }
    @Test fun semanticClickableControlDoesNotRequireDuplicatedClickActionFlag() = runBlocking {
        val send = node("assistant_robin_send_button", clickable = true)
        every { send.actionList } returns emptyList()
        root = node(children = listOf(composer(), send))
        val ui = adapter(); val state = ui.snapshot()
        assertEquals(1, state.sendCandidates)
        assertEquals(GeminiSendResult.CLICKED, ui.sendIfMatches("question", state.targetKey))
        verify(exactly = 1) { send.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
    @Test fun ambiguousSemanticSendDoesNotClickEitherControl() = runBlocking {
        val one = node("assistant_robin_send_button", clickable = true)
        val two = node("robin_send_button", clickable = true)
        root = node(children = listOf(composer(), one, two))
        val ui = adapter(); val snapshot = ui.snapshot()
        assertEquals(2, snapshot.sendCandidates)
        assertEquals(GeminiSendResult.REJECTED, ui.sendIfMatches("question", snapshot.targetKey))
        verify(exactly = 0) { one.performAction(any()) }
        verify(exactly = 0) { two.performAction(any()) }
    }
    @Test fun semanticResponseSubtreeProvidesAnswerAndCompletionEvidence() = runBlocking {
        val copy = node("assistant_robin_copy_button", clickable = true)
        val answer = node("assistant_robin_response_container", children = listOf(node(text = "answer"), copy))
        root = node(children = listOf(composer(), node("assistant_robin_query_text", "question"), answer, node("model_picker", "irrelevant model menu")))
        val snapshot = adapter().snapshot()
        assertEquals("question", snapshot.submittedQuestion)
        assertEquals("answer", snapshot.replies.single().text)
        assertTrue(snapshot.replies.single().complete)
        assertEquals(false, snapshot.generating)
        assertTrue(snapshot.traversalComplete)
    }
    @Test fun nestedResponseRolesProduceOneOrderedAnswerWithParentCompletion() = runBlocking {
        val first = node("assistant_robin_response_text", "first fragment", children = listOf(node(text = "first fragment")))
        val second = node("robin_response_text", "second fragment")
        val answer = node("assistant_robin_response_container", children = listOf(first, second, node("assistant_robin_copy_button", clickable = true)))
        root = node(children = listOf(composer(), answer))
        val snapshot = adapter().snapshot()
        assertEquals(1, snapshot.replies.size)
        assertEquals("first fragment\nsecond fragment", snapshot.replies.single().text)
        assertTrue(snapshot.replies.single().complete)
        assertTrue(snapshot.traversalComplete)
    }
    @Test fun oversizedResponseRootTextIsBoundedAndPartial() = runBlocking {
        val answer = node("assistant_robin_response_container", "a".repeat(24001), children = listOf(node("assistant_robin_copy_button", clickable = true)))
        root = node(children = listOf(composer(), answer))
        val snapshot = adapter().snapshot()
        assertEquals(24000, snapshot.replies.single().text.length)
        assertFalse(snapshot.replies.single().complete)
        assertFalse(snapshot.traversalComplete)
    }
    @Test fun hiddenResponseTextPreventsFullCompletion() = runBlocking {
        val answer = node("assistant_robin_response_container", children = listOf(
            node(text = "visible"), node(text = "hidden", visible = false), node("assistant_robin_copy_button", clickable = true),
        ))
        root = node(children = listOf(composer(), answer))
        val snapshot = adapter().snapshot()
        assertEquals("visible", snapshot.replies.single().text)
        assertFalse(snapshot.replies.single().complete)
        assertFalse(snapshot.traversalComplete)
    }
    @Test fun responseTraversalLimitCannotClaimCompleteAnswer() = runBlocking {
        val answer = node("assistant_robin_response_container", children = List(101) { node(text = "fragment $it") })
        root = node(children = listOf(composer(), answer))
        val snapshot = adapter().snapshot()
        assertFalse(snapshot.traversalComplete)
        assertFalse(snapshot.replies.single().complete)
    }
    @Test fun changedConversationInSameWindowPreventsClick() = runBlocking {
        val send = node("assistant_robin_send_button", clickable = true)
        val question = node("assistant_robin_query_text", "old question")
        root = node(children = listOf(composer(), send, question))
        val ui = adapter(); val baseline = ui.snapshot()
        every { question.text } returns "different question"
        assertEquals(GeminiSendResult.TARGET_CHANGED, ui.sendIfMatches("question", baseline.targetKey))
        verify(exactly = 0) { send.performAction(any()) }
    }
}
