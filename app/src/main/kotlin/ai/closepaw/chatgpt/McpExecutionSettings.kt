package ai.closepaw.chatgpt

import android.content.Context
import java.security.SecureRandom

/** Device-owned consent and bearer endpoint, never supplied by remote arguments. */
internal class McpExecutionSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mcp_execution", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    var geminiDraftEnabled: Boolean
        get() = prefs.getBoolean("gemini_draft_enabled", false)
        set(value) { check(prefs.edit().putBoolean("gemini_draft_enabled", value).commit()) }
    @Synchronized fun endpointPath(): String {
        val existing = prefs.getString("endpoint_secret", null)
        val secret = existing ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also {
                check(prefs.edit().putString("endpoint_secret", it).commit())
            }
        return "/mcp/$secret"
    }
}
