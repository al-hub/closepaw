package ai.closepaw.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.serialization.json.*

/** Public, app-scoped APIs only. A successful probe is never a silent-install guarantee. */
internal object SelfUpdateDiagnostics {
    fun collect(context: Context): JsonObject = buildJsonObject {
        val pm = context.packageManager
        put("android_api", Build.VERSION.SDK_INT)
        put("android_release", Build.VERSION.RELEASE)
        put("manufacturer", Build.MANUFACTURER.take(80))
        put("model", Build.MODEL.take(80))
        put("target_sdk", context.applicationInfo.targetSdkVersion)
        put("one_ui_version", JsonNull)
        put("one_ui_version_observation", "not_available_via_public_api")
        put("install_source_allowed", pm.canRequestPackageInstalls())
        put("update_without_user_action_permission", context.checkSelfPermission(
            "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION") == PackageManager.PERMISSION_GRANTED)
        put("notifications_enabled", context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled())
        put("remote_install_path", "package_installer_session")
        put("manual_install_path", "apk_view_intent")
        put("silent_install_guaranteed", false)
        put("security_warning_owner", JsonNull)
        put("security_settings_observation", "not_observable_via_this_probe")
        put("past_manual_install_callbacks", "not_recorded_by_session_updater")
        val source = runCatching { pm.getInstallSourceInfo(context.packageName) }.getOrNull()
        put("install_source_observed", source != null)
        put("installing_package", source?.installingPackageName?.let(::JsonPrimitive) ?: JsonNull)
        // Resolve the manual APK handler without opening a screen or granting URI access.
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://${context.packageName}.updates/diagnostic.apk"), "application/vnd.android.package-archive")
        put("manual_installer_package", runCatching {
            pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()?.let(::JsonPrimitive) ?: JsonNull)
        put("manual_installer_observation", "resolver_only_not_warning_attribution")
    }
}

/** Only bounded routing metadata; no Intent extras, URIs, raw status messages, or screen text. */
internal object InstallerCallbackEvidence {
    fun build(status: Int, relatedPackage: String?, action: String?, component: String?,
        resolvedPackage: String?, observedAt: Long): JsonObject = buildJsonObject {
        put("status", status)
        put("observed_at_ms", observedAt)
        fun metadata(key: String, value: String?) {
            if (value != null && value.length <= 256 && value.matches(Regex("[A-Za-z0-9_.$/]+"))) put(key, value)
        }
        metadata("related_package", relatedPackage)
        metadata("confirmation_action", action)
        metadata("confirmation_component", component)
        metadata("confirmation_resolved_package", resolvedPackage)
        put("warning_owner", JsonNull)
        put("warning_owner_observation", "not_proven_by_confirmation_handler")
    }
}
