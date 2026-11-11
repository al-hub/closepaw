package ai.closepaw.chatgpt

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class ChatGptConnectionSettingsTest {
    private val values = mutableMapOf<String, Boolean>()
    private val pending = mutableMapOf<String, Boolean>()
    private var writable = true
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val prefs = mockk<SharedPreferences>()
    private val context = mockk<Context>()

    init {
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("chatgpt_connection", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getBoolean(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            pending[firstArg()] = secondArg()
            editor
        }
        every { editor.commit() } answers {
            if (writable) values.putAll(pending)
            writable
        }
    }

    @Test fun existingConfiguredInstallDefaultsToEnabledWithoutChangingCredentials() {
        assertTrue(ChatGptConnectionSettings(context).enabled)
        assertTrue(values.isEmpty())
    }

    @Test fun stopRemainsPausedWhenSettingsAreRecreated() {
        assertTrue(ChatGptConnectionSettings(context).persistEnabled(false))
        val restored = ChatGptConnectionSettings(context)
        assertFalse(restored.enabled)
        assertFalse(ChatGptAutoConnectPolicy.shouldRestore(true, restored.enabled))
    }

    @Test fun connectReenablesFutureRestore() {
        assertTrue(ChatGptConnectionSettings(context).persistEnabled(false))
        assertTrue(ChatGptConnectionSettings(context).persistEnabled(true))
        assertTrue(ChatGptAutoConnectPolicy.shouldRestore(true, ChatGptConnectionSettings(context).enabled))
    }
    @Test fun stopAcknowledgesSynchronousDiskCommitWithoutApply() {
        assertTrue(ChatGptConnectionSettings(context).persistEnabled(false))
        assertEquals(false, values["connection_enabled"])
        verify(exactly = 1) { editor.commit() }
        verify(exactly = 0) { editor.apply() }
    }

    @Test fun failedStopWriteBlocksRestoreInsteadOfReadingOldEnabledIntent() {
        values["connection_enabled"] = true
        writable = false
        try {
            assertFalse(ChatGptConnectionSettings(context).persistEnabled(false))
            assertTrue(values["connection_enabled"] == true) // old durable intent remains
            assertFalse(ChatGptConnectionSettings(context).enabled)
            assertFalse(ChatGptAutoConnectPolicy.shouldRestore(true, ChatGptConnectionSettings(context).enabled))
        } finally {
            writable = true
            assertTrue(ChatGptConnectionSettings(context).persistEnabled(false))
        }
    }

    @Test fun failedConnectCannotAuthorizeRestoreAndSuccessfulExplicitConnectClearsGuard() {
        assertTrue(ChatGptConnectionSettings(context).persistEnabled(false))
        writable = false
        try {
            assertFalse(ChatGptConnectionSettings(context).persistEnabled(true))
            assertFalse(ChatGptConnectionSettings(context).enabled)
        } finally {
            writable = true
            assertTrue(ChatGptConnectionSettings(context).persistEnabled(true))
        }
        assertTrue(ChatGptConnectionSettings(context).enabled)
    }

}
