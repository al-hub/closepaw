package ai.closepaw.chatgpt

import org.junit.Assert.*
import org.junit.Test

/** Representative accessibility projections, not assertions about an unrecorded device tree. */
class GeminiConversationParserTest {
    private val pkg = "com.google.android.apps.bard"
    private val question = "현재 위치를 알려줘"
    private fun node(index: Int, text: String = "", description: String = "", parent: Int = 0,
                     id: String = "", control: Boolean = false) = GeminiUiNode(index, parent,
        packageName = pkg, resourceId = id, className = if (control) "android.widget.ImageButton" else "android.widget.TextView",
        text = text, description = description, clickable = control, clickAction = control)
    private fun fixture() = listOf(
        node(0, parent = -1), node(1, question), node(2, "첫 번째 문단"), node(3, "두 번째 문단"),
        node(4, description = "대답이 마음에 들어요", control = true),
        node(5, description = "대답이 마음에 들지 않아요", control = true),
        node(6, description = "옵션 다시 실행", control = true), node(7, description = "공유", control = true),
        node(8, description = "복사", control = true), node(9, description = "옵션 더보기", control = true),
        node(10, description = "듣기", control = true), node(11, "Gemini는 AI이며 실수를 할 수 있습니다."),
        node(12, "", id = "composer").copy(editable = true, composer = true),
    )
    private fun parse(nodes: List<GeminiUiNode> = fixture(), expected: String? = question, complete: Boolean = true) =
        GeminiConversationParser.parse(nodes, expected, complete)

