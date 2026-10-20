package ai.closepaw.chatgpt.control

import ai.closepaw.platform.AppInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

internal class AppCatalogUnavailable(val reasonCode: String) : IllegalStateException()

/** Query once, enforce policy before expensive label loads, and deduplicate before loading resources. */
internal class AndroidAppCatalog(private val pm: PackageManager, private val packageAllowed: (String) -> Boolean) {
    private data class Label(val value: String, val loadedAt: Long)
    private val labels = mutableMapOf<String, Label>()
    suspend fun list(filter: String): List<AppInfo> = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        var loads = 0
        var hits = 0
        val launcher = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val entries = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        val queriedAt = System.nanoTime()
        currentCoroutineContext().ensureActive()
        labels.keys.retainAll(entries.mapNotNull { it.activityInfo?.packageName }.toSet())
        val result = entries
            .filter { it.activityInfo?.packageName?.let(packageAllowed) == true }
            .distinctBy { it.activityInfo.packageName }
            .map { resolved ->
                val activity = resolved.activityInfo
                currentCoroutineContext().ensureActive()
                val stamp = System.nanoTime()
                val cached = labels[activity.packageName]?.takeIf { stamp - it.loadedAt in 0..30_000_000_000L }
                val label = if (cached != null) { hits++; cached.value } else {
                    loads++
                    val value = runCatching { resolved.loadLabel(pm)?.toString() }.getOrNull().orEmpty().ifBlank { activity.packageName }
                    labels[activity.packageName] = Label(value, stamp)
                    value
                }
                AppInfo(activity.packageName, label, ((activity.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0)
            }
            .filter { it.packageName.contains(filter, true) || it.label.contains(filter, true) }
            .sortedWith(compareBy({ it.label }, { it.packageName })).take(40)
        ControlDiagnostics.record("app_catalog", "completed", (System.nanoTime() - started) / 1_000_000,
            metrics = kotlinx.serialization.json.buildJsonObject {
                put("query_ms", (queriedAt - started) / 1_000_000)
                put("label_loads", loads); put("label_cache_hits", hits)
            })
        result
    }
}
