package ai.closepaw.chatgpt

import android.content.Context

internal class ChatGptConnectionSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("chatgpt_connection", Context.MODE_PRIVATE)

    val enabled: Boolean
        get() = !persistenceBlocked && prefs.getBoolean("connection_enabled", true)

    /** Caller runs on IO. Returning success means the intent is durable before process replacement. */
    fun persistEnabled(value: Boolean): Boolean = synchronized(persistenceLock) {
        val saved = runCatching { prefs.edit().putBoolean("connection_enabled", value).commit() }
            .getOrDefault(false)
        // Failed writes cannot authorize restore in this process. Only a later
        // explicit, successful Connect/Stop write clears this conservative guard.
        persistenceBlocked = !saved
        saved
    }

    companion object {
        private val persistenceLock = Any()
        @Volatile internal var persistenceBlocked: Boolean = false
            private set
    }
}
