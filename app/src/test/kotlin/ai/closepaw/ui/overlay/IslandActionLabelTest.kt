package ai.closepaw.ui.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IslandActionLabelTest {
    @Test fun `touch keeps stable prefix`() {
        assertThat(IslandActionLabel.running("click")).isEqualTo("AI 제어 중 - 터치")
    }

    @Test fun `scroll and text input use only a short changing verb`() {
        assertThat(IslandActionLabel.running("swipe")).isEqualTo("AI 제어 중 - 스크롤")
        assertThat(IslandActionLabel.running("type")).isEqualTo("AI 제어 중 - 입력")
    }

    @Test fun `wait and unknown operations do not expose arguments`() {
        assertThat(IslandActionLabel.running("wait")).isEqualTo("AI 제어 중 - 응답 대기")
        assertThat(IslandActionLabel.running("private message 123")).isEqualTo("AI 제어 중 - 실행")
    }

    @Test fun `terminal statuses are distinct from active control`() {
        assertThat(IslandActionLabel.completed()).isEqualTo("AI 작업 완료")
        assertThat(IslandActionLabel.failed()).isEqualTo("AI 작업 오류")
    }
}
