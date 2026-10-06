package ai.closepaw.bridge

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class TermuxLocalBridgeExecutionAdapterTest {
    @Test fun `probe is available for ClosePaw bridge identity`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"identity":"closepaw-bridge","version":"1"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"exit_code":0,"stdout":"","stderr":"","timed_out":false}"""))
            val adapter = adapter(server)
            assertThat(adapter.probe()).isEqualTo(AdapterAvailability.Available)
            server.takeRequest()
            val authRequest = server.takeRequest()
            assertThat(authRequest.getHeader("X-ClosePaw-Token")).isEqualTo(TOKEN)
            assertThat(authRequest.path).isEqualTo("/v1/exec")
        }
    }

    @Test fun `probe rejects unexpected localhost service`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"identity":"other"}"""))
            assertThat(adapter(server).probe()).isInstanceOf(AdapterAvailability.Unavailable::class.java)
        }
    }

    @Test fun `probe reports pairing mismatch on authenticated 401`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"identity":"closepaw-bridge","version":"1"}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

            val availability = adapter(server).probe()

            assertThat(availability).isInstanceOf(AdapterAvailability.NeedsSetup::class.java)
            assertThat((availability as AdapterAvailability.NeedsSetup).reason)
                .contains("pairing token does not match")
        }
    }

    @Test fun `execute sends auth and normalizes result`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"exit_code":7,"stdout":"out","stderr":"err","timed_out":false}"""))
            val result = adapter(server).execute(ExecutionRequest(ExecutionCapability.LINUX_SHELL, "exit 7"))
            val request = server.takeRequest()
            assertThat(request.getHeader("X-ClosePaw-Token")).isEqualTo(TOKEN)
            assertThat(result.exitCode).isEqualTo(7)
            assertThat(result.stdout).isEqualTo("out")
            assertThat(result.stderr).isEqualTo("err")
        }
    }

    @Test fun `timeout result has null exit code`() = runTest {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"exit_code":null,"stdout":"","stderr":"","timed_out":true}"""))
            val result = adapter(server).execute(ExecutionRequest(ExecutionCapability.LINUX_SHELL, "sleep 9"))
            assertThat(result.exitCode).isNull()
            assertThat(result.timedOut).isTrue()
        }
    }

    private fun adapter(server: MockWebServer) =
        TermuxLocalBridgeExecutionAdapter(TOKEN, server.url("/").toString())

    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer().apply { start() }
        try { block(server) } finally { server.shutdown() }
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