    @Test fun exactQuestionAndVerifiedKoreanFeedbackToolbarBoundOneAnswer() {
        val result = parse()
        assertEquals(question, result.submittedQuestion)
        assertEquals("첫 번째 문단\n두 번째 문단", result.replies.single().text)
        assertTrue(result.replies.single().complete)
        assertEquals(false, result.generating)
        assertTrue(result.traversalComplete)
        assertFalse(result.replies.single().id.contains(question))
    }
    @Test fun unverifiedPlainVisibleTextNeverBecomesReply() {
        val result = parse(fixture().filter { it.index !in 4..10 })
        assertEquals(question, result.submittedQuestion)
        assertTrue(result.replies.isEmpty())
        assertNull(result.generating)
    }
    @Test fun plainCopyWithoutDistinctPositiveAndNegativeFeedbackIsInsufficient() {
        for (missing in listOf(setOf(4), setOf(5), setOf(8), setOf(4, 5))) {
            assertTrue(parse(fixture().filter { it.index !in missing }).replies.isEmpty())
        }
    }
    @Test fun composerTextCannotSupplySubmittedQuestion() {
        val nodes = fixture().filter { it.index != 1 }.map { if (it.index == 12) it.copy(text = question) else it }
        assertNull(parse(nodes).submittedQuestion)
        assertTrue(parse(nodes).replies.isEmpty())
    }
    @Test fun questionMatchingIsExactWithoutWhitespaceOrCaseGuessing() {
        for (different in listOf(" $question", "$question ", "$question!")) {
            assertTrue(parse(expected = different).replies.isEmpty())
        }
    }
    @Test fun hiddenQuestionAndHiddenAncestorCannotAnchorResponse() {
        assertTrue(parse(fixture().map { if (it.index == 1) it.copy(visible = false) else it }).replies.isEmpty())
        assertTrue(parse(fixture().map { if (it.index == 0) it.copy(visible = false) else it }).replies.isEmpty())
    }
    @Test fun backgroundPackageCannotProvideQuestionToolbarOrAnswer() {
        val nodes = fixture().map { if (it.index in 1..10) it.copy(packageName = "other.app") else it }
        assertTrue(parse(nodes).replies.isEmpty())
    }
    @Test fun modelAndPopupMenuAreNotCollectedAsReply() {
        for (id in listOf("model_picker", "model_menu", "popup_menu")) {
            val nodes = fixture().map { if (it.index == 2) it.copy(resourceId = id) else it }
            assertTrue(parse(nodes).replies.isEmpty())
        }
    }
    @Test fun disabledOrUnclickableFeedbackDoesNotProveCompletion() {
        assertTrue(parse(fixture().map { if (it.index == 4) it.copy(enabled = false) else it }).replies.isEmpty())
        assertTrue(parse(fixture().map { if (it.index == 8) it.copy(clickable = false, clickAction = false) else it }).replies.isEmpty())
    }
    @Test fun generationStopOverridesVisibleCompletionToolbar() {
        val nodes = fixture() + node(13, description = "응답 중지", control = true)
        val result = parse(nodes)
        assertEquals(true, result.generating)
        assertFalse(result.replies.single().complete)
    }
    @Test fun longAndClippedResponsesAreTruthfullyPartial() {
        val long = parse(fixture().map { if (it.index == 2) it.copy(text = "a".repeat(24001)) else it })
        assertEquals(24000, long.replies.single().text.length)
        assertFalse(long.replies.single().complete); assertFalse(long.traversalComplete)
        val clipped = parse(complete = false)
        assertFalse(clipped.replies.single().complete); assertFalse(clipped.traversalComplete)
    }
    @Test fun hiddenResponseFragmentIsExcludedAndPreventsCompletion() {
        val result = parse(fixture().map { if (it.index == 2) it.copy(visible = false) else it })
        assertEquals("두 번째 문단", result.replies.single().text)
        assertFalse(result.replies.single().complete); assertFalse(result.traversalComplete)
    }
    @Test fun toolbarAndDisclaimerNeverAppearInsideReturnedText() {
        val result = parse(fixture().map { if (it.index in 4..10) it.copy(text = it.description, description = "") else it })
        assertEquals("첫 번째 문단\n두 번째 문단", result.replies.single().text)
    }
    @Test fun textAfterToolbarCannotBeSilentlyFoldedIntoPriorQuestionAnswer() {
        val nodes = fixture().map { if (it.index == 11) it.copy(text = "나중에 물은 다른 질문") else it }
        assertTrue(parse(nodes).replies.isEmpty())
    }
    @Test fun priorTextAndPriorToolbarBeforeCurrentQuestionAreNotReturned() {
        val nodes = listOf(node(0, parent = -1), node(1, "이전 질문"), node(2, "이전 답변"),
            node(3, description = "대답이 마음에 들어요", control = true),
            node(4, description = "대답이 마음에 들지 않아요", control = true), node(5, description = "복사", control = true)) +
            fixture().drop(1).map { it.copy(index = it.index + 5) }
        assertEquals("첫 번째 문단\n두 번째 문단", parse(nodes).replies.single().text)
    }
    @Test fun changingQuestionChangesIdentityButGrowingAnswerDoesNot() {
        val original = parse().replies.single().id
        assertEquals(original, parse(fixture().map { if (it.index == 2) it.copy(text = "추가 생성 문단") else it }).replies.single().id)
        val other = parse(fixture().map { if (it.index == 1) it.copy(text = "다른 질문") else it }, "다른 질문")
        assertNotEquals(original, other.replies.single().id)
    }
    @Test fun repeatedExactQuestionsAreAmbiguousRatherThanChoosingOldAnswer() {
        val nodes = fixture() + node(13, question)
        assertTrue(parse(nodes).replies.isEmpty())
    }
    @Test fun nestedQuestionAndAnswerTextIsNotDuplicated() {
        val nodes = fixture().flatMap {
            if (it.index == 1) listOf(it, node(101, question, parent = 1))
            else if (it.index == 2) listOf(it, node(102, it.text, parent = 2)) else listOf(it)
        }
        assertEquals("첫 번째 문단\n두 번째 문단", parse(nodes).replies.single().text)
    }
    @Test fun explicitReplyIdsPreserveOldBaselineBeforeNewQuestionAppears() {
        val nodes = listOf(node(0, parent = -1), node(1, "이전 질문", id = "assistant_robin_query_text"),
            node(2, id = "assistant_robin_response_container"), node(3, "이전 답변", parent = 2),
            node(4, parent = 2, id = "assistant_robin_copy_button", control = true),
            node(5, question).copy(composer = true, editable = true))
        val baseline = parse(nodes, expected = null)
        val ready = parse(nodes)
        assertEquals("이전 질문", ready.submittedQuestion)
        assertEquals(baseline, ready)
        assertEquals("이전 답변", ready.replies.single().text)
        assertTrue(ready.replies.single().complete)
    }
    @Test fun explicitReplyWithoutQuestionRemainsReadableForLegacyBaseline() {
        val nodes = listOf(node(0, parent = -1), node(1, id = "assistant_robin_response_container"),
            node(2, "답변", parent = 1), node(3, parent = 1, id = "assistant_robin_copy_button", control = true))
        assertEquals("답변", parse(nodes, expected = null).replies.single().text)
    }
    @Test fun feedbackLabelsInsideActionableWrappersStillCorroborateToolbar() {
        val nodes = fixture().flatMap { node ->
            if (node.index in setOf(4, 5, 8)) listOf(
                node.copy(text = "", description = ""),
                node.copy(index = node.index + 100, parent = node.index, clickable = false, clickAction = false),
            ) else listOf(node)
        }
        assertEquals("첫 번째 문단\n두 번째 문단", parse(nodes).replies.single().text)
        assertTrue(parse(nodes).replies.single().complete)
    }
    @Test fun englishFeedbackAndCopyLabelsAreAlsoExactSemanticRoles() {
        val nodes = fixture().map {
            when (it.index) {
                4 -> it.copy(description = "Good response")
                5 -> it.copy(description = "Bad response")
                8 -> it.copy(description = "Copy")
                else -> it
            }
        }
        assertTrue(parse(nodes).replies.single().complete)
    }
    @Test fun duplicateToolbarCopiesAreAmbiguousRatherThanSelectingOne() {
        val nodes = fixture().dropLast(2) + node(20, description = "복사", control = true) + fixture().takeLast(2)
        assertTrue(parse(nodes).replies.isEmpty())
    }
    @Test fun unknownClickableContentBeforeToolbarIsNotGuessedToBeAnswer() {
        val nodes = fixture().map { if (it.index == 2) it.copy(clickable = true, clickAction = true) else it }
        assertTrue(parse(nodes).replies.isEmpty())
    }
    @Test fun laterExplicitQuestionPreventsCompletingExpectedPreviousQuestion() {
        val nodes = fixture() + node(30, "새로운 다른 질문", id = "user_query")
        val result = parse(nodes)
        assertEquals("새로운 다른 질문", result.submittedQuestion)
        assertTrue(result.replies.isEmpty())
    }
    @Test fun malformedAndCyclicParentsCannotSupplyQuestionOrReply() {
        assertTrue(parse(fixture().map { if (it.index == 1) it.copy(parent = 99) else it }).replies.isEmpty())
        assertTrue(parse(fixture().map { if (it.index == 1) it.copy(parent = 1) else it }).replies.isEmpty())
        assertFalse(parse(fixture() + fixture().last()).traversalComplete)
    }

