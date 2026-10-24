package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test

class GeminiUiInspectionOperationTest {
    private fun input(id: String = "test-session") = buildJsonObject { put("session_id", id) }
    @Test fun readOnlyDescriptorNeedsNoSendPermissionAndRejectsInput() {
        val descriptor = GeminiUiInspectionOperation.descriptor()
        assertThat(descriptor.id).isEqualTo("gemini.ui.inspect")
        assertThat(descriptor.readOnly).isTrue()
        assertThat(descriptor.effects).isEmpty()
        assertThat(descriptor.permissions).containsExactly("accessibility")
        assertThat(OperationSchemaValidator.validate(descriptor.inputSchema, input())).isNull()
        assertThat(OperationSchemaValidator.validate(descriptor.inputSchema, buildJsonObject { put("text", "must not accept") })).isNotNull()
        assertThat(GeminiUiInspectionOperation.descriptor { OperationReadiness(false,"device_locked") }.safeReadiness().ready).isFalse()
    }
    @Test fun onlyInspectsOnceWithoutAnyActionDependency() = runBlocking {
        var reads = 0
        val metadata = buildJsonObject { put("foreground",true); put("reason_code","send_control_missing") }
        val result = GeminiUiInspectionOperation { reads++; metadata }.invoke(OperationInvocation("inspect",input()))
        assertThat(reads).isEqualTo(1); assertThat(result.success).isTrue(); assertThat(result.data).isEqualTo(metadata)
        assertThat(result.stdout).isEmpty()
    }
    @Test fun invalidInputDoesNotInspect() = runBlocking {
        var reads = 0
        val result = GeminiUiInspectionOperation { reads++; buildJsonObject {} }.invoke(OperationInvocation("inspect",buildJsonObject { put("launch",true) }))
        assertThat(result.success).isFalse(); assertThat(reads).isEqualTo(0)
    }
    @Test fun unverifiedScreenAndExceptionFailWithStaticExplanation() = runBlocking {
        val unverified = GeminiUiInspectionOperation { buildJsonObject { put("foreground",false); put("reason_code","target_not_verified") } }.invoke(OperationInvocation("inspect",input()))
        assertThat(unverified.success).isFalse()
        val failed = GeminiUiInspectionOperation { error("private account secret") }.invoke(OperationInvocation("inspect",input()))
        assertThat(failed.success).isFalse(); assertThat(failed.toString()).doesNotContain("private account secret")
    }
    @Test fun cancellationPropagates() = runBlocking {
        var cancelled = false
        try { GeminiUiInspectionOperation { throw CancellationException() }.invoke(OperationInvocation("inspect",input())) }
        catch (_: CancellationException) { cancelled=true }
        assertThat(cancelled).isTrue()
    }
    @Test fun snapshotExceptionReasonsNeverExposeExceptionMessagesOrTypes() = runBlocking {
        val errors = listOf(SecurityException("private account") to "snapshot_security_exception",
            IllegalArgumentException("private account") to "snapshot_invalid_argument",
            IllegalStateException("private account") to "snapshot_illegal_state",
            RuntimeException("private account") to "snapshot_failed")
        for ((error, reason) in errors) {
            val result = GeminiUiInspectionOperation { throw error }.invoke(OperationInvocation("inspect", input()))
            assertThat(result.success).isFalse()
            assertThat(result.data["reason_code"]!!.jsonPrimitive.content).isEqualTo(reason)
            assertThat(result.toString()).doesNotContain("private account")
            assertThat(result.stdout).isEmpty()
        }
    }

    @Test fun missingBlankWrongTypeAndUnknownInputNeverInspect() = runBlocking {
        var reads = 0
        val op = GeminiUiInspectionOperation { reads++; buildJsonObject { put("foreground", true) } }
        val invalid = listOf(buildJsonObject {}, input(""), buildJsonObject { put("session_id", 123) },
            buildJsonObject { put("session_id", "token"); put("launch", true) }, input("x".repeat(129)))
        for (value in invalid) assertThat(op.invoke(OperationInvocation("bad", value)).success).isFalse()
        assertThat(reads).isEqualTo(0)
    }
    @Test fun matchingExplicitSessionTokenIsForwardedWithoutImplicitResume() = runBlocking {
        val session = ai.closepaw.chatgpt.control.ControlSession(); session.start()
        var observed = ""; var reads = 0
        val op = GeminiUiInspectionOperation { id ->
            observed = id
            val refusal = GeminiUiInspectionOperation.sessionRefusal(session, id)
            if (refusal != null) buildJsonObject { put("foreground", false); put("reason_code", refusal) }
            else { reads++; buildJsonObject { put("foreground", true) } }
        }
        assertThat(op.invoke(OperationInvocation("good", input(session.id))).success).isTrue()
        assertThat(observed).isEqualTo(session.id); assertThat(reads).isEqualTo(1)
        assertThat(op.invoke(OperationInvocation("wrong", input("wrong-token"))).success).isFalse()
        assertThat(reads).isEqualTo(1)
    }
    @Test fun sessionAdmissionRejectsIdleEndedPausedAndStaleTokensWithoutStateChange() {
        val session = ai.closepaw.chatgpt.control.ControlSession()
        assertThat(GeminiUiInspectionOperation.sessionRefusal(null, "token")).isEqualTo("accessibility_permission_missing")
        val idleEpoch = session.epoch
        assertThat(GeminiUiInspectionOperation.sessionRefusal(session, "token")).isEqualTo("explicit_active_session_required")
        assertThat(session.epoch).isEqualTo(idleEpoch)
        session.start(); val oldId = session.id
        assertThat(GeminiUiInspectionOperation.sessionRefusal(session, oldId)).isNull()
        assertThat(GeminiUiInspectionOperation.sessionRefusal(session, "wrong-token")).isEqualTo("control_session_id_required")
        session.end(); val endedEpoch = session.epoch
        assertThat(GeminiUiInspectionOperation.sessionRefusal(session, oldId)).isEqualTo("explicit_active_session_required")
        assertThat(session.epoch).isEqualTo(endedEpoch)
        session.start()
        assertThat(GeminiUiInspectionOperation.sessionRefusal(session, oldId)).isEqualTo("control_session_id_required")
        session.pause(); val pausedEpoch = session.epoch
        assertThat(GeminiUiInspectionOperation.sessionRefusal(session, session.id)).isEqualTo("explicit_active_session_required")
        assertThat(session.epoch).isEqualTo(pausedEpoch)
        assertThat(session.state).isEqualTo(ai.closepaw.chatgpt.control.ControlSession.State.PAUSED)
    }

}
