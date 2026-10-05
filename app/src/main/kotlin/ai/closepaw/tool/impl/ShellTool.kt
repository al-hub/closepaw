package ai.closepaw.tool.impl

import ai.closepaw.bridge.CapabilityExecutionGateway
import ai.closepaw.bridge.ExecutionCapability
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.textToolSuccess
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ShellTool(
    private val gateway: CapabilityExecutionGateway,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : ToolSpec {
    override val name: String = "shell"

    override val description: String =
        """
        Android app-sandbox toybox shell for device-local file checks only. Limited available commands (ls, cat, stat, grep, wc, head, tail, sort, uniq, diff, du, df, file, touch, mkdir, cp, mv, rm, chmod, etc.), no support for pipe (|), redirect (>/<), or command substitution (${'
        """.trimIndent()

    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("command", JSONObject().apply {
                    put("type", "string")
                    put("description", "Shell command to execute")
                })
            })
            put("required", JSONArray(listOf("command")))
            put("additionalProperties", false)
        }

    override fun validate(params: JSONObject): ValidationResult {
        val command = params.optString("command", "").trim()
        if (command.isEmpty()) {
            return ValidationResult.Invalid("Missing required parameter: command")
        }
        // Reject shell metacharacters that enable chaining/bypassing
        val metaMatch = SHELL_METACHAR_PATTERN.find(command)
        if (metaMatch != null) {
            return ValidationResult.Invalid(
                "Shell metacharacters not allowed: ${metaMatch.value}"
            )
        }
        // Reject destructive commands by first token
        val firstToken = command.split(Regex("\\s+"), limit = 2).first()
            .substringAfterLast('/') // handle full paths like /system/bin/rm
        if (firstToken in BLOCKED_COMMANDS) {
            return ValidationResult.Invalid("Blocked destructive command: $firstToken")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val command = params.getString("command").trim()
        return ShellInvocation(command = command, params = params, timeoutSeconds = timeoutSeconds, gateway = gateway)
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 10L
        private const val MAX_OUTPUT_CHARS = 4096

        // Blocked first tokens. Rationale:
        //   am, pm        — Activity / Package Manager privilege escalation surface
        //   reboot, su    — destructive / root
        //   env           — mutates the environment seen by subsequent calls
        //   xargs, find   — both can execute arbitrary commands (xargs by design;
        //                   find via -exec), which would subvert the no-pipes /
        //                   no-redirects / no-`$()` policy enforced below.
        private val BLOCKED_COMMANDS = setOf("am", "pm", "reboot", "su", "env", "xargs", "find")

        // Rejects: ; | & ` > < newline/CR, and any $ (variable expansion/substitution)
        private val SHELL_METACHAR_PATTERN = Regex("[;|&`><\\n\\r\$]")
    }

    private class ShellInvocation(
        private val command: String,
        override val params: JSONObject,
        private val timeoutSeconds: Long,
        private val gateway: CapabilityExecutionGateway,
    ) : ToolInvocation {
        override val toolName: String = "shell"

        override fun getDescription(): String = "Execute: $command"

        override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
            if (context.isCancelled()) {
                return ToolExecutionResult.Cancelled("Cancelled before execution")
            }

            return try {
                val result =
                    gateway.execute(
                        capability = ExecutionCapability.ANDROID_SHELL,
                        command = command,
                        timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSeconds),
                    )
                if (result.timedOut) {
                    ToolExecutionResult.Failure("Command timed out after ${timeoutSeconds}s")
                } else {
                    val output =
                        buildString {
                            append("exit=${result.exitCode ?: -1}\n")
                            append(result.stdout)
                            if (result.stderr.isNotBlank()) {
                                if (result.stdout.isNotBlank()) append('\n')
                                append(result.stderr)
                            }
                        }.take(MAX_OUTPUT_CHARS)
                    val truncationNote =
                        if (output.length >= MAX_OUTPUT_CHARS) "\n[output truncated at $MAX_OUTPUT_CHARS chars]"
                        else ""
                    textToolSuccess(output = output + truncationNote)
                }
            } catch (e: Exception) {
                ToolExecutionResult.Failure("Shell execution failed: ${e.message}", e)
            }
        }
    }
}
}()). Do not use this tool for commands the user asks to run in Termux or Linux, even simple echo/pwd/whoami/ssh commands; use termux_shell. Never try to reach Termux with run-as, am, pm, UI typing, or broadcasts from this tool.
        """.trimIndent()

    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("command", JSONObject().apply {
                    put("type", "string")
                    put("description", "Shell command to execute")
                })
            })
            put("required", JSONArray(listOf("command")))
            put("additionalProperties", false)
        }

    override fun validate(params: JSONObject): ValidationResult {
        val command = params.optString("command", "").trim()
        if (command.isEmpty()) {
            return ValidationResult.Invalid("Missing required parameter: command")
        }
        // Reject shell metacharacters that enable chaining/bypassing
        val metaMatch = SHELL_METACHAR_PATTERN.find(command)
        if (metaMatch != null) {
            return ValidationResult.Invalid(
                "Shell metacharacters not allowed: ${metaMatch.value}"
            )
        }
        // Reject destructive commands by first token
        val firstToken = command.split(Regex("\\s+"), limit = 2).first()
            .substringAfterLast('/') // handle full paths like /system/bin/rm
        if (firstToken in BLOCKED_COMMANDS) {
            return ValidationResult.Invalid("Blocked destructive command: $firstToken")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val command = params.getString("command").trim()
        return ShellInvocation(command = command, params = params, timeoutSeconds = timeoutSeconds, gateway = gateway)
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 10L
        private const val MAX_OUTPUT_CHARS = 4096

        // Blocked first tokens. Rationale:
        //   am, pm        — Activity / Package Manager privilege escalation surface
        //   reboot, su    — destructive / root
        //   env           — mutates the environment seen by subsequent calls
        //   xargs, find   — both can execute arbitrary commands (xargs by design;
        //                   find via -exec), which would subvert the no-pipes /
        //                   no-redirects / no-`$()` policy enforced below.
        private val BLOCKED_COMMANDS = setOf("am", "pm", "reboot", "su", "env", "xargs", "find")

        // Rejects: ; | & ` > < newline/CR, and any $ (variable expansion/substitution)
        private val SHELL_METACHAR_PATTERN = Regex("[;|&`><\\n\\r\$]")
    }

    private class ShellInvocation(
        private val command: String,
        override val params: JSONObject,
        private val timeoutSeconds: Long,
        private val gateway: CapabilityExecutionGateway,
    ) : ToolInvocation {
        override val toolName: String = "shell"

        override fun getDescription(): String = "Execute: $command"

        override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
            if (context.isCancelled()) {
                return ToolExecutionResult.Cancelled("Cancelled before execution")
            }

            return try {
                val result =
                    gateway.execute(
                        capability = ExecutionCapability.ANDROID_SHELL,
                        command = command,
                        timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSeconds),
                    )
                if (result.timedOut) {
                    ToolExecutionResult.Failure("Command timed out after ${timeoutSeconds}s")
                } else {
                    val output =
                        buildString {
                            append("exit=${result.exitCode ?: -1}\n")
                            append(result.stdout)
                            if (result.stderr.isNotBlank()) {
                                if (result.stdout.isNotBlank()) append('\n')
                                append(result.stderr)
                            }
                        }.take(MAX_OUTPUT_CHARS)
                    val truncationNote =
                        if (output.length >= MAX_OUTPUT_CHARS) "\n[output truncated at $MAX_OUTPUT_CHARS chars]"
                        else ""
                    textToolSuccess(output = output + truncationNote)
                }
            } catch (e: Exception) {
                ToolExecutionResult.Failure("Shell execution failed: ${e.message}", e)
            }
        }
    }
}
