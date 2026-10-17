package ai.closepaw.chatgpt

import ai.closepaw.app.AgentService
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
    @Test fun exactlyOneSemanticSendIsClickedOnce() = runBlocking {
        val send = node("assistant_robin_send_button", clickable = true)
        root = node(children = listOf(composer(), send))
        val ui = adapter(); val snapshot = ui.snapshot()
        assertEquals(1, snapshot.sendCandidates)
        assertEquals(GeminiSendResult.CLICKED, ui.sendIfMatches("question", snapshot.targetKey))
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
