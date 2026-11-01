package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class TermuxMaintenanceOperationsTest {
    @Test fun `mutable or malformed revision refuses before network or credentials`() = runBlocking {
        val registry = OperationRegistry()
        TermuxMaintenanceOperations({ error("must not read credentials") }).register(registry) { true }
        val operation = registry.get(TermuxMaintenanceOperations.UPDATE)!!
        for (value in listOf("main", "g".repeat(40))) {
            val result = operation.handler.invoke(OperationInvocation("invalid", buildJsonObject { put("revision", value) }))
            assertThat(result.success).isFalse()
            assertThat(result.summary).isEqualTo("invalid_immutable_revision")
        }
    }

    @Test fun `diagnostics discard arbitrary body values and secrets`() {
        val secret = "private_token_like_value"
        val result = maintenanceSafeData(buildJsonObject {
            put("status", secret); put("stdout", secret); put("error", secret)
            put("token", secret); put("revision", "main"); put("attempts", "1")
            put("runtime_revision", secret); put("verification", secret)
        })
        assertThat(result).isEmpty()
    }

    @Test fun `typed fixed diagnostics survive filtering`() {
        val input = buildJsonObject {
            put("status", "updated"); put("stdout", "CLOSEPAW_MAINTENANCE_OK")
            put("source_sha256", "a".repeat(64)); put("exit_code", 0)
            put("verification", "jobs_exit0_stdout_match"); put("attempts", 1)
        }
        assertThat(maintenanceSafeData(input)).isEqualTo(input)
    }

    @Test fun `independent operation uses authenticated maintenance and refuses false updated proof`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"status\":\"updated\",\"stdout\":\"secret\"}"))
            val registry = OperationRegistry()
            TermuxMaintenanceOperations({ "paired-token" }, baseUrl=server.url("/").toString().trimEnd('/'))
                .register(registry) { true }
            val update = registry.get(TermuxMaintenanceOperations.UPDATE)!!
            assertThat(update.descriptor.readOnly).isFalse()
            assertThat(update.descriptor.permissions).contains("remote_execution")
            val result = update.handler.invoke(OperationInvocation("test", buildJsonObject { put("revision", "a".repeat(40)) }))
            assertThat(result.success).isFalse()
            assertThat(result.data.toString()).doesNotContain("secret")
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/maintenance/update")
            assertThat(request.getHeader("X-ClosePaw-Token")).isEqualTo("paired-token")
        } finally { server.shutdown() }
    }
}
