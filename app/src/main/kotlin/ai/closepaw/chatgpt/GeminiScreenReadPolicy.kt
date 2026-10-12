package ai.closepaw.chatgpt

/**
 * Conservative screen-state hints for Google's native Android Gemini app.
 *
 * The Accessibility tree has text and content descriptions but does not guarantee
 * author roles. Never infer that arbitrary visible strings are a finished AI
 * answer. Send the one captured screen to the caller and label uncertainty.
 */
internal data class GeminiScreenAssessment(
    val screenKind: String,
    val replyStatus: String,
    val readQuality: String,
    val shouldOcr: Boolean,
)

internal object GeminiScreenReadPolicy {
    private val modelOption = Regex(
        """(?i)^\d+(?:\.\d+)?\s+(?:flash(?:-lite)?|pro)$"""
    )
    private val generatingLabels = listOf(
        "답변 생성 중", "응답 생성 중", "생성 중지", "답변 작성 중",
        "generating response", "stop generating",
    )
    private val composerLabels = listOf(
        "Gemini에게 물어보", "무엇이든 물어보", "Ask Gemini", "Enter a prompt",
    )
    private val chromeLabels = setOf(
        "Gemini", "Gemini Flash", "새 채팅", "새 대화", "채팅",
        "메뉴", "홈", "뒤로가기", "최근 앱", "프로필",
        "Gemini Live", "음성 검색", "홈페이지",
    )

    fun assess(content: String, hasScreenshot: Boolean): GeminiScreenAssessment {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val missingQuality = if (hasScreenshot) "content_missing" else "empty"
        if (lines.isEmpty()) {
            return GeminiScreenAssessment("no_readable_text", "not_visible", missingQuality, true)
        }

        // A visible model picker is app UI. Model descriptions must never be
        // passed to the assistant as if Gemini had generated a reply.
        if (lines.count { modelOption.matches(it) } >= 3) {
            return GeminiScreenAssessment("model_selector", "not_visible", "ok", false)
        }
        if (lines.any { line -> generatingLabels.any { label -> line.contains(label, true) } }) {
            return GeminiScreenAssessment("generating", "incomplete", "ok", false)
        }

        val other = lines.filterNot { line ->
            line in chromeLabels || composerLabels.any { label -> line.contains(label, true) }
        }
        if (other.isEmpty() && lines.any { line ->
                composerLabels.any { label -> line.contains(label, true) }
            }) {
            return GeminiScreenAssessment("empty_composer", "not_visible", "ok", false)
        }
        if (other.isEmpty()) {
            return GeminiScreenAssessment("ui_only", "not_visible", missingQuality, true)
        }

        // Role-labelled replies cannot be proven from flattened A11y text.
        return GeminiScreenAssessment("conversation", "unverified", "ok", false)
    }

    fun forModel(assessment: GeminiScreenAssessment, content: String): String {
        val notice = when (assessment.screenKind) {
            "model_selector" ->
                "Gemini Android 앱의 모델 선택 메뉴가 보입니다. 모델 설명은 AI 답변이 아닙니다."
            "empty_composer" ->
                "Gemini Android 앱의 입력 화면입니다. 현재 Gemini 답변이 보이지 않습니다."
            "generating" ->
                "Gemini가 답변 생성 중일 수 있습니다. 보이는 텍스트는 아직 불완전할 수 있습니다."
            "conversation" ->
                "Gemini Android 앱에서 읽은 현재 화면입니다. 사용자 질문과 AI 답변의 구분은 확정되지 않았습니다. 실제 보이는 문장만 설명하세요."
            else ->
                "Gemini 앱에서 읽을 수 있는 대화 답변은 확인되지 않았습니다. 첨부된 현재 화면만 참고하세요."
        }
        return if (content.isBlank()) notice else "$notice\n\n[현재 화면 텍스트]\n$content"
    }
}
