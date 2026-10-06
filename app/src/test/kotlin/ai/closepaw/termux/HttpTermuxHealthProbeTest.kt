package ai.closepaw.termux

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class HttpTermuxHealthProbeTest {
    @Test
    fun `health probe requires authenticated exec before Ready`() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"identity":"closepaw-bridge","version":"1"}""")
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"exit_code":0,"stdout":"","stderr":"","timed_out":false}""")
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
            assertThat(auth.path).isEqualTo("/v1/exec")
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
