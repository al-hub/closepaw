package ai.closepaw.termux

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

private val tokenCreationLock = Any()

internal fun getOrCreateBridgeToken(
    read: () -> String?,
    persist: (String) -> Boolean,
    generate: () -> String,
): String = synchronized(tokenCreationLock) {
    read()?.takeIf { it.length >= 32 }?.let { return@synchronized it }

    val token = generate()
    check(persist(token)) { "Failed to persist Termux bridge token" }

    // A second writer in this process cannot interleave because creation is serialized.
    // Re-read so storage remains the single source of truth.
    read()?.takeIf { it.length >= 32 } ?: token
}

internal object TermuxBridgeAuth {
    private const val PREFS = "termux_bridge_auth"
    private const val KEY_TOKEN = "token"

    fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return getOrCreateBridgeToken(
            read = { prefs.getString(KEY_TOKEN, null) },
            persist = { value -> prefs.edit().putString(KEY_TOKEN, value).commit() },
            generate = {
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            },
        )
    }
}
