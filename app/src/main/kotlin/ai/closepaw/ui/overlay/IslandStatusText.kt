package ai.closepaw.ui.overlay

import ai.closepaw.ui.overlay.model.CapsuleMode

/** Island-only redacted labels. Never render task, app, approval or error contents. */
object IslandStatusText {
    fun label(mode: CapsuleMode): String = when (mode) {
        is CapsuleMode.Running -> "AI 제어 중"
        is CapsuleMode.TakeoverPending -> "제어권 전환 중"
        is CapsuleMode.Takeover -> "일시 정지"
        is CapsuleMode.WaitingForInput -> "응답 대기"
        is CapsuleMode.WaitingForAction -> "사용자 동작 대기"
        is CapsuleMode.WaitingForApproval -> "승인 대기"
        is CapsuleMode.Done -> "AI 작업 완료"
        is CapsuleMode.Error -> "AI 작업 오류"
        is CapsuleMode.Hidden -> ""
    }
}
