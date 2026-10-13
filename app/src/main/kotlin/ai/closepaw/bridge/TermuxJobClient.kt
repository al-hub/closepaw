package ai.closepaw.bridge

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer
import org.json.JSONObject

/** Authenticated durable job transport. It does not use the synchronous RUN_COMMAND fallback. */
class TermuxJobClient(
    private val authToken: String,
    private val baseUrl: String = TermuxLocalBridgeExecutionAdapter.DEFAULT_BASE_URL,
    client: OkHttpClient = OkHttpClient(),
) {
    private val http = client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Checks authentication and asynchronous API support without starting any process. */
    suspend fun probe(): TermuxJobReadiness = withContext(Dispatchers.IO) {
        if (authToken.length < 32) return@withContext TermuxJobReadiness(false, "Local bridge is not paired")
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/v1/jobs")
            .header("X-ClosePaw-Token", authToken).get().build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> TermuxJobReadiness(false, "Local bridge pairing token does not match")
                    !response.isSuccessful -> TermuxJobReadiness(false, "Job readiness HTTP ${response.code}")
                    else -> {
                        val json = JSONObject(readBounded(response.body))
                        if (json.optInt("job_api_version", 0) == 1 && json.optString("status") == "ready") {
                            TermuxJobReadiness(true)
                        } else TermuxJobReadiness(false, "Local bridge durable job API is unavailable or incompatible")
                    }
                }
            }
        } catch (_: IOException) {
            TermuxJobReadiness(false, "Local bridge connection failed")
        } catch (_: org.json.JSONException) {
            TermuxJobReadiness(false, "Invalid bridge readiness response")
        } catch (_: RuntimeException) {
            TermuxJobReadiness(false, "Invalid bridge readiness response")
        }
    }

    suspend fun start(requestId: String, request: ExecutionRequest): TermuxJobReply {
        require(requestId.isNotBlank())
        require(request.capability == ExecutionCapability.LINUX_SHELL)
        val payload = JSONObject().apply {
            put("request_id", requestId)
            put("command", request.command)
            put("timeout_ms", request.timeoutMs)
            request.workingDirectory?.let { put("cwd", it) }
            if (request.environment.isNotEmpty()) put("env", JSONObject(request.environment))
        }
        return send("/v1/jobs", payload)
    }

    suspend fun poll(
        jobId: String,
        stdoutOffset: Long = 0,
        stderrOffset: Long = 0,
        limit: Int = 4096,
    ): TermuxJobReply {
        validateJobId(jobId)
        require(stdoutOffset >= 0 && stderrOffset >= 0)
        require(limit in 1..65536)
        return send("/v1/jobs/$jobId?stdout_offset=$stdoutOffset&stderr_offset=$stderrOffset&limit=$limit")
    }

    suspend fun cancel(jobId: String): TermuxJobReply {
        validateJobId(jobId)
        return send("/v1/jobs/$jobId/cancel", JSONObject())
    }

    private fun validateJobId(jobId: String) {
        require(jobId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid bridge job ID" }
    }

    private suspend fun send(path: String, payload: JSONObject? = null): TermuxJobReply =
        withContext(Dispatchers.IO) {
            if (authToken.length < 32) {
                return@withContext TermuxJobReply.Failure("bridge_unpaired", "Local bridge is not paired")
            }
            val builder = Request.Builder().url("${baseUrl.trimEnd('/')}$path")
                .header("X-ClosePaw-Token", authToken)
            if (payload == null) builder.get()
            else builder.post(payload.toString().toRequestBody(JSON))
            try {
                http.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext TermuxJobReply.Failure(
                            "bridge_http_error", "Local bridge HTTP ${response.code}", response.code,
                        )
                    }
                    try {
                        TermuxJobReply.Success(parseSnapshot(JSONObject(readBounded(response.body))))
                    } catch (_: org.json.JSONException) {
                        TermuxJobReply.Failure("bridge_invalid_response", "Invalid bridge job response")
                    } catch (_: RuntimeException) {
                        TermuxJobReply.Failure("bridge_invalid_response", "Invalid bridge job response")
                    }
                }
            } catch (_: IOException) {
                // A failed submission response does not establish whether the daemon accepted it.
                // Callers may retry start with the same request ID, never a fresh ID automatically.
                TermuxJobReply.Failure("bridge_transport_error", "Local bridge connection failed")
            }
        }

    private fun readBounded(body: ResponseBody?): String {
        requireNotNull(body) { "Missing bridge response" }
        val buffer = Buffer()
        val source = body.source()
        while (true) {
            val remaining = MAX_RESPONSE_BYTES + 1 - buffer.size
            val count = source.read(buffer, minOf(8192L, remaining))
            if (count == -1L) break
            require(buffer.size <= MAX_RESPONSE_BYTES) { "Bridge response exceeds size limit" }
        }
        return buffer.readUtf8()
    }

    private fun parseSnapshot(json: JSONObject): TermuxJobSnapshot {
        val jobId = json.getString("job_id").also(::validateJobId)
        val state = json.getString("state")
        require(state in STATES)
        return TermuxJobSnapshot(
            jobId = jobId,
            state = state,
            stdout = json.optString("stdout", ""),
            stderr = json.optString("stderr", ""),
            exitCode = if (!json.has("exit_code") || json.isNull("exit_code")) null else json.getInt("exit_code"),
            timedOut = json.optBoolean("timed_out", state == "timed_out"),
            stdoutTruncated = json.optBoolean("stdout_truncated", false),
            stderrTruncated = json.optBoolean("stderr_truncated", false),
            stdoutNextOffset = json.optLong("stdout_next_offset", 0),
            stderrNextOffset = json.optLong("stderr_next_offset", 0),
            durationMs = json.optLong("duration_ms", 0),
            outcomeUnknown = json.optBoolean("outcome_unknown", state == "interrupted"),
            cancelConfirmed = state == "cancelled" && json.optBoolean("cancel_confirmed", false),
        )
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 512L * 1024
        val JSON = "application/json; charset=utf-8".toMediaType()
        val STATES = setOf("queued", "running", "succeeded", "failed", "timed_out", "cancelled", "interrupted")
    }
}

data class TermuxJobReadiness(val ready: Boolean, val reason: String? = null)

data class TermuxJobSnapshot(
    val jobId: String,
    val state: String,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val stdoutNextOffset: Long = 0,
    val stderrNextOffset: Long = 0,
    val durationMs: Long = 0,
    val outcomeUnknown: Boolean = false,
    val cancelConfirmed: Boolean = false,
) {
    val truncated: Boolean get() = stdoutTruncated || stderrTruncated
    val terminal: Boolean get() = state !in setOf("queued", "running")
}

/** HTTP/connection failures are separate from the process exit code and captured stderr. */
sealed interface TermuxJobReply {
    data class Success(val snapshot: TermuxJobSnapshot) : TermuxJobReply
    data class Failure(val code: String, val message: String, val httpStatus: Int? = null) : TermuxJobReply
}
