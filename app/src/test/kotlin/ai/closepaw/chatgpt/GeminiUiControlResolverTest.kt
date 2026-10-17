package ai.closepaw.chatgpt

import org.junit.Assert.*
import org.junit.Test

class GeminiUiControlResolverTest {
    private val pkg = AndroidGeminiDraftUi.PACKAGE
    private fun n(index: Int, parent: Int = 0, text: String = "", description: String = "", clickable: Boolean = false, action: Boolean = false) =
        GeminiUiNode(index, parent, packageName = pkg, text = text, description = description, clickable = clickable, clickAction = action)
    private val root get() = n(0, -1)
    private fun targets(vararg nodes: GeminiUiNode) = GeminiUiControlResolver.sendTargets(listOf(root) + nodes)

    @Test fun koreanVisibleTextResolvesWithoutContentDescription() {
        assertEquals(listOf(1), targets(n(1, text = "보내기", clickable = true)))
    }
    @Test fun semanticDescriptionResolvesClickActionEvenWhenClickableFlagIsFalse() {
        assertEquals(listOf(1), targets(n(1, description = "Send message", action = true)))
    }
    @Test fun clickableFlagAloneSupportsSemanticButton() {
        assertEquals(listOf(1), targets(n(1, description = "보내기", clickable = true)))
    }
    @Test fun approvedIdSupportsUnlabelledControl() {
        assertEquals(listOf(1), targets(n(1, action = true).copy(resourceId = "$pkg:id/assistant_robin_send_button")))
    }
    @Test fun childLabelResolvesNearestActionableParent() {
        assertEquals(listOf(1), targets(n(1, clickable = true), n(2, 1, text = "보내기")))
    }
    @Test fun duplicateDescriptionAndChildLabelAreOneControl() {
        assertEquals(listOf(1), targets(n(1, description = "보내기", clickable = true), n(2, 1, text = "보내기")))
    }
    @Test fun atMostThreeAncestorsAreResolved() {
        assertEquals(listOf(1), targets(n(1, clickable = true), n(2, 1), n(3, 2), n(4, 3, text = "보내기")))
        assertTrue(targets(n(1, clickable = true), n(2, 1), n(3, 2), n(4, 3), n(5, 4, text = "보내기")).isEmpty())
    }
    @Test fun multipleIndependentTargetsRemainAmbiguous() {
        assertEquals(listOf(1, 2), targets(n(1, text = "보내기", clickable = true), n(2, text = "send", action = true)))
    }
    @Test fun sharedParentOfIndependentLabelsIsRejected() {
        assertTrue(targets(n(1, clickable = true), n(2, 1, text = "보내기"), n(3, 1, text = "send")).isEmpty())
    }
    @Test fun wrapperContainingComposerIsRejected() {
        assertTrue(targets(n(1, clickable = true), n(2, 1, text = "보내기"), n(3, 1).copy(editable = true, composer = true)).isEmpty())
    }
    @Test fun wrapperContainingOtherClickableControlIsRejected() {
        assertTrue(targets(n(1, clickable = true), n(2, 1, text = "보내기"), n(3, 1, description = "마이크", clickable = true)).isEmpty())
    }
    @Test fun broadPageOrScrollAncestorIsRejected() {
        assertTrue(targets(n(1, clickable = true).copy(className = "android.widget.ScrollView"), n(2, 1, text = "보내기")).isEmpty())
        assertTrue(targets(n(1, clickable = true).copy(resourceId = "$pkg:id/chat_container"), n(2, 1, text = "보내기")).isEmpty())
    }
    @Test fun rootCannotBecomeSendTarget() {
        assertTrue(GeminiUiControlResolver.sendTargets(listOf(root.copy(clickable = true), n(1, text = "보내기"))).isEmpty())
    }
    @Test fun disabledInvisibleEditablePasswordAndComposerLabelsAreRejected() {
        val label = n(1, text = "보내기", action = true)
        for (invalid in listOf(label.copy(enabled = false), label.copy(visible = false), label.copy(editable = true), label.copy(password = true), label.copy(composer = true))) {
            assertTrue(targets(invalid).isEmpty())
        }
    }
    @Test fun disabledOrInvisibleAncestorCannotReceiveClick() {
        assertTrue(targets(n(1, clickable = true).copy(enabled = false), n(2, 1, text = "보내기")).isEmpty())
        assertTrue(targets(n(1, clickable = true).copy(visible = false), n(2, 1, text = "보내기")).isEmpty())
    }
    @Test fun crossPackageLabelsWrappersAndOtherAppsAreRejected() {
        assertTrue(targets(n(1, clickable = true), n(2, 1, text = "보내기").copy(packageName = AndroidGeminiDraftUi.GOOGLE_HOST)).isEmpty())
        assertTrue(targets(n(1, text = "보내기", clickable = true).copy(packageName = "another.app")).isEmpty())
        assertTrue(targets(n(1, text = "보내기", clickable = true).copy(packageName = AndroidGeminiDraftUi.GOOGLE_HOST)).isEmpty())
    }
    @Test fun googleHostedGeminiControlIsSupportedWithConsistentPackage() {
        val google = AndroidGeminiDraftUi.GOOGLE_HOST
        assertEquals(listOf(1), GeminiUiControlResolver.sendTargets(listOf(root.copy(packageName = google), n(1, text = "보내기", action = true).copy(packageName = google))))
    }
    @Test fun arbitrarySearchOrFeedbackLabelCannotBecomeSend() {
        assertTrue(targets(n(1, text = "Search", action = true), n(2, text = "검색", action = true), n(3, text = "Send feedback", action = true)).isEmpty())
    }
    @Test fun cyclesMissingParentsAndDuplicateIndicesFailClosed() {
        assertTrue(targets(n(1, 2, text = "보내기"), n(2, 1)).isEmpty())
        assertTrue(targets(n(1, 99, text = "보내기", action = true)).isEmpty())
        assertTrue(targets(n(1, text = "보내기", action = true), n(1, text = "send", action = true)).isEmpty())
    }
    @Test fun exactKoreanCopyAndFeedbackCanBeIdentifiedFromEitherField() {
        assertTrue(GeminiUiControlResolver.isCopyRole(n(1, text = "복사")))
        assertTrue(GeminiUiControlResolver.isFeedbackRole(n(1, description = "대답이 마음에 들어요")))
        assertTrue(GeminiUiControlResolver.isFeedbackRole(n(1, text = "대답이 마음에 들지 않아요")))
        assertFalse(GeminiUiControlResolver.isCopyRole(n(1, text = "복사에 대해 설명해줘")))
    }
}
