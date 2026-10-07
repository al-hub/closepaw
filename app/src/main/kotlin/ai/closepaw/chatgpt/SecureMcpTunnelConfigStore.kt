package ai.closepaw.chatgpt

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal data class SecureMcpTunnelConfig(
    val tunnelId: String,
    val runtimeApiKey: String,
)

internal class SecureMcpTunnelConfigStore(
    private val context: Context,
    private val prefsProvider: (Context) -> SharedPreferences = ::defaultEncryptedPrefs,
) {
    @Volatile private var prefsCache: SharedPreferences? = null

    fun load(): SecureMcpTunnelConfig? {
        val tunnelId = prefs().getString(KEY_TUNNEL_ID, null)?.trim().orEmpty()
        val runtimeApiKey = prefs().getString(KEY_RUNTIME_API_KEY, null)?.trim().orEmpty()
        if (tunnelId.isBlank() || runtimeApiKey.isBlank()) return null
        return SecureMcpTunnelConfig(tunnelId = tunnelId, runtimeApiKey = runtimeApiKey)
    }

    fun save(tunnelId: String, runtimeApiKey: String) {
        val normalizedId = tunnelId.trim()
        val normalizedKey = runtimeApiKey.trim()
        require(TUNNEL_ID_REGEX.matches(normalizedId)) {
            "Tunnel ID must be tunnel_ followed by 32 lowercase hex characters."
        }
        require(normalizedKey.isNotBlank()) { "Runtime API key is required." }

        prefs().edit()
            .putString(KEY_TUNNEL_ID, normalizedId)
            .putString(KEY_RUNTIME_API_KEY, normalizedKey)
            .apply()
    }

    fun clear() {
        prefs().edit()
            .remove(KEY_TUNNEL_ID)
            .remove(KEY_RUNTIME_API_KEY)
            .apply()
    }

    private fun prefs(): SharedPreferences {
        prefsCache?.let { return it }
        return prefsProvider(context.applicationContext).also { prefsCache = it }
    }

    companion object {
        private const val PREFS_NAME = "secure_mcp_tunnel"
        private const val KEY_TUNNEL_ID = "tunnel_id"
        private const val KEY_RUNTIME_API_KEY = "runtime_api_key"
        internal val TUNNEL_ID_REGEX = Regex("^tunnel_[0-9a-f]{32}$")

        private fun defaultEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
