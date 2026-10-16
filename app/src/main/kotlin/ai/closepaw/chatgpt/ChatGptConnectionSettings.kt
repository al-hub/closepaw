package ai.closepaw.chatgpt

import android.content.Context

internal class ChatGptConnectionSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("chatgpt_connection", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("connection_enabled", true)
        set(value) { prefs.edit().putBoolean("connection_enabled", value).apply() }
}
