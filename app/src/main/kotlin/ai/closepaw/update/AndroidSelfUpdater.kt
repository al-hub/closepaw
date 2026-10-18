package ai.closepaw.update

import ai.closepaw.BuildConfig
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import java.io.File
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/** Uses Android's package installer; never clicks through or disables security checks. */
internal class AndroidSelfUpdater private constructor(private val context: Context) {
    private val ledger = SelfUpdateLedger(File(context.filesDir, "updates/self-update.json"))
    private var approval: PendingIntent? = null
    private var running = false
    private val installer get() = context.packageManager.packageInstaller

    @Synchronized fun status(): JsonObject {
        val record = ledger.read()
        if (SelfUpdatePolicy.installed(record, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())) {
            ledger.patch("state" to JsonPrimitive("installed"), "installed_version" to JsonPrimitive(BuildConfig.VERSION_NAME))
        } else if (!running && record["state"]?.jsonPrimitive?.content in setOf("checking", "downloading")) {
            ledger.patch("state" to JsonPrimitive("interrupted_before_install"))
        } else if (record["state"]?.jsonPrimitive?.content in setOf("installing", "user_action_required")) {
            val id = record["session_id"]?.jsonPrimitive?.intOrNull
            if (id != null && installer.getSessionInfo(id) == null) {
                ledger.patch("state" to JsonPrimitive("install_outcome_unknown"))
            }
        }
        return JsonObject(ledger.read() + mapOf(
            "current_version" to JsonPrimitive(BuildConfig.VERSION_NAME),
            "current_code" to JsonPrimitive(BuildConfig.VERSION_CODE),
            "install_source_allowed" to JsonPrimitive(context.packageManager.canRequestPackageInstalls()),
            "remote_update_enabled" to JsonPrimitive(enabled()),
        ))
    }

    fun enabled(): Boolean = context.getSharedPreferences("mcp_execution", Context.MODE_PRIVATE)
        .getBoolean("self_update_enabled", false)

    /** Serialized across all callers. An unresolved install is never automatically resubmitted. */
    @Synchronized fun start(taskId: String, expectedVersion: String): JsonObject {
        check(enabled()) { "Remote self-update is disabled locally." }
        val previous = status()
        if (previous["state"]?.jsonPrimitive?.content in SelfUpdatePolicy.activeStates) return previous
        if (!context.packageManager.canRequestPackageInstalls()) {
            ledger.write(buildJsonObject { put("state", "install_permission_required"); put("task_id", taskId) })
            return status()
        }
        var sessionId: Int? = null
        var commitAttempted = false
        try {
            running = true
            ledger.write(buildJsonObject { put("state", "checking"); put("task_id", taskId); put("target_version", expectedVersion) })
            val checked = AppUpdater.check()
            if (checked is AppUpdater.CheckResult.UpToDate) {
                ledger.patch("state" to JsonPrimitive("up_to_date")); return status()
            }
            check(checked is AppUpdater.CheckResult.Available) { "Release check failed." }
            require(checked.release.version == expectedVersion) { "Latest release changed; check and authorize its version again." }
            ledger.patch("state" to JsonPrimitive("downloading"))
            val apk = AppUpdater.downloadAndVerify(context, checked.release).getOrThrow()
            val pm = context.packageManager
            val candidate = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: error("APK package metadata unavailable.")
            check(enabled()) { "Remote self-update permission revoked." }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            sessionId = id
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                ledger.patch("state" to JsonPrimitive("installing"), "session_id" to JsonPrimitive(id),
                    "target_code" to JsonPrimitive(candidate.longVersionCode))
                val callback = PendingIntent.getBroadcast(context, id,
                    Intent(context, SelfUpdateReceiver::class.java).setAction(CALLBACK).putExtra("expected_session_id", id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                check(enabled()) { "Remote self-update permission revoked before install." }
                // From this boundary onward a missing callback means unknown, never safe to replay.
                commitAttempted = true
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            if (!commitAttempted) sessionId?.let { runCatching { installer.abandonSession(it) } }
            ledger.patch("state" to JsonPrimitive(if (commitAttempted) "install_outcome_unknown" else "failed"),
                "reason" to JsonPrimitive(error.message ?: error.javaClass.simpleName))
        } finally { running = false }
        return status()
    }

    @Synchronized fun onResult(intent: Intent) {
        val record = ledger.read()
        val id = record["session_id"]?.jsonPrimitive?.intOrNull ?: return
        if (intent.getIntExtra("expected_session_id", -1) != id ||
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id) return
        if (record["state"]?.jsonPrimitive?.content == "installed") return
        val result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        @Suppress("DEPRECATION")
        val confirmation = if (result == PackageInstaller.STATUS_PENDING_USER_ACTION)
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) else null
        val resolvedPackage = confirmation?.let { request -> runCatching {
            context.packageManager.resolveActivity(request, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull() }
        val event = InstallerCallbackEvidence.build(result,
            intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME),
            confirmation?.action, confirmation?.component?.flattenToShortString(), resolvedPackage, System.currentTimeMillis())
        val history = (record["installer_callbacks"] as? JsonArray)?.toList().orEmpty()
        ledger.patch("installer_callbacks" to JsonArray((history + event).takeLast(8)))
        when (result) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                ledger.patch("state" to JsonPrimitive("user_action_required"))
                if (confirmation == null) return
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Software updates", NotificationManager.IMPORTANCE_HIGH))
                val pending = PendingIntent.getActivity(context, id, confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                approval = pending
                runCatching {
                    manager.notify(NOTIFICATION, NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("ClosePaw update needs approval")
                        .setContentText("Tap to review Android's installation request.").setContentIntent(pending).setAutoCancel(true).build())
                }
                // The local update page can also launch the exact system-provided approval.
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Only the new running build can confirm the target package was installed.
                ledger.patch("state" to JsonPrimitive("installing")); status()
            }
            else -> {
                approval = null
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
                ledger.patch("state" to JsonPrimitive("failed"), "installer_status" to JsonPrimitive(result))
            }
        }
    }

    @Synchronized fun openApproval(): Boolean {
        if (status()["state"]?.jsonPrimitive?.content != "user_action_required") return false
        return runCatching { (approval ?: return false).send(); true }.getOrDefault(false)
    }

    companion object {
        const val CALLBACK = "ai.closepaw.SELF_UPDATE_RESULT"
        private const val CHANNEL = "software_updates"
        private const val NOTIFICATION = 4105
        @Volatile private var instance: AndroidSelfUpdater? = null
        fun get(context: Context): AndroidSelfUpdater = instance ?: synchronized(this) {
            instance ?: AndroidSelfUpdater(context.applicationContext).also { instance = it }
        }
    }
}

class SelfUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidSelfUpdater.CALLBACK) return
        val pending = goAsync()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        scope.launch {
            try { runCatching { AndroidSelfUpdater.get(context).onResult(intent) } }
            finally { pending.finish(); scope.cancel() }
        }
    }
}
