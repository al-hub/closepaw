package ai.closepaw.chatgpt

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class TailscaleExecutionSettingsTest {
    private val values = mutableMapOf("enabled" to true, "gemini_draft_enabled" to true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val prefs = mockk<SharedPreferences>()
    private val context = mockk<Context>()
    init {
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("mcp_execution", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getBoolean(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers { values[firstArg()] = secondArg(); editor }
    }
    @Test fun newControlDefaultsOffWithoutChangingExistingExecutionPermissions() {
        val settings = McpExecutionSettings(context)
        assertFalse(settings.tailscaleRecoveryEnabled)
        assertTrue(settings.enabled)
        assertTrue(settings.geminiDraftEnabled)
    }
    @Test fun togglingTailscalePersistsIndependently() {
        McpExecutionSettings(context).tailscaleRecoveryEnabled = true
        assertTrue(McpExecutionSettings(context).tailscaleRecoveryEnabled)
        McpExecutionSettings(context).tailscaleRecoveryEnabled = false
        assertFalse(McpExecutionSettings(context).tailscaleRecoveryEnabled)
        assertTrue(McpExecutionSettings(context).enabled)
        assertTrue(McpExecutionSettings(context).geminiDraftEnabled)
    }
}
