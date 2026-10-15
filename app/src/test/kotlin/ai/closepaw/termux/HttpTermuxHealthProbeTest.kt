package ai.closepaw.termux

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class HttpTermuxHealthProbeTest {
    @Test
    fun `health probe requires authenticated job readiness before Ready`() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"identity":"closepaw-bridge","version":"1"}""")
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"status":"ready","job_api_version":1}""")
            )

            val probe = HttpTermuxHealthProbe(
                server.url("/v1/health").toString(),
                "closepaw-bridge",
                "1",
                TOKEN,
            )

            assertThat(probe.fetch()).isEqualTo(HealthProbe.Ready)
            server.takeRequest()
            val auth = server.takeRequest()
            assertThat(auth.path).isEqualTo("/v1/jobs")
            assertThat(auth.getHeader("X-ClosePaw-Token")).isEqualTo(TOKEN)
        }
    }

    @Test
    fun `health probe reports AuthMismatch on authenticated 401`() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"identity":"closepaw-bridge","version":"1"}""")
            )
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

            val probe = HttpTermuxHealthProbe(
                server.url("/v1/health").toString(),
                "closepaw-bridge",
                "1",
                TOKEN,
            )

            assertThat(probe.fetch()).isEqualTo(HealthProbe.AuthMismatch)
        }
    }

    @Test
    fun `recovery upgrade uses existing authenticated transport without token file writes`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setBody("""{"exit_code":0,"stdout":"CLOSEPAW_RECOVERY=installed"}"""))
            val probe = HttpTermuxHealthProbe(server.url("/v1/health").toString(), "closepaw-bridge", "2", TOKEN)
            assertThat(probe.installRecovery("YnJpZGdl", "Ym9vdA==")).isTrue()
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/exec")
            assertThat(request.getHeader("X-ClosePaw-Token")).isEqualTo(TOKEN)
            val payload = org.json.JSONObject(request.body.readUtf8())
            assertThat(payload.getString("command")).doesNotContain(".closepaw/token")
            assertThat(payload.getString("command")).doesNotContain(TOKEN)
            assertThat(payload.getString("command")).contains("--supervise")
            assertThat(payload.getJSONObject("env").getString("CLOSEPAW_BRIDGE_TOKEN")).isEqualTo(TOKEN)
        }
    }

    @Test
    fun `busy bridge defers upgrade without another command`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(409))
            val probe = HttpTermuxHealthProbe(server.url("/v1/health").toString(), "closepaw-bridge", "2", TOKEN)
            assertThat(probe.installRecovery("YnJpZGdl", "Ym9vdA==")).isFalse()
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer().apply { start() }
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
