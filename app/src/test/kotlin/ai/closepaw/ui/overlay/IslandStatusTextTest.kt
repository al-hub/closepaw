package ai.closepaw.ui.overlay

import ai.closepaw.ui.overlay.model.CapsuleMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IslandStatusTextTest {
    @Test fun `running text never reveals user input`() {
        assertThat(IslandStatusText.label(CapsuleMode.Running("private screen text")))
            .isEqualTo("AI 제어 중")
    }

    @Test fun `terminal text never reveals private result or error`() {
        assertThat(IslandStatusText.label(CapsuleMode.Done("private result")))
            .isEqualTo("AI 작업 완료")
        assertThat(IslandStatusText.label(CapsuleMode.Error("private exception")))
            .isEqualTo("AI 작업 오류")
    }

    @Test fun `approval text never reveals app name or reason`() {
        assertThat(IslandStatusText.label(CapsuleMode.WaitingForApproval(
            "call1", "private description", "private app", "private.package", "private reason"
        ))).isEqualTo("승인 대기")
    }
}
