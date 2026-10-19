package ai.closepaw.chatgpt.control

import ai.closepaw.platform.AppInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AppCatalogUnavailable(val reasonCode: String) : IllegalStateException()

/** Query once, enforce policy before expensive label loads, and deduplicate before loading resources. */
internal class AndroidAppCatalog(private val pm: PackageManager, private val packageAllowed: (String) -> Boolean) {
    suspend fun list(filter: String): List<AppInfo> = withContext(Dispatchers.IO) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .filter { it.activityInfo?.packageName?.let(packageAllowed) == true }
            .distinctBy { it.activityInfo.packageName }
            .map { resolved ->
                val activity = resolved.activityInfo
                val label = runCatching { resolved.loadLabel(pm)?.toString() }.getOrNull().orEmpty().ifBlank { activity.packageName }
                AppInfo(activity.packageName, label, ((activity.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0)
            }
            .filter { it.packageName.contains(filter, true) || it.label.contains(filter, true) }
            .sortedWith(compareBy({ it.label }, { it.packageName })).take(40)
    }
}
