package ai.closepaw.qa

import ai.closepaw.BuildConfig
import ai.closepaw.chatgpt.McpExecutionSettings
import ai.closepaw.chatgpt.control.RuntimeControlSettings
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import kotlinx.serialization.json.*

/**
 * Local, candidate-only, explicitly user-approved Samsung IME interaction grant.
 *
 * This is not Android Accessibility/MediaProjection approval and does not bypass either.
 * It modifies only the already-existing runtime package policy through its validated API.
 * Remote control, exported intents, ADB scripts and QA code cannot call the user-consent UI.
 */
internal object DebugImeInteractConsent {
    const val SAMSUNG_IME = "com.samsung.android.honeyboard"

    private fun candidate(context: Context) =
        BuildConfig.DEBUG && context.packageName == CaptureQaAcceptance.CANDIDATE

    /** Independently verify the active and enabled keyboard using OS state. */
    fun selectedSamsungIme(context: Context): Boolean {
        if (!candidate(context)) return false
        return runCatching {
            val selected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?: return@runCatching false
            if (ComponentName.unflattenFromString(selected)?.packageName != SAMSUNG_IME)
                return@runCatching false
            context.getSystemService(InputMethodManager::class.java)
                .enabledInputMethodList.any { it.id == selected && it.packageName == SAMSUNG_IME }
        }.getOrDefault(false)
    }

    /** UI display only; the native port still checks the classifier, window, editor and consent. */
    fun explicitlyGranted(context: Context): Boolean {
        if (!candidate(context)) return false
        return runCatching {
            McpExecutionSettings(context).runtimeControl.snapshot().config.getValue("apps")
                .jsonObject[SAMSUNG_IME]?.jsonPrimitive?.content == "interact"
        }.getOrDefault(false)
    }

    /**
     * Pure policy transformation for unit tests. Never overwrite a prior explicit
     * deny/read choice, change any other app, or broaden normal/default access.
     */
    fun nextConfig(current: JsonObject, grant: Boolean): JsonObject? {
        if (!RuntimeControlSettings.validate(current)) return null
        val apps = current.getValue("apps").jsonObject
        val previous = apps[SAMSUNG_IME]?.jsonPrimitive?.content
        if (grant && previous != null && previous != "interact") return null
        if (!grant && previous != "interact") return null
        if (grant && previous == "interact") return current
        if (grant && apps.size >= 64) return null

        val updatedApps = apps.toMutableMap()
        if (grant) updatedApps[SAMSUNG_IME] = JsonPrimitive("interact")
        else updatedApps.remove(SAMSUNG_IME)
        val updated = JsonObject(current.toMutableMap().apply {
            put("apps", JsonObject(updatedApps))
        })
        return updated.takeIf { RuntimeControlSettings.validate(it) }
    }

    /**
     * Must be called only from the candidate's physical on-screen consent handler.
     * No silent permission change occurs at launch, on resume, or by QA automation.
     */
    fun updateFromLocalConsent(context: Context, grant: Boolean): Boolean {
        if (!candidate(context) || (grant && !selectedSamsungIme(context))) return false
        return runCatching {
            val store = McpExecutionSettings(context).runtimeControl
            val before = store.snapshot()
            val next = nextConfig(before.config, grant) ?: return@runCatching false
            if (next != before.config) store.change(before.revision, "apply", next)
            explicitlyGranted(context) == grant
        }.getOrDefault(false)
    }
}
