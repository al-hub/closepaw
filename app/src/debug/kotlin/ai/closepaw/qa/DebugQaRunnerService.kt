package ai.closepaw.qa

import ai.closepaw.BuildConfig
import ai.closepaw.platform.virtualdisplay.ProjectionImageTransport
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Explicitly armed, candidate-only foreground QA runner. The OS-approved
 * projection and accessibility service remain independent owners of their
 * permissions. This service owns only the bounded QA coroutine and checkpoints.
 *
 * START_NOT_STICKY: process/service death is NOT permission to replay touches,
 * restart a projection, or resume a partially attempted key.
 */
/** An UNKNOWN, blocked, or only partially verified run is never repeated. */
internal object DebugQaBatchPolicy {
    const val REPETITIONS = 3
    fun mayProceedAfter(result: JsonObject): Boolean =
        result["outcome"]?.jsonPrimitive?.content == "PASS" &&
            result["goal_verified"]?.jsonPrimitive?.content == "true" &&
            result["completed_keys"]?.jsonPrimitive?.intOrNull == 6 &&
            result["touch_attempts"]?.jsonPrimitive?.intOrNull == 6 &&
            result["outcome_unknown"]?.jsonPrimitive?.content == "false"
}

class DebugQaRunnerService : Service() {
    companion object {
        private const val CHANNEL = "debug_qa_runner"
        private const val NOTIFICATION_ID = 14701
        private const val ACTION_RUN = "ai.closepaw.qa.RUN_USER_ARMED"
        private const val ACTION_DIAG = "ai.closepaw.qa.DIAG_USER_ARMED"
        private const val ACTION_CANCEL = "ai.closepaw.qa.CANCEL_USER_ARMED"
        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        /** Call only from the physically visible local QA screen or its OS consent callback. */
        fun start(context: Context, diagnosticOnly: Boolean = false): Boolean {
            if (!BuildConfig.DEBUG || context.packageName != CaptureQaAcceptance.CANDIDATE ||
                running.get()) return false
            return runCatching {
                ContextCompat.startForegroundService(context,
                    Intent(context, DebugQaRunnerService::class.java)
                        .setAction(if (diagnosticOnly) ACTION_DIAG else ACTION_RUN))
                true
            }.getOrDefault(false)
        }

        /** Cancel only on a local user gesture; never let remote QA re-arm itself. */
        fun cancel(context: Context) {
            if (running.get() && context.packageName == CaptureQaAcceptance.CANDIDATE)
                context.startService(Intent(context, DebugQaRunnerService::class.java)
                    .setAction(ACTION_CANCEL))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ClosePaw DEBUG QA", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(message: String = "User-approved local test · results saved on device",
                             error: Boolean = false): Notification {
        val viewIntent = PendingIntent.getActivity(this, 14701,
            Intent(this, LocalCaptureQaActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancelIntent = PendingIntent.getService(this, 14702,
            Intent(this, DebugQaRunnerService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(if (error) "ClosePaw DEBUG · QA errors, session continuing"
                else "ClosePaw DEBUG · guarded QA")
            .setContentText(message.take(96))
            .setColor(if (error) android.graphics.Color.RED else android.graphics.Color.rgb(210, 109, 18))
            .setOngoing(true)
            .setContentIntent(viewIntent)
            .addAction(Notification.Action.Builder(null, "Stop QA", cancelIntent).build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BuildConfig.DEBUG || packageName != CaptureQaAcceptance.CANDIDATE) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            if (job == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent?.action !in setOf(ACTION_RUN, ACTION_DIAG) || !running.compareAndSet(false, true))
            return START_NOT_STICKY

        try {
            startForeground(NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (_: Exception) {
            running.set(false)
            runCatching { DebugOneStopQa.recordUnavailable(this, "debug_qa_foreground_unavailable") }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val diagnosticOnly = intent?.action == ACTION_DIAG
        job = scope.launch {
            val batchId = UUID.randomUUID().toString()
            val results = mutableListOf<JsonObject>()
            try {
                DebugOneStopQa.saveBatch(applicationContext, batchId, "IN_PROGRESS", results, diagnosticOnly)
                // Consent callback can precede the independent projection service's
                // lease publication. Wait here, NOT in the Activity lifecycle.
                val ready = withTimeoutOrNull(12_000L) {
                    while (ProjectionImageTransport.active()?.screenObservationAllowed() != true)
                        delay(150L)
                    true
                } == true
                if (!ready) {
                    DebugOneStopQa.recordUnavailable(applicationContext,
                        "android_observation_approval_required")
                    DebugOneStopQa.saveBatch(applicationContext, batchId, "BLOCKED", results, diagnosticOnly)
                    return@launch
                }
                var blocked = false
                for (index in 0 until (if (diagnosticOnly) 1 else DebugQaBatchPolicy.REPETITIONS)) {
                    // Each run creates and independently verifies a NEW empty Chrome
                    // editor. Never replay a partially attempted key or a failed run.
                    val result = DebugOneStopQa.run(applicationContext, { progress ->
                        runCatching {
                            getSystemService(NotificationManager::class.java).notify(
                                NOTIFICATION_ID, notification(progress,
                                    error = progress.contains("errors=") &&
                                        !progress.endsWith("errors=0")))
                        }
                    }, diagnosticOnly, batchId)
                    results += result
                    DebugOneStopQa.saveBatch(applicationContext, batchId, "IN_PROGRESS", results, diagnosticOnly)
                    if (diagnosticOnly) {
                        blocked = result["outcome"]?.jsonPrimitive?.content != "DIAGNOSTIC_COMPLETE"
                        break // Never interpret structural evidence as a typed six-key PASS.
                    }
                    if (!DebugQaBatchPolicy.mayProceedAfter(result)) {
                        blocked = true
                        break
                    }
                    if (index < DebugQaBatchPolicy.REPETITIONS - 1) delay(500L)
                }
                DebugOneStopQa.saveBatch(applicationContext, batchId,
                    if (blocked) "BLOCKED" else if (diagnosticOnly) "DIAGNOSTIC_COMPLETE" else "PASS", results, diagnosticOnly)
            } catch (_: CancellationException) {
                // The individual run records its non-replayable cancellation
                // checkpoint. No service or process resurrection is attempted.
                runCatching {
                    DebugOneStopQa.saveBatch(applicationContext, batchId, "CANCELLED", results, diagnosticOnly)
                }
            } catch (_: Exception) {
                runCatching {
                    DebugOneStopQa.recordUnavailable(applicationContext, "debug_qa_service_failed")
                    DebugOneStopQa.saveBatch(applicationContext, batchId, "FAILED", results, diagnosticOnly)
                }
            } finally {
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                // A later explicit Stop intent may have a newer startId; this
                // runner owns the service until its bounded work is finished.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        running.set(false)
        super.onDestroy()
    }
}