    @Test fun exactQuestionInsideMenuCannotSupplySubmissionAnchor() {
        val nodes = fixture().map { if (it.index == 1) it.copy(resourceId = "popup_menu") else it }
        assertNull(parse(nodes).submittedQuestion)
        assertTrue(parse(nodes).replies.isEmpty())
    }

    @Test fun globalChromeAfterComposerIsOutsideResponseScope() {
        val nodes = fixture().dropLast(1) + listOf(
            node(12, "첨부파일 추가", control = true),
            node(13, "Gemini에게 물어보세요").copy(editable = true, composer = true),
            node(14, "마이크", control = true), node(15, "Open Gemini Live", control = true),
            node(16, "사이드바 열기", control = true), node(17, "Gemini Argon", control = true),
            node(18, "새 채팅", control = true),
        )
        val result = parse(nodes)
        assertEquals("첫 번째 문단\n두 번째 문단", result.replies.single().text)
        assertTrue(result.replies.single().complete)
    }
    @Test fun anotherQuestionBeforeComposerIsStillAmbiguousAfterFooter() {
        val nodes = fixture().dropLast(1) + listOf(
            node(12, "나중에 물은 다른 질문"),
            node(13, "Gemini에게 물어보세요").copy(editable = true, composer = true),
        )
        assertTrue(parse(nodes).replies.isEmpty())
    }
    @Test fun anotherToolbarBeforeComposerCannotBeFoldedIntoOriginalAnswer() {
        val nodes = fixture().dropLast(1) + listOf(
            node(12, "새로운 질문"), node(13, "새로운 답변"),
            node(14, "대답이 마음에 들어요", control = true),
            node(15, "대답이 마음에 들지 않아요", control = true), node(16, "복사", control = true),
            node(17, "Gemini에게 물어보세요").copy(editable = true, composer = true),
        )
        assertTrue(parse(nodes).replies.isEmpty())
    }

