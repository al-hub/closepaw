package ai.closepaw.chatgpt

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
    private val recoveryMutex = Mutex()
    private var lastRecoveryAttemptMs = 0L
    private val shell = TermuxShellOperation(client, TermuxJobLinkStore(File(context.filesDir, "mcp/job-links.tsv")))
    private val store = OperationTaskStore(File(context.filesDir, "mcp/tasks.json"))
    private val registry = OperationRegistry()
    private val screenMutex = Mutex()
    private val draftDiagnostics = GeminiDraftDiagnostics(File(context.filesDir, "mcp/gemini-draft-diagnostics.json"))
    private val roundTripDiagnostics = GeminiDraftDiagnostics(File(context.filesDir, "mcp/gemini-roundtrip-diagnostics.json"))
    private val tailscaleDiagnostics = TailscaleDiagnostics(File(context.filesDir, "mcp/tailscale-recovery.json"))
    private val dispatcher: OperationDispatcher
    init {
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
            override suspend fun invoke(invocation: OperationInvocation): OperationResult = screenMutex.withLock {
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
        registry.register(OperationDescriptor(
            id="linux.shell.exec", description="Execute a noninteractive shell command in paired Termux. Returns a task ID for output, errors and process exit code. No interactive terminal input is supported. For private-network/SSH failures, discover network.tailscale.status and network.tailscale.ensure_connected. Do not automatically replay writes or unknown outcomes.",
            readOnly=false, permissions=listOf("remote_execution", "termux_local_bridge"), effects=listOf("shell_execution"), cancellationSupported=true,
            inputSchema=schema("command" to stringSchema(32768, 1), "cwd" to stringSchema(4096, 1), "timeout_ms" to buildJsonObject { put("type", "integer"); put("minimum", 1000); put("maximum", 3600000) }, required=listOf("command")),
            readiness={
                if(!settings.enabled) OperationReadiness(false,"remote_execution_disabled")
                else runBlocking {
                    var readiness = client.probe()
                    if (!readiness.ready && readiness.reason?.startsWith("bridge_daemon_unreachable:") == true) {
                        recoveryMutex.withLock {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastRecoveryAttemptMs >= 30_000 || lastRecoveryAttemptMs == 0L) {
                                lastRecoveryAttemptMs = now
                                termuxBridge.ensureReadyForSession(8_000)
                                readiness = client.probe()
                            }
                        }
                    }
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
            override suspend fun invoke(invocation: OperationInvocation): OperationResult = screenMutex.withLock {
                val service = AgentService.instance
                if (service == null) OperationResult(success = false, summary = "Accessibility service is not connected.")
                else withTimeout(10_000) { GeminiDraftOperation(AndroidGeminiDraftUi(service) { settings.geminiDraftEnabled }, { settings.geminiDraftEnabled }, draftDiagnostics::record).invoke(invocation) }
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
                    return screenMutex.withLock {
                        if (stoppedQuestions.contains(invocation.taskId)) return@withLock OperationResult(
                            success = false, summary = "Observation stopped before the question started. No send action was invoked.",
                            data = buildJsonObject { put("state", "interrupted"); put("sent", false); put("send_action_invoked", false); put("generation_cancelled", false) },
                        )
                        val service = AgentService.instance ?: return@withLock OperationResult(success = false, summary = "Accessibility service is not connected.")
                        val allowed = { settings.geminiQuestionEnabled && AgentService.instance === service && !stoppedQuestions.contains(invocation.taskId) }
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
        }), GeminiUiInspectionOperation {
            screenMutex.withLock {
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
            executeAllowed = { settings.enabled || settings.geminiDraftEnabled || settings.geminiQuestionEnabled || settings.tailscaleRecoveryEnabled || settings.selfUpdateEnabled },
            operationAllowed = { id -> when(id) {
                SelfUpdateOperations.INSTALL -> settings.selfUpdateEnabled
                "linux.shell.exec" -> settings.enabled
                "gemini.draft.insert" -> settings.geminiDraftEnabled
                "gemini.question.ask" -> settings.geminiQuestionEnabled
                TailscaleOperations.ENSURE -> settings.tailscaleRecoveryEnabled
                else -> false
            } },
        )
    }
    fun diagnosticSnapshot(): JsonObject = JsonObject(draftDiagnostics.snapshot() + ("roundtrip" to roundTripDiagnostics.snapshot()))
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
        put("type", "object"); put("properties", JsonObject(fields.toMap())); put("additionalProperties", false)
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}
