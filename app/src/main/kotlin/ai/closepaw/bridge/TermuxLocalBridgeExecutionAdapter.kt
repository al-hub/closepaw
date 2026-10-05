package ai.closepaw.bridge

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class TermuxLocalBridgeExecutionAdapter(
    private val authToken: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient(),
) : ExecutionAdapter {
    override val id = "termux-local-bridge"
    override val capabilities = setOf(ExecutionCapability.LINUX_SHELL)

    override suspend fun probe(): AdapterAvailability = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/v1/health").get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext AdapterAvailability.Unavailable("health check failed")
                val json = JSONObject(response.body.string())
                if (json.optString("identity") == BRIDGE_IDENTITY) AdapterAvailability.Available
                else AdapterAvailability.Unavailable("unexpected localhost service identity")
            }
        } catch (_: IOException) {
            AdapterAvailability.NeedsSetup("local bridge is not running or paired")
        }
    }

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        require(request.capability == ExecutionCapability.LINUX_SHELL)
        if (authToken.length < 32) {
            return ExecutionResult(id, null, "", "Local bridge is not paired")
        }
        val payload = JSONObject().apply {
            put("command", request.command)
            request.workingDirectory?.let { put("cwd", it) }
            put("timeout_ms", request.timeoutMs)
        }
        val http = client.newBuilder()
            .callTimeout(request.timeoutMs + HTTP_GRACE_MS, TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMs + HTTP_GRACE_MS, TimeUnit.MILLISECONDS)
            .build()
        val call = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/exec")
            .header(AUTH_HEADER, authToken)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(call).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ExecutionResult(id, response.code, "", "Local bridge HTTP ${response.code}")
                    }
                    val json = JSONObject(response.body.string())
                    val timedOut = json.optBoolean("timed_out", false)
                    ExecutionResult(
                        adapterId = id,
                        exitCode = if (timedOut || json.isNull("exit_code")) null else json.optInt("exit_code"),
                        stdout = json.optString("stdout", ""),
                        stderr = json.optString("stderr", ""),
                        timedOut = timedOut,
                    )
                }
            } catch (e: IOException) {
                ExecutionResult(id, null, "", e.message ?: "Local bridge unavailable")
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:18422"
        private const val BRIDGE_IDENTITY = "closepaw-bridge"
        private const val AUTH_HEADER = "X-ClosePaw-Token"
        private const val HTTP_GRACE_MS = 5_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
