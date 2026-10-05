package ai.closepaw.termux

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

internal object TermuxBridgeAuth {
    private const val PREFS = "termux_bridge_auth"
    private const val KEY_TOKEN = "token"

    fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.takeIf { it.length >= 32 }?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        check(prefs.edit().putString(KEY_TOKEN, token).commit()) { "Failed to persist Termux bridge token" }
        return token
    }
}
