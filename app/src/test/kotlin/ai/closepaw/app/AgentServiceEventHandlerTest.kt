package ai.closepaw.app

import com.google.common.truth.Truth.assertThat
import ai.closepaw.history.SessionRecordingService
import ai.closepaw.protocol.SessionError
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.TaskCompleted
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.protocol.TaskStarted
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class AgentServiceEventHandlerTest {

    private val sessionId = SessionId("s1")
    private val statusUpdates = mutableListOf<String>()
    private var sessionClearedCount = 0
    private val overlay = mockk<ServiceOverlayController>(relaxed = true)
    private val recording = mockk<SessionRecordingService>(relaxed = true)

    private val handler = AgentServiceEventHandler(
        logTag = "test",
        updateStatus = { statusUpdates += it },
        sessionCleared = { sessionClearedCount++ },
        overlayController = { overlay }
    )

    @Test
    fun `TaskStarted records user message and notifies overlay`() {
        val event = TaskStarted(
            sessionId = sessionId,
            timestamp = 1_000L,
            taskId = "t1",
            input = "Do the thing"
        )

        handler.handleEvent(event, recording)

        verify { recording.onTaskStarted() }
        verify { recording.recordUserMessage(any(), 1_000L, "Do the thing") }
        verify { recording.startAgentMessage("t1", 1_000L) }
        verify { overlay.onTaskStarted("t1", "Do the thing") }
    }

    @Test
    fun `TaskCompleted finalizes recording and updates overlay`() {
        handler.handleEvent(TaskStarted(sessionId, 1_000L, "t1", "Do the thing"), recording)
        val event = TaskCompleted(
            sessionId = sessionId,
            timestamp = 2_000L,
            taskId = "t1",
            result = "ok",
            outcome = TaskOutcome.GOAL_ACHIEVED
        )

        handler.handleEvent(event, recording)

        verify { recording.completeAgentMessage() }
        verify { recording.recordTaskOutcome(TaskOutcome.GOAL_ACHIEVED) }
        verify { overlay.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED, "ok") }
    }

    @Test
    fun `SessionError surfaces error in status text and overlay`() {
        val event = SessionError(
            sessionId = sessionId,
            timestamp = 3_000L,
            message = "boom"
        )

        handler.handleEvent(event, recording)

        assertThat(statusUpdates).containsExactly("❌ Error: boom")
        verify { overlay.onSessionError("boom") }
    }
    @Test
    fun `late completion from prior task cannot finalize current recording or overlay`() {
        handler.handleEvent(TaskStarted(sessionId, 100L, "old", "one"), recording)
        handler.handleEvent(TaskStarted(sessionId, 200L, "current", "two"), recording)
        handler.handleEvent(TaskCompleted(sessionId, 300L, "old", "stale", TaskOutcome.GOAL_ACHIEVED), recording)
        verify(exactly = 0) { recording.completeAgentMessage() }
        verify(exactly = 0) { overlay.onTaskCompleted(any(), any()) }
        handler.handleEvent(TaskCompleted(sessionId, 400L, "current", "ok", TaskOutcome.GOAL_ACHIEVED), recording)
        verify(exactly = 1) { recording.completeAgentMessage() }
        verify(exactly = 1) { overlay.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED, "ok") }
    }

    @Test
    fun `duplicate task start and unbound completion do not produce UI or recording effects`() {
        handler.handleEvent(TaskCompleted(sessionId, 50L, "unknown", "late", TaskOutcome.GOAL_ACHIEVED), recording)
        val started = TaskStarted(sessionId, 100L, "current", "one")
        handler.handleEvent(started, recording)
        handler.handleEvent(started, recording)
        verify(exactly = 1) { recording.onTaskStarted() }
        verify(exactly = 1) { overlay.onTaskStarted("current", "one") }
        verify(exactly = 0) { recording.completeAgentMessage() }
        verify(exactly = 0) { overlay.onTaskCompleted(any(), any()) }
    }
}
