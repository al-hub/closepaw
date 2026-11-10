package ai.closepaw.chatgpt

import ai.closepaw.BuildConfig
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic configuration on an isolated CI emulator only. Never run on a user's phone. */
@RunWith(AndroidJUnit4::class)
class PackageReplacementFixtureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val marker get() = File(context.filesDir, "package-replacement-fixture")
    private fun requireFixture() {
        check(BuildConfig.DEBUG)
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
    }
    @Test fun seedEnabled() {
        requireFixture()
        assertNull(SecureMcpTunnelConfigStore(context).load())
        SecureMcpTunnelConfigStore(context).save("tunnel_" + "0".repeat(32), "ci-synthetic-not-a-credential")
        assertTrue(context.getSharedPreferences("chatgpt_connection", Context.MODE_PRIVATE).edit()
            .putBoolean("connection_enabled", true).commit())
        marker.writeText("retained-across-package-replacement")
    }
    @Test fun seedEnabledForCold() {
        requireFixture()
        assertNotNull(SecureMcpTunnelConfigStore(context).load())
        assertEquals("retained-across-package-replacement", marker.readText())
        assertTrue(context.getSharedPreferences("chatgpt_connection", Context.MODE_PRIVATE).edit()
            .putBoolean("connection_enabled", true).commit())
    }
    @Test fun seedStopped() {
        requireFixture()
        assertEquals("retained-across-package-replacement", marker.readText())
        assertNotNull(SecureMcpTunnelConfigStore(context).load())
        ChatGptMcpService.stop(context)
        assertFalse(ChatGptConnectionSettings(context).enabled)
    }
    @Test fun verifyPreservedAndCleanup() {
        requireFixture()
        assertEquals("retained-across-package-replacement", marker.readText())
        assertFalse(ChatGptConnectionSettings(context).enabled)
        assertEquals("tunnel_" + "0".repeat(32), SecureMcpTunnelConfigStore(context).load()?.tunnelId)
        assertEquals("ci-synthetic-not-a-credential", SecureMcpTunnelConfigStore(context).load()?.runtimeApiKey)
        context.stopService(Intent(context, ChatGptMcpService::class.java))
        SecureMcpTunnelConfigStore(context).clear()
        assertTrue(context.getSharedPreferences("chatgpt_connection", Context.MODE_PRIVATE).edit().clear().commit())
        assertTrue(marker.delete())
    }
}
