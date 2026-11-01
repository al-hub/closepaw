package ai.closepaw.chatgpt

import ai.closepaw.bridge.TermuxJobClient
import ai.closepaw.bridge.TermuxJobLinkStore
import ai.closepaw.chatgpt.operations.OperationInvocation
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real HTTP transport + host shell fixture; Android/Termux physical acceptance is separate. */
class TermuxShellCapacityIntegrationTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun fullLinksRecoverThenHttpCommandExecutesOnceAndOutputIsCollected() = runTest {
        val file = File(directory.root, "links")
        file.writeText((0 until 1000).joinToString("\n", postfix="\n") {
            "${UUID(0, it.toLong())}\t${UUID(1, it.toLong())}"
        })
        val submitted = AtomicInteger()
        val task = UUID.randomUUID().toString()
        val job = UUID.randomUUID().toString().replace("-", "")
        val token = "capacity-fixture-token-012345678901234567890"
        var output = ""
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("X-ClosePaw-Token") != token) return MockResponse().setResponseCode(401)
                if (request.method == "POST" && request.path == "/v1/jobs") {
                    submitted.incrementAndGet()
                    val payload = JSONObject(request.body.readUtf8())
                    check(payload.getString("request_id") == task)
                    val process = ProcessBuilder("bash", "-c", payload.getString("command")).start()
                    output = process.inputStream.bufferedReader().readText()
                    check(process.waitFor() == 0)
                }
                val offset = request.requestUrl?.queryParameter("stdout_offset")?.toInt() ?: 0
                return MockResponse().setBody(JSONObject().apply {
                    put("job_id", job); put("state", "succeeded"); put("exit_code", 0)
                    put("stdout", if (offset == 0) output else ""); put("stderr", "")
                    put("stdout_next_offset", output.toByteArray().size); put("stderr_next_offset", 0)
                    put("outcome_unknown", false)
                }.toString())
            }
        }
        server.start()
        try {
            val links = TermuxJobLinkStore(file) { mapOf(UUID(0, 0).toString() to UUID(1, 0).toString()) }
            val operation = TermuxShellOperation(TermuxJobClient(token, server.url("/").toString()), links)
            val invocation = OperationInvocation(task, buildJsonObject { put("command", "printf 'bridge-capacity-ok\\n'") })
            val result = operation.invoke(invocation)
            assertThat(result.success).isTrue()
            assertThat(result.exitCode).isEqualTo(0)
            assertThat(result.stdout).isEqualTo("bridge-capacity-ok\n")
            assertThat(operation.invoke(invocation).stdout).isEqualTo(result.stdout)
            assertThat(submitted.get()).isEqualTo(1)
            assertThat(file.readLines()).hasSize(1000)
            assertThat(links.get(UUID(0, 1).toString())).isEqualTo(UUID(1, 1).toString())
        } finally { server.shutdown() }
    }
}
