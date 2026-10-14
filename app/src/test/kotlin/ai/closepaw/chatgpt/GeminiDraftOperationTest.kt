package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.OperationInvocation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test

class GeminiDraftOperationTest {
    private class FakeUi : GeminiDraftUi {
        var locked = false
        var foreground = true
        var draft = ""
        var candidates = 1
        var allow = true
        var writes = 0
        var launchCalls = 0
        var loseForegroundOnWrite = false
        var verified = true
        override suspend fun isLocked() = locked
        override suspend fun launch(): Boolean { launchCalls++; return foreground }
        override suspend fun inspect() = DraftComposerState(foreground, candidates, draft)
        override suspend fun setIfEmpty(text: String): DraftWriteResult {
            if (loseForegroundOnWrite) return DraftWriteResult.TARGET_CHANGED
            if (draft.isNotBlank()) return DraftWriteResult.EXISTING_DRAFT
            writes++; draft = text; return DraftWriteResult.WRITTEN
        }
        override suspend fun matches(text: String) = verified && draft == text
    }
    private fun invocation(text: String = "오늘 할 일을 정리해줘 😀") = OperationInvocation("task-123", buildJsonObject { put("text", text) })
    private fun operation(ui: FakeUi, events: MutableList<DraftDiagnostic> = mutableListOf()) =
        GeminiDraftOperation(ui, allowed = { ui.allow }, record = { events.add(it) })

    @Test fun insertsAndVerifiesWithoutSendAndLogsNoText() = runBlocking {
        val ui = FakeUi(); val events = mutableListOf<DraftDiagnostic>()
        val result = operation(ui, events).invoke(invocation())
        assertThat(result.success).isTrue()
        assertThat(ui.writes).isEqualTo(1)
        assertThat(result.data["sent"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(result.data["verified"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(events.map { it.stage }).containsAtLeast("start", "launch", "find_composer", "set_text", "verify", "complete")
        assertThat(events.all { it.taskId == "task-123" }).isTrue()
        assertThat(events.toString()).doesNotContain("오늘")
        assertThat(result.data.toString()).doesNotContain("오늘")
    }
    @Test fun lockedPhoneDoesNotLaunchOrWrite() = runBlocking {
        val ui = FakeUi().apply { locked = true }
        val result = operation(ui).invoke(invocation())
        assertThat(result.data["reason_code"]!!.jsonPrimitive.content).isEqualTo("device_locked")
        assertThat(ui.launchCalls).isEqualTo(0); assertThat(ui.writes).isEqualTo(0)
    }
    @Test fun existingDraftIsNeverOverwrittenEvenIfSameText() = runBlocking {
        val ui = FakeUi().apply { draft = "existing" }
        assertThat(operation(ui).invoke(invocation()).success).isFalse()
        assertThat(ui.draft).isEqualTo("existing"); assertThat(ui.writes).isEqualTo(0)
    }
    @Test fun ambiguousOrMissingComposerFailsClosed() = runBlocking {
        for (count in listOf(0, 2)) {
            val ui = FakeUi().apply { candidates = count }
            assertThat(operation(ui).invoke(invocation()).success).isFalse()
            assertThat(ui.writes).isEqualTo(0)
        }
    }
    @Test fun permissionRevokedAndWrongForegroundPreventWrite() = runBlocking {
        val disabled = FakeUi().apply { allow = false }
        assertThat(operation(disabled).invoke(invocation()).success).isFalse()
        assertThat(disabled.launchCalls).isEqualTo(0)
        val switched = FakeUi().apply { loseForegroundOnWrite = true }
        assertThat(operation(switched).invoke(invocation()).success).isFalse()
        assertThat(switched.writes).isEqualTo(0)
    }
    @Test fun verificationFailureIsNotSuccessOrAutomaticallyRetried() = runBlocking {
        val ui = FakeUi().apply { verified = false }
        val result = operation(ui).invoke(invocation())
        assertThat(result.success).isFalse(); assertThat(ui.writes).isEqualTo(1)
        assertThat(result.data["outcome_unknown"]!!.jsonPrimitive.boolean).isTrue()
    }
}
