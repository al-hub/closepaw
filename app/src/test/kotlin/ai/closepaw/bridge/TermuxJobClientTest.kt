package ai.closepaw.bridge

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Test

class TermuxJobClientTest {
    @Test fun `start authenticates and submits idempotency and execution arguments`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"job_id":"job-1","state":"running"}"""))
            val reply = client(server).start("request-1", ExecutionRequest(ExecutionCapability.LINUX_SHELL,
                "kv", "/home", 9000, mapOf("LANG" to "C"))) as TermuxJobReply.Success
            assertThat(reply.snapshot.jobId).isEqualTo("job-1")
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/jobs")
            assertThat(request.getHeader("X-ClosePaw-Token")).isEqualTo(TOKEN)
            val body = JSONObject(request.body.readUtf8())
            assertThat(body.getString("request_id")).isEqualTo("request-1")
            assertThat(body.getString("command")).isEqualTo("kv")
            assertThat(body.getString("cwd")).isEqualTo("/home")
            assertThat(body.getLong("timeout_ms")).isEqualTo(9000)
            assertThat(body.getJSONObject("env").getString("LANG")).isEqualTo("C")
        }
    }

    @Test fun `poll preserves failed exit code output truncation and offsets`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("""{"job_id":"job-1","state":"failed","exit_code":7,"stdout":"out","stderr":"err","stdout_next_offset":3,"stderr_next_offset":3,"stdout_truncated":true,"duration_ms":20}"""))
            val snapshot = (client(server).poll("job-1", 1, 2, 512) as TermuxJobReply.Success).snapshot
            assertThat(snapshot.exitCode).isEqualTo(7)
            assertThat(snapshot.stdout).isEqualTo("out")
            assertThat(snapshot.stderr).isEqualTo("err")
            assertThat(snapshot.stdoutTruncated).isTrue()
            assertThat(snapshot.stdoutNextOffset).isEqualTo(3)
            assertThat(snapshot.durationMs).isEqualTo(20)
            assertThat(server.takeRequest().path).isEqualTo("/v1/jobs/job-1?stdout_offset=1&stderr_offset=2&limit=512")
        }
    }

    @Test fun `HTTP error is transport failure and never shell exit code`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
            val reply = client(server).poll("job-1") as TermuxJobReply.Failure
            assertThat(reply.httpStatus).isEqualTo(401)
            assertThat(reply.code).isEqualTo("bridge_auth_mismatch")
        }
    }

    @Test fun `cancel pending is not confirmed and timeout keeps nullable exit code`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("""{"job_id":"job-1","state":"running","cancel_confirmed":false}"""))
            assertThat((client(server).cancel("job-1") as TermuxJobReply.Success).snapshot.cancelConfirmed).isFalse()
            assertThat(server.takeRequest().path).isEqualTo("/v1/jobs/job-1/cancel")
            server.enqueue(MockResponse().setBody("""{"job_id":"job-1","state":"timed_out","exit_code":null,"timed_out":true}"""))
            val snapshot = (client(server).poll("job-1") as TermuxJobReply.Success).snapshot
            assertThat(snapshot.exitCode).isNull()
            assertThat(snapshot.timedOut).isTrue()
        }
    }

    @Test fun `malformed response cannot report execution success`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("""{"exit_code":0}"""))
            assertThat((client(server).poll("job-1") as TermuxJobReply.Failure).code).isEqualTo("bridge_invalid_response")
        }
    }

    @Test fun `interrupted job never loses uncertain outcome`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("""{"job_id":"job-1","state":"interrupted","exit_code":null}"""))
            val snapshot = (client(server).poll("job-1") as TermuxJobReply.Success).snapshot
            assertThat(snapshot.outcomeUnknown).isTrue()
            assertThat(snapshot.terminal).isTrue()
            assertThat(snapshot.exitCode).isNull()
        }
    }

    @Test fun `cancel reports confirmation only for cancelled process`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("""{"job_id":"job-1","state":"cancelled","cancel_confirmed":true}"""))
            val snapshot = (client(server).cancel("job-1") as TermuxJobReply.Success).snapshot
            assertThat(snapshot.cancelConfirmed).isTrue()
            assertThat(snapshot.terminal).isTrue()
        }
    }

    @Test fun `readiness authenticates without executing a command`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("""{"status":"ready","job_api_version":1}"""))
            assertThat(client(server).probe().ready).isTrue()
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/jobs")
            assertThat(request.method).isEqualTo("GET")
            assertThat(request.getHeader("X-ClosePaw-Token")).isEqualTo(TOKEN)
        }
    }

    @Test fun `readiness rejects absent job API and reports pairing mismatch`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("""{"status":"ready"}"""))
            assertThat(client(server).probe().ready).isFalse()
            server.enqueue(MockResponse().setResponseCode(401))
            val readiness = client(server).probe()
            assertThat(readiness.ready).isFalse()
            assertThat(readiness.reason).contains("pairing token")
        }
    }

    @Test fun `oversized response is rejected before parsing`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("x".repeat(524289)))
            assertThat((client(server).poll("job-1") as TermuxJobReply.Failure).code).isEqualTo("bridge_invalid_response")
        }
    }

    private fun client(server: MockWebServer) = TermuxJobClient(TOKEN, server.url("/").toString())
    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer().apply { start() }
        try { block(server) } finally { server.shutdown() }
    }
    private companion object { const val TOKEN = "0123456789abcdef0123456789abcdef" }
}

