package ai.closepaw.chatgpt.control

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build

/** Current OS selection plus the matched unrestricted web filter, never a grant to interact. */
internal object BrowserReadResolver {
    fun permits(pkg: String, resolve: (Intent, Int) -> ResolveInfo?): Boolean = runCatching {
        val components = listOf("http", "https").map { scheme ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.invalid/"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val selected = resolve(intent, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER)
                ?: return@runCatching false
            val activity = selected.activityInfo ?: return@runCatching false
            val filter = selected.filter ?: return@runCatching false
            if (activity.packageName != pkg || activity.name.isNullOrBlank() || !activity.enabled ||
                !activity.exported || activity.applicationInfo?.enabled != true ||
                !filter.hasAction(Intent.ACTION_VIEW) || !filter.hasCategory(Intent.CATEGORY_DEFAULT) ||
                !filter.hasCategory(Intent.CATEGORY_BROWSABLE) || !filter.hasDataScheme(scheme) ||
                filter.countDataAuthorities() != 0 || filter.countDataPaths() != 0 ||
                filter.countDataSchemeSpecificParts() != 0 || filter.countDataTypes() != 0 ||
                (Build.VERSION.SDK_INT >= 35 && filter.countUriRelativeFilterGroups() != 0)) {
                return@runCatching false
            }
            ComponentName(activity.packageName, activity.name)
        }
        components[0] == components[1]
    }.getOrDefault(false)
}
