package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class CloudflareQuickTunnelProviderTest {
    @Test
    fun extractsQuickTunnelUrlFromCloudflaredLog() {
        val line = "INF + https://quiet-river-123.trycloudflare.com"

        assertThat(CloudflareTunnelLogParser.extractPublicUrl(line))
            .isEqualTo("https://quiet-river-123.trycloudflare.com")
    }

    @Test
    fun ignoresLogsWithoutQuickTunnelUrl() {
        assertThat(CloudflareTunnelLogParser.extractPublicUrl("INF Starting tunnel"))
            .isNull()
    }

    @Test
    fun commandForwardsOnlyLoopbackMcpPortAndDisablesAutoupdate() {
        val command = CloudflareQuickTunnelProvider.buildCommand(
            File("/data/app/lib/libcloudflared.so"),
            18424,
        )

        assertThat(command).containsExactly(
            "/data/app/lib/libcloudflared.so",
            "tunnel",
            "--url",
            "http://127.0.0.1:18424",
            "--no-autoupdate",
        ).inOrder()
    }
}
