package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test

class GeminiUiInspectionOperationTest {
    @Test fun readOnlyDescriptorNeedsNoSendPermissionAndRejectsInput() {
        val descriptor = GeminiUiInspectionOperation.descriptor()
        assertThat(descriptor.id).isEqualTo("gemini.ui.inspect")
        assertThat(descriptor.readOnly).isTrue()
        assertThat(descriptor.effects).isEmpty()
        assertThat(descriptor.permissions).containsExactly("accessibility")
        assertThat(OperationSchemaValidator.validate(descriptor.inputSchema, buildJsonObject {})).isNull()
        assertThat(OperationSchemaValidator.validate(descriptor.inputSchema, buildJsonObject { put("text", "must not accept") })).isNotNull()
        assertThat(GeminiUiInspectionOperation.descriptor { OperationReadiness(false,"device_locked") }.safeReadiness().ready).isFalse()
    }
    @Test fun onlyInspectsOnceWithoutAnyActionDependency() = runBlocking {
        var reads = 0
        val metadata = buildJsonObject { put("foreground",true); put("reason_code","send_control_missing") }
        val result = GeminiUiInspectionOperation { reads++; metadata }.invoke(OperationInvocation("inspect",buildJsonObject {}))
        assertThat(reads).isEqualTo(1); assertThat(result.success).isTrue(); assertThat(result.data).isEqualTo(metadata)
        assertThat(result.stdout).isEmpty()
    }
    @Test fun invalidInputDoesNotInspect() = runBlocking {
        var reads = 0
        val result = GeminiUiInspectionOperation { reads++; buildJsonObject {} }.invoke(OperationInvocation("inspect",buildJsonObject { put("launch",true) }))
        assertThat(result.success).isFalse(); assertThat(reads).isEqualTo(0)
    }
    @Test fun unverifiedScreenAndExceptionFailWithStaticExplanation() = runBlocking {
        val unverified = GeminiUiInspectionOperation { buildJsonObject { put("foreground",false); put("reason_code","target_not_verified") } }.invoke(OperationInvocation("inspect",buildJsonObject {}))
        assertThat(unverified.success).isFalse()
        val failed = GeminiUiInspectionOperation { error("private account secret") }.invoke(OperationInvocation("inspect",buildJsonObject {}))
        assertThat(failed.success).isFalse(); assertThat(failed.toString()).doesNotContain("private account secret")
    }
    @Test fun cancellationPropagates() = runBlocking {
        var cancelled = false
        try { GeminiUiInspectionOperation { throw CancellationException() }.invoke(OperationInvocation("inspect",buildJsonObject {})) }
        catch (_: CancellationException) { cancelled=true }
        assertThat(cancelled).isTrue()
    }
}
