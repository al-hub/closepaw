package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.control.*
import ai.closepaw.platform.virtualdisplay.ProjectionAcquisitionTuning
import ai.closepaw.update.AndroidSelfUpdater
import ai.closepaw.update.SelfUpdateOperations
import ai.closepaw.app.AgentService
import ai.closepaw.bridge.*
import ai.closepaw.chatgpt.operations.*
import ai.closepaw.termux.TermuxBridgeAuth
import ai.closepaw.termux.TermuxBridgeManager
import ai.closepaw.tailscale.*
import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Composition only: registry, policies and transports remain independently testable. */
internal class AndroidOperationRuntime(
    context: Context,
    scope: CoroutineScope,
    private val readApp: ReadAppTool,
    private val settings: McpExecutionSettings,
) : McpOperationTools {
    private val client = TermuxJobClient(TermuxBridgeAuth.token(context))
    private val termuxBridge = TermuxBridgeManager.get(context)
    private val shellReadiness = TermuxJobReadinessGate(client::probe, {
        when (val status = termuxBridge.ensureReadyForSession(8_000)) {
            ai.closepaw.termux.TermuxBridgeStatus.Ready -> null
            ai.closepaw.termux.TermuxBridgeStatus.NotInstalled -> "termux_not_installed"
            is ai.closepaw.termux.TermuxBridgeStatus.NeedsSetup -> status.reason.name.lowercase(java.util.Locale.ROOT)
            else -> "termux_recovery_unavailable"
        }
    }, android.os.SystemClock::elapsedRealtime)
    private val shell = TermuxShellOperation(client, TermuxJobLinkStore(File(context.filesDir, "mcp/job-links.tsv")))
    private val store = OperationTaskStore(File(context.filesDir, "mcp/tasks.json"))
    private val registry = OperationRegistry()
    private val screenMutex = ControlScreenLease.mutex
    private val draftDiagnostics = GeminiDraftDiagnostics(File(context.filesDir, "mcp/gemini-draft-diagnostics.json"))
    private val roundTripDiagnostics = GeminiDraftDiagnostics(File(context.filesDir, "mcp/gemini-roundtrip-diagnostics.json"))
    private val tailscaleDiagnostics = TailscaleDiagnostics(File(context.filesDir, "mcp/tailscale-recovery.json"))
    private val dispatcher: OperationDispatcher

    /** The same serialized UI lease drives indication for registered reads and dedicated Gemini paths. */
    private suspend fun <T> Mutex.withRemoteActivity(sessionId: String? = null, block: suspend () -> T): T {
        val admitted = AgentService.instance?.remoteControlSession
        val epoch = admitted?.epoch
        return withLock {
            val service = AgentService.instance ?: error("accessibility_permission_missing")
            val session = service.remoteControlSession
            check(session === admitted && session.epoch == epoch) { "control_session_changed" }
            session.refusal(sessionId)?.let { error(it) }
            session.activateLegacy()
            val job = currentCoroutineContext()[Job]
            session.interrupt = { job?.cancel(CancellationException("user_control_interrupt")) }
            try {
                withContext(Dispatchers.Main) { service.showRemoteControlActivity() }
                block()
            } finally {
                session.interrupt = {}
                // Keep the executor's truthful final stage; cleanup must not replace failure
                // or cancelled observation with a generic execution label.
                withContext(NonCancellable + Dispatchers.Main) { service.hideRemoteControlActivity() }
            }
        }
    }
    init {
        scope.launch(Dispatchers.IO) { ProjectionAcquisitionTuning.initialize(context) }
        val genericPort = AndroidControlPort(context, mutationBlock = { pkg ->
            if (pkg in setOf(AndroidGeminiDraftUi.PACKAGE, AndroidGeminiDraftUi.GOOGLE_HOST) &&
                store.latestOutcomeUnknown("gemini.question.ask")) "dedicated_submission_unresolved" else null
        }, runtime = settings.runtimeControl) { settings.enabled && settings.genericControlEnabled }
        val genericControl = GenericControl(genericPort, { settings.enabled && settings.genericControlEnabled })
        fun bindResume() {
            AgentService.instance?.remoteResumeCheck = {
                genericControl.invalidate()
                screenMutex.withLock { genericPort.capture().let { it.reason == null && !it.truncated } }
            }
        }
        bindResume()
        GenericControlOperations(genericControl, genericPort, screenMutex, settings.runtimeControl) {
            when {
                !settings.genericControlEnabled -> OperationReadiness(false, "generic_control_disabled")
                AgentService.instance == null -> OperationReadiness(false, "accessibility_permission_missing")
                else -> OperationReadiness()
            }
        }.register(registry)
        registry.register(OperationDescriptor(
            id = "closepaw.projection.diagnostics",
            description = "Read bounded projection acquisition metadata only; no pixels, screen text or projection tokens.",
            inputSchema = buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}); put("additionalProperties", false) },
            permissions = listOf("generic_android_control"),
            readiness = { OperationReadiness(settings.genericControlEnabled && AgentService.instance != null,
                if (!settings.genericControlEnabled) "generic_control_disabled" else if (AgentService.instance == null) "accessibility_permission_missing" else null) },
        ), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation) = OperationResult(
                data = ControlDiagnostics.projectionSnapshot(), summary = "Projection acquisition metadata returned.")
        })
        val tuningReady = {
            when {
                !settings.genericControlEnabled -> OperationReadiness(false, "generic_control_disabled")
                !ProjectionAcquisitionTuning.initialized() -> OperationReadiness(false, "tuning_initializing")
                else -> OperationReadiness()
            }
        }
        registry.register(ProjectionTuningProtocol.get(tuningReady), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult {
                val v = ProjectionAcquisitionTuning.snapshot()
                return OperationResult(data = buildJsonObject {
                    put("revision", v.revision); put("timeout_ms", v.timeoutMs); put("poll_ms", v.pollMs)
                    put("storage_state", ProjectionAcquisitionTuning.storageState())
                    put("timeout_min_ms", 1500); put("timeout_max_ms", 3000)
                    put("poll_min_ms", 25); put("poll_max_ms", 100)
                }, summary = "Current bounded projection polling settings.")
            }
        })
        registry.register(ProjectionTuningProtocol.set(tuningReady), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult {
                val input = invocation.input
                val authorized = input["authorized"]?.jsonPrimitive?.booleanOrNull == true
                val revision = input["expected_revision"]?.jsonPrimitive?.intOrNull
                val timeout = input["timeout_ms"]?.jsonPrimitive?.longOrNull
                val poll = input["poll_ms"]?.jsonPrimitive?.longOrNull
                val session = AgentService.instance?.remoteControlSession
                val admittedEpoch = invocation.admittedEpoch ?: -1L
                val outcome = if (revision != null && timeout != null && poll != null)
                    withContext(Dispatchers.IO) {
                        ProjectionAcquisitionTuning.update(AgentService.instance?.remoteControlSession?.takeIf { it === session },
                            input["session_id"]?.jsonPrimitive?.content, authorized, revision, timeout, poll, admittedEpoch)
                    } else ai.closepaw.platform.virtualdisplay.ProjectionTuningOutcome.REFUSED
                val changed = outcome == ai.closepaw.platform.virtualdisplay.ProjectionTuningOutcome.APPLIED
                val unknown = outcome == ai.closepaw.platform.virtualdisplay.ProjectionTuningOutcome.STORAGE_UNKNOWN
                val v = ProjectionAcquisitionTuning.snapshot()
                return OperationResult(success = changed, data = buildJsonObject {
                    put("applied", changed); put("revision", v.revision)
                    put("outcome_unknown", unknown); put("storage_state", ProjectionAcquisitionTuning.storageState())
                    put("timeout_ms", v.timeoutMs); put("poll_ms", v.pollMs)
                }, summary = if (changed) "Bounded polling configuration committed." else if (unknown)
                    "Storage outcome unknown. Restart app and read durable revision before any retry." else
                    "Configuration refused: initialization, authorization, revision or range invalid.")
            }
        })
        SelfUpdateOperations(AndroidSelfUpdater.get(context)).register(registry)
        TailscaleOperations(
            TailscaleRecovery(AndroidTailscalePlatform(context), { settings.tailscaleRecoveryEnabled }, record = tailscaleDiagnostics::record),
            { settings.tailscaleRecoveryEnabled },
        ).register(registry)
        // One bounded maintenance attempt per app runtime. Healthy paired Play Termux can
        // install its supervisor without RUN_COMMAND or another manual pairing ceremony.
        scope.launch {
            if (settings.enabled) termuxBridge.ensureReadyForSession(8_000)
        }
        registry.register(OperationDescriptor(
            id = "app.screen.read", description = "Read one visible screen from a registered app. May bring that app to foreground. Discover targets with app.targets.list. Does not type or send.",
            inputSchema = schema("app" to stringSchema(128), required = listOf("app")),
            permissions = listOf("accessibility"), effects = listOf("app_launch"),
            readiness = { OperationReadiness(AgentService.instance != null, if(AgentService.instance == null) "accessibility_permission_missing" else null) },
        ), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult = screenMutex.withRemoteActivity(invocation.input["session_id"]?.jsonPrimitive?.content) {
                genericControl.invalidate()
                val captured = withContext(Dispatchers.IO) { readApp.read(invocation.input.getValue("app").jsonPrimitive.content) }
                OperationResult(data = captured.structured,
                    success = captured.structured["status"]?.jsonPrimitive?.content != "failed",
                    summary = captured.structured["summary"]?.jsonPrimitive?.content ?: "Visible screen captured.")
            }
        })
        registry.register(OperationDescriptor(
            id="app.targets.list", description="List configured screen-read apps and current installation/permission readiness.", inputSchema=schema(),
        ), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation) = OperationResult(data = buildJsonObject {
                put("apps", buildJsonArray { BrowserReadTarget.supported.forEach { target ->
                    val installed = runCatching { context.packageManager.getApplicationInfo(target.packageName, 0) }.isSuccess
                    add(buildJsonObject {
                        put("app", target.id); put("package_name", target.packageName); put("installed", installed)
                        put("ready", installed && AgentService.instance != null)
                        put("reason", if(!installed) "app_not_installed" else if(AgentService.instance == null) "accessibility_permission_missing" else "ready")
                    })
                } })
            }, summary="Read app targets and readiness returned.")
        })
        registry.register(TermuxBridgeStatusOperation.descriptor { settings.enabled },
            TermuxBridgeStatusOperation(termuxBridge::diagnosticSnapshot))
        registry.register(SelfMonitoringOperation.descriptor { settings.genericControlEnabled }, SelfMonitoringOperation(
            local = {
                val service = AgentService.instance
                val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
                val power = context.getSystemService(android.os.PowerManager::class.java)
                SelfMonitoringOperation.LocalState(
                    accessibilityConnected = service != null,
                    sessionState = service?.remoteControlSession?.state?.name?.lowercase(java.util.Locale.ROOT),
                    deviceLocked = keyguard.isKeyguardLocked || !power.isInteractive,
                    controlEnabled = settings.genericControlEnabled,
                    remoteExecutionEnabled = settings.enabled,
                    projectionLeaseActive = ai.closepaw.platform.virtualdisplay.ProjectionImageTransport.active() != null,
                )
            },
        ))
        registry.register(OperationDescriptor(
            id="linux.shell.exec", description="Execute a noninteractive shell command in paired Termux. Returns a task ID for output, errors and process exit code. No interactive terminal input is supported. For private-network/SSH failures, discover network.tailscale.status and network.tailscale.ensure_connected. Do not automatically replay writes or unknown outcomes.",
            readOnly=false, permissions=listOf("remote_execution", "termux_local_bridge"), effects=listOf("shell_execution"), cancellationSupported=true,
            inputSchema=schema("command" to stringSchema(32768, 1), "cwd" to stringSchema(4096, 1), "timeout_ms" to buildJsonObject { put("type", "integer"); put("minimum", 1000); put("maximum", 3600000) }, required=listOf("command")),
            readiness={
                if(!settings.enabled) OperationReadiness(false,"remote_execution_disabled")
                else runBlocking {
                    val readiness = shellReadiness.check()
                    OperationReadiness(readiness.ready, readiness.reason)
                }
            },
        ), shell)
        registry.register(GeminiDraftOperation.descriptor(
            readiness = {
                when {
                    !settings.geminiDraftEnabled -> OperationReadiness(false, "gemini_draft_disabled")
                    AgentService.instance == null -> OperationReadiness(false, "accessibility_permission_missing")
                    runCatching { context.packageManager.getApplicationInfo(AndroidGeminiDraftUi.PACKAGE, 0) }.isFailure -> OperationReadiness(false, "app_not_installed")
                    else -> OperationReadiness()
                }
            },
        ), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult = screenMutex.withRemoteActivity(invocation.input["session_id"]?.jsonPrimitive?.content) {
                genericControl.invalidate()
                val service = AgentService.instance
                if (service == null) OperationResult(success = false, summary = "Accessibility service is not connected.")
                else withTimeout(10_000) { GeminiDraftOperation(AndroidGeminiDraftUi(service) { settings.geminiDraftEnabled && service.remoteControlSession.refusal(service.remoteControlSession.id, true) == null }, { settings.geminiDraftEnabled && service.remoteControlSession.refusal(service.remoteControlSession.id, true) == null }, draftDiagnostics::record).invoke(invocation) }
            }
        })
        val activeQuestions = ConcurrentHashMap<String, GeminiRoundTripOperation>()
        val stoppedQuestions = ConcurrentHashMap.newKeySet<String>()
        registry.register(GeminiRoundTripOperation.descriptor(readiness = {
            when {
                !settings.geminiQuestionEnabled -> OperationReadiness(false, "gemini_question_disabled")
                AgentService.instance == null -> OperationReadiness(false, "accessibility_permission_missing")
                runCatching { context.packageManager.getApplicationInfo(AndroidGeminiDraftUi.PACKAGE, 0) }.isFailure -> OperationReadiness(false, "app_not_installed")
                else -> OperationReadiness()
            }
        }), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult {
                try {
                    return screenMutex.withRemoteActivity(invocation.input["session_id"]?.jsonPrimitive?.content) {
                        genericControl.invalidate()
                        if (stoppedQuestions.contains(invocation.taskId)) return@withRemoteActivity OperationResult(
                            success = false, summary = "Observation stopped before the question started. No send action was invoked.",
                            data = buildJsonObject { put("state", "interrupted"); put("sent", false); put("send_action_invoked", false); put("generation_cancelled", false) },
                        )
                        val service = AgentService.instance ?: return@withRemoteActivity OperationResult(success = false, summary = "Accessibility service is not connected.")
                        val executionEpoch = service.remoteControlSession.epoch
                        val allowed = { settings.geminiQuestionEnabled && AgentService.instance === service && !stoppedQuestions.contains(invocation.taskId) && service.remoteControlSession.epoch == executionEpoch && service.remoteControlSession.refusal(service.remoteControlSession.id, true) == null }
                        val ui = AndroidGeminiRoundTripUi(service, allowed)
                        val started = android.os.SystemClock.elapsedRealtime()
                        val operation = GeminiRoundTripOperation(ui, allowed, { taskId, stage, reason ->
                            // This metadata-only ledger boundary must succeed before a send is possible.
                            check(store.updateActive(taskId, mapOf(
                                "submission_state" to JsonPrimitive(stage), "reason_code" to JsonPrimitive(reason),
                                "observed_at_ms" to JsonPrimitive(System.currentTimeMillis()),
                                "ui_metadata" to ui.metadataSnapshot(),
                            ))) { "Task is no longer active" }
                            // Logging is best-effort and cannot turn a durable submission into a retry.
                            runCatching { roundTripDiagnostics.record(DraftDiagnostic(taskId, stage, reason,
                                (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0), ui.evidence())) }
                        }, progress = { stage ->
                            // UI feedback is best effort and cannot alter the durable send boundary.
                            runCatching { service.remoteControlSession.geminiProgress(stage, executionEpoch) }
                        })
                        activeQuestions[invocation.taskId] = operation
                        try { operation.invoke(invocation) } finally { activeQuestions.remove(invocation.taskId, operation) }
                    }
                } finally { stoppedQuestions.remove(invocation.taskId) }
            }
            override suspend fun cancel(taskId: String): Boolean {
                stoppedQuestions.add(taskId)
                activeQuestions[taskId]?.cancel(taskId)
                // Only observation stops. Never claim Gemini generation was cancelled.
                return false
            }
        })
        registry.register(GeminiUiInspectionOperation.descriptor(readiness = {
            OperationReadiness(AgentService.instance != null,
                if (AgentService.instance == null) "accessibility_permission_missing" else null)
        }), GeminiUiInspectionOperation { sessionId ->
            val refusal = GeminiUiInspectionOperation.sessionRefusal(AgentService.instance?.remoteControlSession, sessionId)
            if (refusal != null) buildJsonObject { put("foreground", false); put("reason_code", refusal) }
            else screenMutex.withRemoteActivity(sessionId) {
                genericControl.invalidate()
                val service = AgentService.instance
                if (service == null) buildJsonObject { put("foreground", false); put("reason_code", "accessibility_permission_missing") }
                else {
                    val ui = AndroidGeminiRoundTripUi(service, { AgentService.instance === service })
                    ui.snapshot()
                    ui.metadataSnapshot()
                }
            }
        })
        dispatcher = OperationDispatcher(registry, store, scope,
            operationEpoch = { id -> if (ProjectionTuningProtocol.requiresSessionEpoch(id) || id in setOf("android.ui.action", "android.ui.sequence", "android.control.island.drag", "android.control.feedback_test",
                    "app.screen.read", "gemini.draft.insert", "gemini.question.ask"))
                AgentService.instance?.remoteControlSession?.epoch ?: -1L else 0L },
            executeAllowed = { settings.enabled || settings.geminiDraftEnabled || settings.geminiQuestionEnabled || settings.tailscaleRecoveryEnabled || settings.selfUpdateEnabled || settings.genericControlEnabled },
            operationAllowed = { id -> when(id) {
                GenericControlOperations.ACTION, GenericControlOperations.SEQUENCE, GenericControlOperations.ISLAND_DRAG, GenericControlOperations.CONFIGURE, GenericControlOperations.FEEDBACK_TEST,
                "android.control.session.start", "android.control.session.wait", "android.control.session.end" -> settings.genericControlEnabled
                "android.control.session.status", "closepaw.self.status", "closepaw.projection.diagnostics",
                "closepaw.projection.tuning.get", "closepaw.projection.tuning.set" -> settings.genericControlEnabled
                SelfUpdateOperations.INSTALL -> settings.selfUpdateEnabled
                "linux.shell.exec" -> settings.enabled
                "gemini.draft.insert" -> settings.geminiDraftEnabled
                "gemini.question.ask" -> settings.geminiQuestionEnabled
                TailscaleOperations.ENSURE -> settings.tailscaleRecoveryEnabled
                else -> false
            } },
        )
    }
    fun diagnosticSnapshot(): JsonObject = JsonObject(draftDiagnostics.snapshot() + ("roundtrip" to roundTripDiagnostics.snapshot()) + ("projection" to ControlDiagnostics.projectionSnapshot()))
    fun tailscaleDiagnosticSnapshot(): JsonObject = tailscaleDiagnostics.snapshot()

    override fun call(name: String, arguments: JsonObject): JsonObject = when(name) {
        "discover_operations" -> dispatcher.discover(arguments)
        "query" -> dispatcher.query(arguments)
        "execute" -> dispatcher.execute(arguments)
        "cancel_task" -> dispatcher.cancelTask(arguments)
        "get_task" -> {
            val taskId = arguments["task_id"]?.jsonPrimitive?.content.orEmpty()
            val task = store.get(taskId)
            if(task?.get("operation_id")?.jsonPrimitive?.content == "linux.shell.exec") {
                val snapshot = runBlocking { shell.snapshot(taskId) }
                if(snapshot != null) {
                    val result = shell.result(snapshot)
                    store.updateActive(taskId, mapOf(
                        "state" to JsonPrimitive(if(snapshot.outcomeUnknown) "outcome_unknown" else snapshot.state),
                        "summary" to JsonPrimitive(result.summary),
                        "result" to buildJsonObject {
                            put("status", if(result.success) "succeeded" else if(snapshot.terminal) "failed" else "running")
                            put("data", result.data); put("summary", result.summary); put("stdout", result.stdout); put("stderr", result.stderr)
                            snapshot.exitCode?.let { put("exit_code", it) }
                        },
                    ))
                }
            }
            runBlocking { GeminiTaskAwaiter(dispatcher::getTask).get(arguments) }
        }
        else -> buildJsonObject { put("status", "failed"); put("error_code", "unknown_tool") }
    }
    private fun stringSchema(max: Int, min: Int=0) = buildJsonObject { put("type", "string"); put("minLength", min); put("maxLength", max) }
    private fun schema(vararg fields: Pair<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object"); put("properties", JsonObject(fields.toMap() + ("session_id" to stringSchema(128, 1)))); put("additionalProperties", false)
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}
