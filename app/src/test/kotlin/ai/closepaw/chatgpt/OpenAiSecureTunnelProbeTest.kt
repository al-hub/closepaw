package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenAiSecureTunnelProbeTest {
    @Test
    fun missingRuntimeFailsClosed() {
        val probe = SecureMcpTunnelProbe(OpenAiTunnelBinaryResolver { null })

        val result = kotlinx.coroutines.runBlocking { probe.run() }

        assertThat(result.succeeded).isFalse()
        assertThat(result.summary).contains("not bundled")
    }

    @Test
    fun androidResolverUsesNativeLibraryName() {
        assertThat(AndroidOpenAiTunnelBinaryResolver.BINARY_NAME)
            .isEqualTo("libopenaitunnel.so")
    }
}
