package ai.closepaw.qa

import ai.closepaw.ui.overlay.compose.StatusIslandCompose
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the request-priority and static rendering path used with system motion disabled. */
@RunWith(AndroidJUnit4::class)
class IslandUserActionRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentActionReplacesProgressThenClearsBackToProgress() {
        val prompt = mutableStateOf<String?>("공유를 허용하세요")
        compose.setContent {
            ClosePawTheme {
                StatusIslandCompose(
                    text = "화면 확인 중",
                    dotColor = MaterialTheme.colorScheme.primary,
                    pulsing = false,
                    onClick = null,
                    userAction = prompt.value,
                )
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("공유를 허용하세요").assertExists()
        compose.onNodeWithText("화면 확인 중").assertDoesNotExist()

        compose.runOnIdle { prompt.value = "공유를 중지하세요" }
        compose.mainClock.advanceTimeBy(64L)
        compose.onNodeWithContentDescription("공유를 허용하세요").assertDoesNotExist()
        compose.onNodeWithContentDescription("공유를 중지하세요").assertExists()

        compose.runOnIdle { prompt.value = null }
        compose.mainClock.advanceTimeBy(64L)
        compose.onNodeWithContentDescription("공유를 중지하세요").assertDoesNotExist()
        compose.onNodeWithText("화면 확인 중").assertExists()
    }
}
