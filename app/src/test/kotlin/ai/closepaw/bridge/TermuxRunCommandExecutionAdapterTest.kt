package ai.closepaw.bridge

import ai.closepaw.termux.RunCommandError
import ai.closepaw.termux.RunCommandResult
import ai.closepaw.termux.TermuxRunCommandAdapter
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TermuxRunCommandExecutionAdapterTest {
    private val runCommand = mockk<TermuxRunCommandAdapter>()
    private val adapter = TermuxRunCommandExecutionAdapter(runCommand)

    @Test
    fun `probe reports available when RUN_COMMAND succeeds`() = runTest {
        coEvery { runCommand.runShell("printf closepaw-probe", null, 5_000L) } returns
            RunCommandResult("closepaw-probe", "", 0)

        assertThat(adapter.probe()).isEqualTo(AdapterAvailability.Available)
    }

    @Test
    fun `probe falls through when Play Termux has no RUN_COMMAND service`() = runTest {
        coEvery { runCommand.runShell("printf closepaw-probe", null, 5_000L) } throws
            RunCommandError.TermuxNotAvailable

        assertThat(adapter.probe()).isInstanceOf(AdapterAvailability.Unavailable::class.java)
    }

    @Test
    fun `probe reports Android start restriction without claiming Termux is stopped`() = runTest {
        coEvery { runCommand.runShell("printf closepaw-probe", null, 5_000L) } throws
            RunCommandError.StartRestricted("BG-FGS-START denied")

        val availability = adapter.probe()

        assertThat(availability).isInstanceOf(AdapterAvailability.NeedsSetup::class.java)
        assertThat((availability as AdapterAvailability.NeedsSetup).reason)
            .contains("Android blocked Termux RUN_COMMAND service start: BG-FGS-START denied")
    }

    @Test
    fun `execute normalizes RUN_COMMAND result`() = runTest {
        coEvery { runCommand.runShell("echo ok", null, 2_000L) } returns
            RunCommandResult("ok\n", "", 0)

        val result =
            adapter.execute(
                ExecutionRequest(
                    capability = ExecutionCapability.LINUX_SHELL,
                    command = "echo ok",
                    timeoutMs = 2_000L,
                )
            )

        assertThat(result.adapterId).isEqualTo("termux-run-command")
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).isEqualTo("ok\n")
        assertThat(result.timedOut).isFalse()
    }

    @Test
    fun `execute preserves timeout as normalized result`() = runTest {
        coEvery { runCommand.runShell("sleep 10", null, 100L) } throws RunCommandError.Timeout(100L)

        val result =
            adapter.execute(
                ExecutionRequest(
                    capability = ExecutionCapability.LINUX_SHELL,
                    command = "sleep 10",
                    timeoutMs = 100L,
                )
            )

        assertThat(result.exitCode).isNull()
        assertThat(result.timedOut).isTrue()
    }
}
