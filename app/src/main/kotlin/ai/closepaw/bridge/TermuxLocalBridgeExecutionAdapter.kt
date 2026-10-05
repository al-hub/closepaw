package ai.closepaw.bridge

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class TermuxLocalBridgeExecutionAdapter(
    private val baseUrl: String = "http://127.0.0.1:18422",
    private val client: OkHttpClient = OkHttpClient(),
) : ExecutionAdapter {
    override val id = "termux-local-bridge"
    override val capabilities = setOf(ExecutionCapability.LINUX_SHELL)

    override suspend fun probe(): AdapterAvailability = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/v1/health").get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext AdapterAvailability.Unavailable("health check failed")
                val body = JSONObject(response.body.string())
                if (body.optString("identity") == "closepaw-bridge") AdapterAvailability.Available
                else AdapterAvailability.Unavailable("unexpected localhost service")
            }
        } catch (_: IOException) {
            AdapterAvailability.NeedsSetup("local bridge is not running or paired")
        }
    }

    override suspend fun execute(request: ExecutionRequest): ExecutionResult =
        ExecutionResult(id, null, "", "local bridge execution requires pairing client")
}
