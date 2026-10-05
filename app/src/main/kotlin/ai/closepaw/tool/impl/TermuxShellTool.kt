package ai.closepaw.tool.impl

import ai.closepaw.bridge.CapabilityExecutionGateway
import ai.closepaw.bridge.ExecutionCapability
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolObservation
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import org.json.JSONArray
import org.json.JSONObject

class TermuxShellTool(
    private val gateway: CapabilityExecutionGateway,
) : ToolSpec {
    override val name: String = "termux_shell"

    override val description: String =
        """
        Execute a full Linux bash command through the available LINUX_SHELL capability.
        Supports pipes, redirects, git, python, node, and installed Termux packages.
        """.trimIndent()

    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("command", JSONObject().apply {
                    put("type", "string")
                    put("description", "Bash command to execute")
                })
                put("cwd", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional working directory")
                })
                put("timeout_seconds", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Command timeout in seconds")
                    put("default", DEFAULT_TIMEOUT_SECONDS)
                    put("maximum", MAX_TIMEOUT_SECONDS)
                })
                put("env", JSONObject().apply {
                    put("type", "object")
                    put("description", "Optional environment variables to pass through")
                    put("additionalProperties", JSONObject().put("type", "string"))
                })
            })
            put("required", JSONArray(listOf("command")))
            put("additionalProperties", false)
        }

    override fun validate(params: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()
        val names = params.names()
        if (names != null) {
            for (index in 0 until names.length()) {
                val key = names.optString(index)
                if (key !in ALLOWED_PARAMS) errors += "Unknown parameter: $key"
            }
        }
        val command = params.opt("command") as? String
        if (command == null || command.trim().isEmpty()) errors += "Missing required parameter: command"
        if (params.has("cwd") && !params.isNull("cwd") && params.opt("cwd") !is String) {
            errors += "Parameter cwd must be a string"
        }
        if (params.has("timeout_seconds") && !params.isNull("timeout_seconds")) {
            val timeout = parseTimeoutSeconds(params)
            if (timeout == null || timeout <= 0) errors += "Parameter timeout_seconds must be a positive integer"
        }
        val env = params.opt("env")
        if (env != null && env != JSONObject.NULL) {
            if (env !is JSONObject) errors += "Parameter env must be an object"
            else {
                val envNames = env.names()
                if (envNames != null) {
                    for (index in 0 until envNames.length()) {
                        val key = envNames.optString(index)
                        if (env.opt(key) !is String) errors += "Environment variable '$key' must be a string"
                    }
                }
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val command = params.optString("command", "").trim()
        val cwd = params.optString("cwd").trim().takeIf { params.has("cwd") && it.isNotEmpty() }
        val timeoutSeconds = (parseTimeoutSeconds(params) ?: DEFAULT_TIMEOUT_SECONDS).coerceAtMost(MAX_TIMEOUT_SECONDS)
        val envJson = params.opt("env") as? JSONObject
        val environment = buildMap {
            val names = envJson?.names()
            if (names != null) {
                for (index in 0 until names.length()) {
                    val key = names.getString(index)
                    put(key, envJson.getString(key))
                }
            }
        }
        return TermuxShellInvocation(command, cwd, timeoutSeconds * 1_000L, environment, params, gateway)
    }

    private class TermuxShellInvocation(
        private val command: String,
        private val cwd: String?,
        private val timeoutMs: Long,
        private val environment: Map<String, String>,
        override val params: JSONObject,
        private val gateway: CapabilityExecutionGateway,
    ) : ToolInvocation {
        override val toolName: String = "termux_shell"
        override fun getDescription(): String = "Execute Linux shell: $command"

        override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
            if (context.isCancelled()) return ToolExecutionResult.Cancelled("Cancelled before execution")
            return try {
                val result = gateway.execute(
                    capability = ExecutionCapability.LINUX_SHELL,
                    command = command,
                    workingDirectory = cwd,
                    timeoutMs = timeoutMs,
                    environment = environment,
                )
                val payload = JSONObject().apply {
                    put("adapter_id", result.adapterId)
                    put("exit_code", result.exitCode ?: JSONObject.NULL)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timed_out", result.timedOut)
                }
                ToolExecutionResult.Success(
                    output = payload.toString(2),
                    observation = ToolObservation.TextOutput(payload.toString(2))
                )
            } catch (e: Exception) {
                ToolExecutionResult.Failure(
                    JSONObject().apply {
                        put("reason", "execution_unavailable")
                        put("message", e.message ?: "No LINUX_SHELL provider is available")
                    }.toString(),
                    e
                )
            }
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 120
        private const val MAX_TIMEOUT_SECONDS = 120
        private val ALLOWED_PARAMS = setOf("command", "cwd", "timeout_seconds", "env")

        private fun parseTimeoutSeconds(params: JSONObject): Int? {
            if (!params.has("timeout_seconds") || params.isNull("timeout_seconds")) return DEFAULT_TIMEOUT_SECONDS
            return when (val raw = params.opt("timeout_seconds")) {
                is Int -> raw
                is Long -> raw.takeIf { it <= Int.MAX_VALUE }?.toInt()
                else -> null
            }
        }
    }
}
