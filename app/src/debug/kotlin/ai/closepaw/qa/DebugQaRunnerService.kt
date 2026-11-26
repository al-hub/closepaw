package ai.closepaw.qa

import ai.closepaw.BuildConfig
import ai.closepaw.app.AgentService
import ai.closepaw.platform.virtualdisplay.MediaProjectionCaptureService
import ai.closepaw.platform.virtualdisplay.ProjectionImageTransport
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** An UNKNOWN, blocked, or only partially verified run is never repeated. */
internal object DebugQaBatchPolicy {
    const val REPETITIONS = 3

    fun terminalState(mode: DebugQaMode, result: JsonObject): String =
        when (result["outcome"]?.jsonPrimitive?.content) {
            "UNKNOWN" -> "UNKNOWN"
            "DIAGNOSTIC_COMPLETE" -> if (mode.diagnosticOnly) "DIAGNOSTIC_COMPLETE" else "BLOCKED"
            "PASS" -> if (!mode.diagnosticOnly && mayProceedAfter(result)) "PASS" else "BLOCKED"
            else -> "BLOCKED"
        }

    fun mayProceedAfter(result: JsonObject): Boolean =
        result["outcome"]?.jsonPrimitive?.content == "PASS" &&
            result["goal_verified"]?.jsonPrimitive?.content == "true" &&
            result["completed_keys"]?.jsonPrimitive?.intOrNull == 6 &&
            result["touch_attempts"]?.jsonPrimitive?.intOrNull == 6 &&
            result["outcome_unknown"]?.jsonPrimitive?.content == "false"
}

internal data class DebugQaStatus(
    val request: DebugQaRunRequest,
    val state: String,
    val stage: String,
    val export: DebugQaExportOutcome? = null,
    val batchId: String? = null,
) {
    fun description(): String = buildString {
        append("$state · ${request.mode.label} · ${stage.replace('_', ' ')}")
        append("\nRequest ${request.id}")
        batchId?.let { append(" · Batch $it") }
        when (export) {
            DebugQaExportOutcome.SAVED -> append("\nJSON saved to Downloads/ClosePawQA")
            DebugQaExportOutcome.FAILED -> append("\nDownloads JSON export failed")
            DebugQaExportOutcome.REVOKED -> append("\nDownloads export permission revoked")
            DebugQaExportOutcome.NOT_ENABLED -> append("\nDownloads export disabled for this run")
            null -> Unit
        }
    }
}

/** Private status transport for local Debug QA and its island. */
internal object DebugQaRunnerStatusStore {
    private val status = MutableStateFlow<DebugQaStatus?>(null)
    val updates = status.asStateFlow()

    @Synchronized
    fun publish(context: Context, value: DebugQaStatus) {
        status.value = value
        runCatching {
            val file = File(context.filesDir, "debug-qa/runner-status.json")
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                val json = buildJsonObject {
                    put("schema_version", 2)
                    put("scope", "local_debug_qa")
                    put("source_commit", BuildConfig.CAPTURE_QA_SOURCE_SHA)
                    put("state", value.state)
                    put("stage", value.stage)
                    put("request_id", value.request.id)
                    put("mode", value.request.mode.code)
                    value.batchId?.let { put("batch_id", it) }
                    value.export?.let { put("export", it.name) }
                    put("updated_at_epoch_ms", System.currentTimeMillis())
                    put("updated_at_elapsed_ms", SystemClock.elapsedRealtime())
                    put("process_start_elapsed_ms", android.os.Process.getStartElapsedRealtime())
                }
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (failure: Exception) {
                atomic.failWrite(output)
                throw failure
            }
        }.onFailure { Log.w("ClosePawDebugQA", "status_store_failed request_id=${value.request.id}") }
    }

    fun savedStatusJson(context: Context): JsonObject? = runCatching {
        val file = File(context.filesDir, "debug-qa/runner-status.json")
        if (!file.isFile || file.length() !in 1L..2048L) return@runCatching null
        val fields = Json.parseToJsonElement(file.readText()).jsonObject
        fields.takeIf { it["source_commit"]?.jsonPrimitive?.content == BuildConfig.CAPTURE_QA_SOURCE_SHA }
    }.getOrNull()

