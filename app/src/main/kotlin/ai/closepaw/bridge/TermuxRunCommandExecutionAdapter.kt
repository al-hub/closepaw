package ai.closepaw.bridge

import ai.closepaw.termux.RunCommandError
import ai.closepaw.termux.TermuxInstallProbe
import ai.closepaw.termux.TermuxInstallState
import ai.closepaw.termux.TermuxRunCommandAdapter

/**
 * Exposes Termux RUN_COMMAND through the transport-neutral AA-Bridge contract.
 *
 * Availability is verified with a lightweight shell probe so Play Store builds that
 * do not expose RunCommandService naturally fall through to the next LINUX_SHELL adapter.
 */
class TermuxRunCommandExecutionAdapter private constructor(
    private val runCommand: TermuxRunCommandAdapter,
    private val installProbe: TermuxInstallProbe,
) : ExecutionAdapter {
    constructor(runCommand: TermuxRunCommandAdapter) : this(
        runCommand,
        TermuxInstallProbe { TermuxInstallState.Available },
    )

    internal companion object {
        fun capabilityAware(
            runCommand: TermuxRunCommandAdapter,
            installProbe: TermuxInstallProbe,
        ): TermuxRunCommandExecutionAdapter =
            TermuxRunCommandExecutionAdapter(runCommand, installProbe)

        private const val PROBE_TIMEOUT_MS = 5_000L
    }
    override val id: String = "termux-run-command"
    override val capabilities: Set<ExecutionCapability> = setOf(ExecutionCapability.LINUX_SHELL)

    override suspend fun probe(): AdapterAvailability {
        when (installProbe.inspect()) {
            TermuxInstallState.NotInstalled ->
                return AdapterAvailability.Unavailable("Termux is not installed")
            TermuxInstallState.RunCommandUnavailable ->
                return AdapterAvailability.Unavailable(
                    "Installed Termux does not expose the RUN_COMMAND service contract"
                )
            TermuxInstallState.Available -> Unit
        }
        return try {
            val result = runCommand.runShell("printf closepaw-probe", timeoutMs = PROBE_TIMEOUT_MS)
            if (result.exitCode == 0 && result.stdout == "closepaw-probe") {
                AdapterAvailability.Available
            } else {
                AdapterAvailability.Unavailable("RUN_COMMAND probe failed with exit code ${result.exitCode}")
            }
        } catch (error: RunCommandError.PermissionMissing) {
            AdapterAvailability.NeedsSetup("Termux RUN_COMMAND permission is missing")
        } catch (error: RunCommandError.AllowExternalAppsMissing) {
            AdapterAvailability.NeedsSetup("Termux allow-external-apps is disabled")
        } catch (error: RunCommandError.StartRestricted) {
            AdapterAvailability.NeedsSetup(
                "Android blocked Termux RUN_COMMAND service start: ${error.detail}"
            )
        } catch (error: RunCommandError.TermuxNotAvailable) {
            AdapterAvailability.Unavailable("Termux RUN_COMMAND service is unavailable")
        } catch (error: RunCommandError.Timeout) {
            AdapterAvailability.Unavailable("Termux RUN_COMMAND probe timed out")
        } catch (error: RunCommandError.Other) {
            AdapterAvailability.Unavailable("Termux RUN_COMMAND probe failed")
        }
    }

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        require(request.capability == ExecutionCapability.LINUX_SHELL) {
            "Termux RUN_COMMAND only provides LINUX_SHELL"
        }
        val environmentPrefix =
            request.environment.entries.joinToString(" ") { (key, value) ->
                "${key}=${shellQuote(value)}"
            }
        val commandWithEnvironment =
            if (environmentPrefix.isBlank()) request.command else "env $environmentPrefix ${request.command}"
        val command =
            request.workingDirectory?.let { cwd ->
                "cd -- ${shellQuote(cwd)} && $commandWithEnvironment"
            } ?: commandWithEnvironment
        return try {
            val result = runCommand.runShell(command, timeoutMs = request.timeoutMs)
            ExecutionResult(
                adapterId = id,
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
            )
        } catch (error: RunCommandError.Timeout) {
            ExecutionResult(
                adapterId = id,
                exitCode = null,
                stdout = "",
                stderr = "Command timed out after ${error.ms} ms",
                timedOut = true,
            )
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

}
