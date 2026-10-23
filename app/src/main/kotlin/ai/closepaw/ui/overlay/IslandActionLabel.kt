package ai.closepaw.ui.overlay

/**
 * Stable, privacy-preserving label for the single AI-control island.
 *
 * Maps operation identifiers, never arguments or screen text, to a short verb.
 * The caller must supply the action actually acknowledged by the executor.
 */
object IslandActionLabel {
    fun running(action: String): String {
        val verb = when (action.lowercase()) {
            "click", "tap", "long_press" -> "터치"
            "swipe", "scroll" -> "스크롤"
            "type", "input_text" -> "입력"
            "wait", "response_wait" -> "응답 대기"
            else -> "실행"
        }
        return "AI 제어 중 - $verb"
    }

    fun completed(): String = "AI 작업 완료"

    fun failed(): String = "AI 작업 오류"
}