    @Test fun descriptionOnlyQuestionAndScopedParagraphsAreReadable() {
        val nodes = fixture().map { if (it.index in 1..3) it.copy(description = it.text, text = "") else it }
        val result = parse(nodes)
        assertEquals(question, result.submittedQuestion)
        assertEquals("첫 번째 문단\n두 번째 문단", result.replies.single().text)
        assertTrue(result.replies.single().complete)
    }
    @Test fun conflictingDescriptionCannotOverrideQuestionText() {
        val nodes = fixture().map { if (it.index == 1) it.copy(text = "다른 질문", description = question) else it }
        assertNull(parse(nodes).submittedQuestion)
        assertTrue(parse(nodes).replies.isEmpty())
    }
    @Test fun bodyTextWinsOverConflictingDescriptionWithoutDuplicateContent() {
        val nodes = fixture().map { if (it.index in 2..3) it.copy(description = "다른 보조 설명") else it }
        assertEquals("첫 번째 문단\n두 번째 문단", parse(nodes).replies.single().text)
    }
    @Test fun descriptionOnlyTextStillNeedsQuestionAndThreeToolbarRoles() {
        val nodes = fixture().filter { it.index != 5 }.map { if (it.index in 1..3) it.copy(description = it.text, text = "") else it }
        assertTrue(parse(nodes).replies.isEmpty())
    }
    @Test fun descriptionOnlyComposerCannotMasqueradeAsSubmittedQuestion() {
        val nodes = fixture().filter { it.index != 1 }.map { if (it.index == 12) it.copy(description = question) else it }
        assertNull(parse(nodes).submittedQuestion)
        assertTrue(parse(nodes).replies.isEmpty())
    }
    @Test fun descriptionOnlyHiddenBodyRemainsPartialAndNeverLeaks() {
        val nodes = fixture().map { if (it.index == 2) it.copy(text = "", description = "숨겨진 본문", visible = false) else it }
        val result = parse(nodes)
        assertEquals("두 번째 문단", result.replies.single().text)
        assertFalse(result.traversalComplete)
        assertFalse(result.replies.single().complete)
    }
    @Test fun descriptionOnlyDisclaimerAndChromeAreNotPartOfAnswer() {
        val nodes = fixture().map { if (it.index == 11) it.copy(description = it.text, text = "") else it }
        assertEquals("첫 번째 문단\n두 번째 문단", parse(nodes).replies.single().text)
    }

    @Test fun genericTextEvidenceContainsOnlyChosenBodyFragments() {
        val evidence = parse().replies.single().textEvidence
        assertEquals(setOf(GeminiUiTextEvidence.hash("첫 번째 문단"), GeminiUiTextEvidence.hash("두 번째 문단")), evidence)
        for (label in listOf(question, "대답이 마음에 들어요", "대답이 마음에 들지 않아요", "복사", "Gemini는 AI이며 실수를 할 수 있습니다.")) {
            assertFalse(evidence.contains(GeminiUiTextEvidence.hash(label)))
        }
    }
    @Test fun explicitTextEvidenceContainsBodyRatherThanQueryOrControls() {
        val nodes = listOf(node(0, parent = -1), node(1, question, id = "user_query"),
            node(2, id = "assistant_robin_response_container"), node(3, "답변 본문", parent = 2),
            node(4, parent = 2, id = "assistant_robin_copy_button", control = true))
        assertEquals(setOf(GeminiUiTextEvidence.hash("답변 본문")), parse(nodes).replies.single().textEvidence)
    }
    @Test fun textEvidenceIsStableAcrossTextOrDescriptionRepresentation() {
        val descriptions = fixture().map { if (it.index in 1..3) it.copy(description = it.text, text = "") else it }
        assertEquals(parse().replies.single().textEvidence, parse(descriptions).replies.single().textEvidence)
    }
    @Test fun textEvidenceUsesSelectedParentOnceWithoutDuplicateNestedFragments() {
        val nodes = fixture().flatMap {
            if (it.index == 2) listOf(it, node(102, it.text, parent = 2)) else listOf(it)
        }
        assertEquals(parse().replies.single().textEvidence, parse(nodes).replies.single().textEvidence)
    }

}
