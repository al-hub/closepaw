package ai.closepaw.termux

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

internal interface TermuxCommandRunner {
    suspend fun runShell(
        command: String,
        stdinBase64: String? = null,
        timeoutMs: Long
    ): RunCommandResult
}

internal fun interface TermuxHealthProbe {
    suspend fun fetch(): HealthProbe

    suspend fun installRecovery(bridgePayload: String, bootPayload: String): Boolean = true
}

internal fun interface TermuxInstallProbe {
    fun inspect(): TermuxInstallState
}

internal enum class HealthProbe {
    Ready,
    BridgeOutdated,
    InvalidIdentity,
    AuthMismatch,
    UnverifiedListener,
    ResponseTimeout,
    Unavailable
}

internal enum class TermuxInstallState {
    NotInstalled,
    RunCommandUnavailable,
    Available
}

internal class AndroidTermuxCommandRunner(context: Context) : TermuxCommandRunner {
    private val adapter = TermuxRunCommandAdapter(context)

    override suspend fun runShell(
        command: String,
        stdinBase64: String?,
        timeoutMs: Long
    ): RunCommandResult = adapter.runShell(command, stdinBase64, timeoutMs)
}

internal class AndroidTermuxInstallProbe(
    private val packageManager: PackageManager
) : TermuxInstallProbe {

    @Suppress("DEPRECATION")
    override fun inspect(): TermuxInstallState {
        val packageInfo =
            try {
                packageManager.getPackageInfo(TERMUX_PACKAGE, PackageManager.GET_PERMISSIONS)
            } catch (_: PackageManager.NameNotFoundException) {
                return TermuxInstallState.NotInstalled
            }

        val declaresRunCommandPermission =
            packageInfo.permissions.orEmpty().any { it.name == RUN_COMMAND_PERMISSION }
        val resolvesRunCommandAction =
            packageManager.queryIntentServices(Intent(ACTION_RUN_COMMAND), 0)
                .any { it.isTermuxRunCommandService() }
        val explicitRunCommandService =
            try {
                packageManager.getServiceInfo(
                    ComponentName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE),
                    0
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        val exposesExplicitRunCommandService =
            explicitRunCommandService?.let { service ->
                service.packageName == TERMUX_PACKAGE &&
                    service.name == TERMUX_RUN_COMMAND_SERVICE &&
                    service.exported &&
                    service.permission == RUN_COMMAND_PERMISSION
            } == true

        return if (
            declaresRunCommandPermission &&
            resolvesRunCommandAction &&
            exposesExplicitRunCommandService
        ) {
            TermuxInstallState.Available
        } else {
            TermuxInstallState.RunCommandUnavailable
        }
    }

    private fun ResolveInfo.isTermuxRunCommandService(): Boolean {
        val serviceInfo = serviceInfo ?: return false
        return serviceInfo.packageName == TERMUX_PACKAGE && serviceInfo.name == TERMUX_RUN_COMMAND_SERVICE
    }

    private companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    }
}

internal class HttpTermuxHealthProbe(
    private val healthUrl: String,
    private val expectedIdentity: String,
    private val expectedVersion: String,
    private val authToken: String,
) : TermuxHealthProbe {
    private val httpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()

    /** Upgrade an already paired bridge through its authenticated transport, including Play Termux.
     * Never read, rotate or overwrite the existing token file. No user job is resubmitted.
     */
    override suspend fun installRecovery(bridgePayload: String, bootPayload: String): Boolean =
        withContext(Dispatchers.IO) {
            require(listOf(bridgePayload, bootPayload).all { it.matches(Regex("[A-Za-z0-9+/=]+")) })
            val command = TermuxManualBootstrap.recoveryInstallCommand(bridgePayload, bootPayload)
            val payload = JSONObject().put("command", command).put("timeout_ms", 5000)
                .put("env", JSONObject().put("CLOSEPAW_BRIDGE_TOKEN", authToken))
            val request = Request.Builder()
                .url(healthUrl.removeSuffix("/v1/health") + "/v1/exec")
                .header(AUTH_HEADER, authToken)
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) false else {
                        val result = JSONObject(response.body.string())
                        result.optInt("exit_code", -1) == 0 &&
                            result.optString("stdout").contains("CLOSEPAW_RECOVERY=installed")
                    }
                }
            } catch (_: IOException) { false }
            catch (_: JSONException) { false }
        }

    override suspend fun fetch(): HealthProbe =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(healthUrl).get().build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext HealthProbe.UnverifiedListener
                    val json = JSONObject(response.body.string())
                    val identity = json.optString("identity")
                    val version = json.optString("version")

                    when {
                        identity != expectedIdentity -> HealthProbe.InvalidIdentity
                        version != expectedVersion -> HealthProbe.BridgeOutdated
                        else -> authenticatedProbe()
                    }
                }
            } catch (_: java.io.InterruptedIOException) {
                HealthProbe.ResponseTimeout
            } catch (_: IOException) {
                HealthProbe.Unavailable
            } catch (_: JSONException) {
                HealthProbe.UnverifiedListener
            } catch (_: IllegalStateException) {
                HealthProbe.UnverifiedListener
            }
        }
    private fun authenticatedProbe(): HealthProbe {
        val execUrl = healthUrl.removeSuffix("/v1/health") + "/v1/jobs"
        val request = Request.Builder()
            .url(execUrl)
            .header(AUTH_HEADER, authToken)
            .get()
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> HealthProbe.AuthMismatch
                    response.isSuccessful -> {
                        val json = JSONObject(response.body.string())
                        if (json.optString("status") == "ready" && json.optInt("job_api_version") == 1)
                            HealthProbe.Ready else HealthProbe.BridgeOutdated
                    }
                    else -> HealthProbe.UnverifiedListener
                }
            }
        } catch (_: java.io.InterruptedIOException) {
            HealthProbe.ResponseTimeout
        } catch (_: IOException) {
            HealthProbe.Unavailable
        } catch (_: JSONException) {
            HealthProbe.UnverifiedListener
        }
    }

    private companion object {
        const val AUTH_HEADER = "X-ClosePaw-Token"
    }

}
