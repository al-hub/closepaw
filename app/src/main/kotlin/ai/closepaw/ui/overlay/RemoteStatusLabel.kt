package ai.closepaw.ui.overlay

/** Allowlisted executor feedback only. Never display raw target, package or error strings. */
object RemoteStatusLabel {
    fun sanitize(text: String): String = IslandActionLabel.running(when (text) {
        "선택한 항목을 누르고 있습니다", "선택한 항목의 길게 누르기 기능을 실행합니다" -> "click"
        "화면을 스크롤하고 있습니다" -> "scroll"
        "입력란에 글자를 입력하고 있습니다" -> "type"
        "접근성 확인 완료 · 다음 명령 대기", "화면 변화 확인 · 다음 단계 대기", "AI 제어 중 · 새 명령 대기" -> "response_wait"
        else -> "execute"
    })
}