    fun savedDescription(context: Context): String = runCatching {
        val file = File(context.filesDir, "debug-qa/runner-status.json")
        if (!file.isFile || file.length() !in 1L..2048L) return@runCatching "QA result: none"
        val fields = Json.parseToJsonElement(file.readText()).jsonObject
        if (fields["source_commit"]?.jsonPrimitive?.content != BuildConfig.CAPTURE_QA_SOURCE_SHA)
            return@runCatching "QA result: none for this build"
        val mode = DebugQaMode.entries.first { it.code == fields.getValue("mode").jsonPrimitive.content }
        val request = DebugQaRunRequest(mode, id = fields.getValue("request_id").jsonPrimitive.content)
        val batch = fields["batch_id"]?.jsonPrimitive?.content
        val state = fields.getValue("state").jsonPrimitive.content
        if (state == "RUNNING" || state == "WAITING") {
            val terminalBatch = DebugOneStopQa.lastBatchResult(context)
                ?.takeIf { it["batch_id"]?.jsonPrimitive?.content == batch && batch != null }
            val savedState = terminalBatch?.get("state")?.jsonPrimitive?.content
            if (savedState != null && savedState != "IN_PROGRESS") {
                return@runCatching DebugQaStatus(request, savedState, "export_status_unavailable", batchId = batch).description()
            }
            return@runCatching DebugQaStatus(request, "INTERRUPTED", "no_automatic_replay", batchId = batch).description()
        }
        DebugQaStatus(request, state, fields.getValue("stage").jsonPrimitive.content,
            fields["export"]?.jsonPrimitive?.content?.let(DebugQaExportOutcome::valueOf),
            batch).description()
    }.getOrDefault("QA result: unavailable")

    fun clear() { status.value = null }
}

