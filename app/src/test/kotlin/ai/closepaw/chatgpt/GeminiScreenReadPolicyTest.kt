package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Play Store Gemini Android app is a native UI, not a browser page. */
class GeminiScreenReadPolicyTest {
    @Test fun modelPickerIsNeverMisrepresentedAsGeminiAnswer() {
        val screen = """
            Gemini Flash
            3.5 Flash-Lite
            가장 빠른 답변 제공
            3.8 Flash
            무엇이든 도움을 받으세요
            3.1 Pro
            고급 추론
            최저가
            빠르고 효율적
            보통
            균형 잡힌 깊이
            최고가
            매우 철저함
            Gemini에게 물어보...
        """.trimIndent()
        val result = GeminiScreenReadPolicy.assess(screen, hasScreenshot = true)
        assertThat(result.screenKind).isEqualTo("model_selector")
        assertThat(result.replyStatus).isEqualTo("not_visible")
        assertThat(result.readQuality).isEqualTo("ok")
        assertThat(result.shouldOcr).isFalse()
        assertThat(GeminiScreenReadPolicy.forModel(result, screen)).contains("AI 답변이 아닙니다")
    }

    @Test fun emptyConversationComposerIsNotAnAnswer() {
        val screen = "Gemini Flash\n무엇이든 물어보세요\nGemini에게 물어보..."
        val result = GeminiScreenReadPolicy.assess(screen, hasScreenshot = true)
        assertThat(result.screenKind).isEqualTo("empty_composer")
        assertThat(result.replyStatus).isEqualTo("not_visible")
        assertThat(GeminiScreenReadPolicy.forModel(result, screen)).contains("답변이 보이지 않습니다")
    }

    @Test fun generatingIndicatorCannotBeCalledCompletedReply() {
        val result = GeminiScreenReadPolicy.assess(
            "Gemini\n답변 생성 중\n서울은 대한민국의 수도입니다.",
            hasScreenshot = true,
        )
        assertThat(result.screenKind).isEqualTo("generating")
        assertThat(result.replyStatus).isEqualTo("incomplete")
        assertThat(GeminiScreenReadPolicy.forModel(result, "서울은 대한민국의 수도입니다."))
            .contains("생성 중")
    }

    @Test fun visibleConversationTextIsKeptButNotAttributedToAssistantWithoutRoleEvidence() {
        val screen = "Gemini Flash\n내 질문: 대한민국의 수도는?\n대한민국의 수도는 서울입니다."
        val result = GeminiScreenReadPolicy.assess(screen, hasScreenshot = true)
        assertThat(result.screenKind).isEqualTo("conversation")
        assertThat(result.replyStatus).isEqualTo("unverified")
        assertThat(GeminiScreenReadPolicy.forModel(result, screen)).contains("대한민국의 수도는 서울입니다.")
        assertThat(GeminiScreenReadPolicy.forModel(result, screen)).contains("구분은 확정되지 않았습니다")
    }

    @Test fun chromeOnlyAndroidUiTriggersLocalOcrFallback() {
        val screen = "Gemini\n뒤로가기\n홈\n최근 앱"
        val before = GeminiScreenReadPolicy.assess(screen, hasScreenshot = false)
        assertThat(before.readQuality).isEqualTo("empty")
        assertThat(before.shouldOcr).isTrue()
        val after = GeminiScreenReadPolicy.assess(screen, hasScreenshot = true)
        assertThat(after.readQuality).isEqualTo("content_missing")
        assertThat(after.shouldOcr).isTrue()
        assertThat(after.replyStatus).isEqualTo("not_visible")
    }

    @Test fun screenshotsWithoutTextDoNotInventGeminiReply() {
        val result = GeminiScreenReadPolicy.assess("", hasScreenshot = true)
        assertThat(result.screenKind).isEqualTo("no_readable_text")
        assertThat(result.readQuality).isEqualTo("content_missing")
        assertThat(GeminiScreenReadPolicy.forModel(result, "")).contains("확인되지 않았습니다")
    }

    @Test fun browserPageQualityRemainsIndependent() {
        assertThat(BrowserReadQuality.classify("서울 뉴스 본문", true)).isEqualTo("ok")
        assertThat(BrowserReadQuality.classify("홈\n새로고침", true))
            .isEqualTo("content_missing")
    }
}
