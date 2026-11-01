package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import ai.closepaw.termux.TermuxBridgeAuth
import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Independent authenticated localhost channel. Never submits a shell/job or approves source pins. */
internal class TermuxMaintenanceOperations(
    private val tokenProvider: () -> String?,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "http://127.0.0.1:18424",
    private val bootPackage: () -> String = { "not_observed" },
) {
    constructor(context: Context) : this({ TermuxBridgeAuth.existingToken(context.applicationContext) },
        bootPackage = {
            try {
                val info = context.applicationContext.packageManager.getApplicationInfo("com.termux.boot", 0)
                if (info.enabled) "installed_enabled_activation_unobserved" else "installed_disabled"
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) { "not_installed" }
        })

    fun register(registry: OperationRegistry, enabled: () -> Boolean) {
        for (update in listOf(false, true)) {
            registry.register(OperationDescriptor(
                id = if (update) UPDATE else STATUS,
                description = if (update) "Apply an already locally approved immutable daemon revision independently of jobs. Never approves a pin, pairs, kills active work or replays a command. Query maintenance status after timeout."
                    else "Read independent supervisor state and actual daemon revision. Unreachable means Termux/user action may be needed, never automatic recovery success.",
                readOnly = !update,
                inputSchema = buildJsonObject {
                    put("type", "object"); put("additionalProperties", false)
                    put("properties", buildJsonObject { if (update) put("revision", buildJsonObject {
                        put("type", "string"); put("pattern", "^[a-f0-9]{40}$")
                    }) })
                    put("required", buildJsonArray { if (update) add("revision") })
                },
                permissions = listOf("remote_execution", "termux_local_bridge"),
                effects = if (update) listOf("daemon_source_update", "idle_daemon_restart") else emptyList(),
                readiness = { OperationReadiness(enabled(), if (enabled()) null else "remote_execution_disabled") },
            ), object : OperationHandler {
                override suspend fun invoke(invocation: OperationInvocation): OperationResult = withContext(Dispatchers.IO) {
                    val token = tokenProvider()
                        ?: return@withContext OperationResult(success=false, summary="bridge_unpaired")
                    val builder = Request.Builder().url("$baseUrl/v1/maintenance/" + if (update) "update" else "status")
                        .header("X-ClosePaw-Token", token)
                    if (update) builder.post(invocation.input.toString().toRequestBody("application/json".toMediaType()))
                    try {
                        client.newCall(builder.build()).execute().use { response ->
                            // Bound before decoding; remote arbitrary bodies never become diagnostics.
                            val source = response.body.source()
                            val buffer = okio.Buffer()
                            while (buffer.size <= 8192) {
                                if (source.read(buffer, minOf(1024L, 8193L - buffer.size)) == -1L) break
                            }
                            val bytes = buffer.readByteArray()
                            if (bytes.size > 8192) return@withContext OperationResult(success=false, data=buildJsonObject { put("outcome_unknown", update) }, summary="maintenance_invalid_response")
                            val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                            val safe = buildJsonObject {
                                maintenanceSafeData(json).forEach { (key, value) -> put(key, value) }
                                put("http_status", response.code)
                                put("apk_version", ai.closepaw.BuildConfig.VERSION_NAME)
                                put("apk_version_code", ai.closepaw.BuildConfig.VERSION_CODE)
                                put("termux_boot_package", bootPackage())
                                put("termux_boot_activation", "not_observed")
                                put("android_background_permission", "not_observed")
                            }
                            val state = safe["status"]?.jsonPrimitive?.content
                            val verified = state == "updated" && safe["verification"]?.jsonPrimitive?.content == "jobs_exit0_stdout_match" &&
                                safe["exit_code"]?.jsonPrimitive?.intOrNull == 0 &&
                                safe["stdout"]?.jsonPrimitive?.content == "CLOSEPAW_MAINTENANCE_OK" &&
                                safe["source_sha256"] != null
                            OperationResult(data=safe,
                                success=response.isSuccessful && (!update || verified),
                                summary=if (update && verified) "Daemon update verified by fixed command exit0/stdout."
                                    else "Maintenance observations returned; inspect status and verification.")
                        }
                    } catch (_: IOException) {
                        OperationResult(success=false, data=buildJsonObject { put("outcome_unknown", update) }, summary="maintenance_unreachable_or_timeout: user action may be required; inspect status before retry; Termux force-stop cannot self-recover.")
                    } catch (_: RuntimeException) {
                        OperationResult(success=false, data=buildJsonObject { put("outcome_unknown", update) }, summary="maintenance_invalid_response")
                    }
                }
            })
        }
    }

    companion object {
        const val STATUS = "linux.bridge.maintenance.status"
        const val UPDATE = "linux.bridge.maintenance.update"
    }
}

/** Each diagnostic field has a closed contract; arbitrary response strings are discarded. */
internal fun maintenanceSafeData(json: JsonObject): JsonObject = buildJsonObject {
    val enums = mapOf(
        "status" to setOf("idle", "downloading", "updated", "rolled_back", "retry_exhausted", "recovery_busy",
            "interrupted_update_user_required", "activation_outcome_unknown", "state_corrupt", "unapproved_revision",
            "integrity_failed", "syntax_failed", "download_failed", "active_jobs", "outcome_unknown",
            "job_store_unhealthy", "job_archive_full", "job_archive_conflict", "job_archive_unavailable",
            "job_store_full_no_confirmed_results", "job_queue_full", "daemon_absent", "response_timeout", "bridge_unavailable", "legacy_or_port_conflict", "response_timeout_or_absent", "auth_mismatch",
            "replacement_or_rollback_failed", "execution_verification_failed", "startup_failed", "stop_failed", "revision_mismatch"),
        "error" to setOf("unauthorized", "not_found", "invalid_request"),
        "verification" to setOf("not_run", "not_currently_verified", "recheck_required", "jobs_exit0_stdout_match", "runtime_only_exit0"),
        "last_verification" to setOf("not_run", "not_currently_verified", "recheck_required", "jobs_exit0_stdout_match", "runtime_only_exit0"),
        "supervisor" to setOf("running"),
        "daemon_state" to setOf("drained", "responding", "revision_mismatch", "daemon_absent", "response_timeout", "bridge_unavailable", "auth_mismatch", "legacy_or_port_conflict"),
        "stdout" to setOf("CLOSEPAW_MAINTENANCE_OK"),
    )
    for ((key, value) in json) {
        val primitive = value as? JsonPrimitive ?: continue
        val valid = when (key) {
            in enums -> primitive.isString && primitive.content in enums.getValue(key)
            "revision" -> primitive.isString && primitive.content.matches(Regex("[a-f0-9]{40}"))
            "source_sha256", "daemon_source_sha256" -> primitive.isString && primitive.content.matches(Regex("[a-f0-9]{64}"))
            "runtime_revision" -> primitive.isString && primitive.content.matches(Regex("[0-9]{1,8}\\.[0-9]{1,8}"))
            "attempts" -> !primitive.isString && (primitive.intOrNull ?: -1) in 0..3
            "exit_code" -> !primitive.isString && primitive.intOrNull == 0
            "boot_hook_present" -> !primitive.isString && primitive.booleanOrNull != null
            "automatic_after_termux_force_stop" -> !primitive.isString && primitive.booleanOrNull == false
            else -> false
        }
        if (valid) put(key, primitive)
    }
}