/** Candidate-only, explicitly armed foreground QA. Service/process restart never replays a request. */
class DebugQaRunnerService : Service() {
    companion object {
        private const val CHANNEL = "debug_qa_runner"
        private const val NOTIFICATION_ID = 14701
        private const val ACTION_CANCEL = "ai.closepaw.qa.CANCEL_USER_ARMED"
        private const val REQUEST_ID = "local_run_request_id"
        internal val admission = DebugQaRunAdmission()
        internal val processId = UUID.randomUUID().toString()
        // Serialized off-main-thread IO lane for pre-run persistence & atomic MediaStore export.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val preRunIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        fun isRunning(): Boolean = admission.active != null
        fun isBusy(): Boolean = admission.current != null

        internal fun audit(request: DebugQaRunRequest, outcome: String, reason: String) {
            Log.i("ClosePawDebugQA", "request_id=${request.id} mode=${request.mode.code} outcome=$outcome reason=$reason")
        }

        private fun persistPreRunAsync(
            appContext: Context,
            request: DebugQaRunRequest?,
            mode: DebugQaMode?,
            requestId: String,
            exportGrant: String?,
            state: String,
            reason: String,
            stage: String,
            admissionOutcome: DebugQaAdmissionOutcome?,
            consent: DebugQaConsent?,
        ) {
            val batchId = UUID.randomUUID().toString()
            val candidateSessionState = AgentService.instance?.remoteControlSession?.state
            val snapshot = DebugQaLifecyclePolicy.buildPreRunBatchSnapshot(
                batchId = batchId,
                requestId = requestId,
                sourceCommit = BuildConfig.CAPTURE_QA_SOURCE_SHA,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                mode = mode,
                state = state,
                stage = stage,
                reason = reason,
                admissionOutcome = admissionOutcome,
                consent = consent,
                candidateControlSessionState = candidateSessionState,
            )
            preRunIoScope.launch {
                val (allowLatest, saved) = synchronized(DebugOneStopQa.batchFileLock) {
                    val idle = !isBusy()
                    val ok = runCatching {
                        DebugOneStopQa.savePreRunBatch(appContext, snapshot, overwriteLatest = idle)
                    }.isSuccess
                    idle to ok
                }
                // Always export this specific pre-run batch_id from history/batch-<id>.json when
                // grant is enabled, even if a live batch is running (never overwriting live latest).
                val export = if (saved) {
                    DebugQaDownloadExport.completePreRun(appContext, exportGrant, batchId)
                } else if (exportGrant == null) {
                    DebugQaExportOutcome.NOT_ENABLED
                } else {
                    DebugQaExportOutcome.FAILED
                }
                if (allowLatest) {
                    val statusRequest = request ?: mode?.let { DebugQaRunRequest(it, exportGrant, requestId) }
                    if (statusRequest != null) {
                        synchronized(DebugOneStopQa.batchFileLock) {
                            if (!isBusy()) {
                                DebugQaRunnerStatusStore.publish(
                                    appContext,
                                    DebugQaStatus(statusRequest, state, reason, export, batchId),
                                )
                            }
                        }
                    }
                }
            }
        }

        internal fun reject(
            context: Context,
            request: DebugQaRunRequest,
            state: String,
            reason: String,
            stage: String = reason,
            admissionOutcome: DebugQaAdmissionOutcome = DebugQaAdmissionOutcome.DENIED,
            consent: DebugQaConsent? = null,
        ) {
            audit(request, state, reason)
            if (admission.finish(request)) {
                persistPreRunAsync(
                    appContext = context.applicationContext,
                    request = request,
                    mode = request.mode,
                    requestId = request.id,
                    exportGrant = request.exportGrant,
                    state = state,
                    reason = reason,
                    stage = stage,
                    admissionOutcome = admissionOutcome,
                    consent = consent,
                )
            }
        }

        /**
         * Persists an unreserved pre-run rejection/drop (e.g. BUSY/DUPLICATE admission or
         * OS consent returning after process death) off the main thread with correlation IDs
         * while holding [DebugOneStopQa.batchFileLock] so no reserved/queued/active live batch
         * is ever overwritten.
         */
        internal fun recordUnreservedPreRunOutcome(
            context: Context,
            mode: DebugQaMode?,
            requestId: String?,
            exportGrant: String?,
            state: String,
            reason: String,
            stage: String,
            admissionOutcome: DebugQaAdmissionOutcome? = null,
            consent: DebugQaConsent? = null,
        ) {
            if (!BuildConfig.DEBUG || context.packageName != CaptureQaAcceptance.CANDIDATE) return
            val safeRequestId = DebugQaLifecyclePolicy.safeUuidOrFresh(requestId)
            Log.i("ClosePawDebugQA",
                "request_id=$safeRequestId mode=${mode?.code ?: "unknown"} outcome=$state reason=$reason pre_run=true")
            persistPreRunAsync(
                appContext = context.applicationContext,
                request = null,
                mode = mode,
                requestId = safeRequestId,
                exportGrant = exportGrant,
                state = state,
                reason = reason,
                stage = stage,
                admissionOutcome = admissionOutcome,
                consent = consent,
            )
        }

        internal fun start(context: Context, run: DebugQaApprovedRun): Boolean {
            if (!BuildConfig.DEBUG || context.packageName != CaptureQaAcceptance.CANDIDATE ||
                !admission.queue(run)) return false
            val request = run.request
            return runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DebugQaRunnerService::class.java)
                    .setAction(request.mode.action).putExtra(REQUEST_ID, request.id))
                true
            }.getOrDefault(false)
        }

        fun cancel(context: Context) {
            val run = admission.active ?: return
            if (context.packageName == CaptureQaAcceptance.CANDIDATE) {
                context.startService(Intent(context, DebugQaRunnerService::class.java)
                    .setAction(ACTION_CANCEL).putExtra(REQUEST_ID, run.request.id))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ClosePaw DEBUG QA", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(request: DebugQaRunRequest, message: String, error: Boolean = false): Notification {
        val viewIntent = PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, LocalCaptureQaActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancelIntent = PendingIntent.getService(this, 14702,
            Intent(this, DebugQaRunnerService::class.java).setAction(ACTION_CANCEL)
                .setData(Uri.parse("closepaw-debug-qa:${request.id}")).putExtra(REQUEST_ID, request.id),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("DEBUG · ${request.mode.label}")
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
        val id = intent?.getStringExtra(REQUEST_ID)
        if (intent?.action == ACTION_CANCEL) {
            if (id == admission.active?.request?.id) job?.cancel()
            if (admission.active == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val (outcome, run) = admission.admit(id, intent?.action)
        if (run == null) {
            val safeId = id?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: "invalid"
            val mode = DebugQaMode.entries.firstOrNull { it.action == intent?.action }
            Log.i("ClosePawDebugQA", "request_id=$safeId mode=${mode?.code} admission=$outcome")
            val pending = admission.current
            if (pending?.id == id && pending != null && outcome == DebugQaAdmissionOutcome.DENIED)
                reject(this, pending, "DENIED", "service_request_mismatch")
            if (!isBusy() || (admission.active == null && outcome == DebugQaAdmissionOutcome.DENIED))
                stopSelfResult(startId)
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, notification(run.request, "WAITING · Android screen share"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (_: Exception) {
            reject(this, run.request, "FAILED", "foreground_service_unavailable")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val batchId = UUID.randomUUID().toString()
        Log.i("ClosePawDebugQA", "request_id=$id mode=${run.request.mode.code} consent=${run.consent} " +
            "action=${intent?.action} admission=$outcome batch_id=$batchId")
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { execute(run, batchId) }
            } finally {
                val status = DebugQaRunnerStatusStore.updates.value
                if (status == null || status.request != run.request || status.state in setOf("WAITING", "RUNNING"))
                    reject(this@DebugQaRunnerService, run.request, "INTERRUPTED", "no_automatic_replay")
                admission.finish(run.request)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun execute(run: DebugQaApprovedRun, batchId: String) = kotlinx.coroutines.coroutineScope {
        val request = run.request
        val results = mutableListOf<JsonObject>()
        val gate = MediaProjectionCaptureService.consentGate
        var terminal = "FAILED"
        var reason = "qa_service_failed"
        val progress = MutableStateFlow(DebugQaStatus(request, "WAITING", "projection_starting", batchId = batchId))
        fun record(value: DebugQaStatus) = DebugQaRunnerStatusStore.publish(applicationContext, value)
        val heartbeat = launch {
            while (isActive) {
                record(progress.value)
                delay(1_000L)
            }
        }
        fun save(state: String, reason: String) =
            DebugOneStopQa.saveBatch(applicationContext, batchId, state, reason, results, run)
        try {
            save("IN_PROGRESS", "projection_starting")
            val projection = withTimeoutOrNull(12_000L) {
                var lease = ProjectionImageTransport.active()?.takeIf { it.screenObservationAllowed() }
                while (gate.generation == run.projectionGeneration && lease == null) {
                    delay(150L)
                    lease = ProjectionImageTransport.active()?.takeIf { it.screenObservationAllowed() }
                }
                lease?.takeIf { gate.generation == run.projectionGeneration }
            }
            if (projection == null) {
                terminal = "BLOCKED"
                reason = "android_observation_approval_unavailable"
            } else {
                progress.value = progress.value.copy(state = "RUNNING", stage = "starting")
                for (index in 0 until request.mode.repetitions) {
                    val result = DebugOneStopQa.run(applicationContext, { update ->
                        val stage = update.substringAfter(": ", "").substringBefore(" errors=", "")
                        if (stage.matches(Regex("[a-z][a-z0-9_]{0,79}"))) {
                            progress.value = progress.value.copy(
                                state = if (stage == "await_chrome") "WAITING" else "RUNNING", stage = stage)
                        }
                        runCatching {
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                                notification(request, "${progress.value.state} · $stage",
                                    error = update.contains("errors=") && !update.endsWith("errors=0")))
                        }
                    }, request, batchId, projection)
                    results += result
                    terminal = DebugQaBatchPolicy.terminalState(request.mode, result)
                    reason = result.getValue("reason").jsonPrimitive.content
                    if (terminal != "PASS" || index == request.mode.repetitions - 1) break
                    save("IN_PROGRESS", "between_repetitions")
                    delay(500L)
                }
            }
        } catch (_: CancellationException) {
            terminal = "CANCELLED"
            reason = "user_or_system_cancelled"
        } catch (_: Exception) {
            terminal = "FAILED"
            reason = "qa_service_failed"
        } finally {
            withContext(NonCancellable) {
                heartbeat.cancelAndJoin()
                if (terminal == "CANCELLED" && DebugOneStopQa.lastResult(applicationContext)?.let {
                        it["batch_id"]?.jsonPrimitive?.content == batchId &&
                            it["outcome"]?.jsonPrimitive?.content == "UNKNOWN"
                    } == true) {
                    terminal = "UNKNOWN"
                    reason = "input_effect_unconfirmed"
                }
                val saved = runCatching { save(terminal, reason) }.isSuccess
                val export = if (saved) DebugQaDownloadExport.complete(applicationContext, run, batchId)
                    else if (request.exportGrant == null) DebugQaExportOutcome.NOT_ENABLED
                    else DebugQaExportOutcome.FAILED
                record(DebugQaStatus(request, terminal, if (saved) reason else "qa_checkpoint_write_failed",
                    export, batchId))
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
