package ai.closepaw.chatgpt

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class GeminiQuestionSettingsTest {
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
        every { editor.commit() } returns true
    }
    @Test fun draftConsentDoesNotAuthorizeSending() {
        val settings = McpExecutionSettings(context)
        assertTrue(settings.geminiDraftEnabled)
        assertFalse(settings.geminiQuestionEnabled)
    }
    @Test fun sendConsentPersistsIndependentlyAndRevokes() {
        McpExecutionSettings(context).geminiQuestionEnabled = true
        assertTrue(McpExecutionSettings(context).geminiQuestionEnabled)
        McpExecutionSettings(context).geminiQuestionEnabled = false
        val restored = McpExecutionSettings(context)
        assertFalse(restored.geminiQuestionEnabled)
        assertTrue(restored.geminiDraftEnabled)
        assertTrue(restored.enabled)
    }
    @Test fun failedConsentCommitDoesNotReportSuccess() {
        every { editor.commit() } returns false
        assertThrows(IllegalStateException::class.java) { McpExecutionSettings(context).geminiQuestionEnabled = true }
    }
}
