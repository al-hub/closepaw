package ai.closepaw.chatgpt

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class ChatGptConnectionSettingsTest {
    private val values = mutableMapOf<String, Boolean>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val prefs = mockk<SharedPreferences>()
    private val context = mockk<Context>()

    init {
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("chatgpt_connection", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getBoolean(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            values[firstArg()] = secondArg()
            editor
        }
    }

    @Test fun existingConfiguredInstallDefaultsToEnabledWithoutChangingCredentials() {
        assertTrue(ChatGptConnectionSettings(context).enabled)
        assertTrue(values.isEmpty())
    }

    @Test fun stopRemainsPausedWhenSettingsAreRecreated() {
        ChatGptConnectionSettings(context).enabled = false
        val restored = ChatGptConnectionSettings(context)
        assertFalse(restored.enabled)
        assertFalse(ChatGptAutoConnectPolicy.shouldRestore(true, restored.enabled))
    }

    @Test fun connectReenablesFutureRestore() {
        ChatGptConnectionSettings(context).enabled = false
        ChatGptConnectionSettings(context).enabled = true
        assertTrue(ChatGptAutoConnectPolicy.shouldRestore(true, ChatGptConnectionSettings(context).enabled))
    }
}
