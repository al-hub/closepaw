package ai.closepaw.ui.overlay
import org.junit.Assert.*
import org.junit.Test
class RemoteStatusLabelTest {
    @Test fun preservesActualActionWithoutScreenOrErrorContent() {
        assertEquals("사용자 제어 중 · AI 일시정지", RemoteStatusLabel.sanitize("직접 조작 중 · AI 일시정지"))
        assertEquals("AI 제어 중 - 터치",RemoteStatusLabel.sanitize("선택한 항목을 누르고 있습니다"))
        assertEquals("AI 제어 중 - 스크롤",RemoteStatusLabel.sanitize("화면을 스크롤하고 있습니다"))
        assertEquals("AI 제어 중 - 응답 대기",RemoteStatusLabel.sanitize("화면 변화 확인 · 다음 단계 대기"))
        assertEquals("AI 제어 중 - 실행",RemoteStatusLabel.sanitize("secret account target failure"))
    }
    @Test fun mapsActualObservationAndFailureStagesWithoutRawDetail() {
        assertEquals("AI 제어 중 - 화면 확인", RemoteStatusLabel.sanitize("화면 확인 중"))
        assertEquals("AI 제어 중 - 화면 확인", RemoteStatusLabel.sanitize("화면 보안 확인 중"))
        assertEquals("AI 제어 중 - 화면 확인", RemoteStatusLabel.sanitize("접근성 정보 읽는 중"))
        assertEquals("AI 제어 중 - 결과 확인 필요", RemoteStatusLabel.sanitize("결과 미확인 · 재관찰 필요"))
        assertEquals("AI 제어 중 - 응답 대기", RemoteStatusLabel.sanitize("응답 대기 중"))
        assertEquals("AI 제어 중 - 단계 완료", RemoteStatusLabel.sanitize("실행 단계 확인 완료"))
    }
}
