package ai.closepaw.tool.impl

import ai.closepaw.bridge.AdapterAvailability
import ai.closepaw.bridge.CapabilityExecutionGateway
import ai.closepaw.bridge.ExecutionAdapter
import ai.closepaw.bridge.ExecutionAdapterRegistry
import ai.closepaw.bridge.ExecutionCapability
import ai.closepaw.bridge.ExecutionRequest
import ai.closepaw.bridge.ExecutionResult
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

/** Execution-path coverage for ShellTool through the capability gateway. */
class ShellToolExecutionTest {

    @Test
    fun `description keeps Android shell out of explicit Termux execution`() {
        val tool = ShellTool(gateway(ExecutionResult("test-shell", 0, "", "")))

        assertThat(tool.description).contains("Do not use this tool for commands the user asks to run in Termux")
        assertThat(tool.description).contains("termux_shell")
        assertThat(tool.description).contains("run-as")
    }

    @Test
    fun `command timeout returns timeout-specific failure`() = runTest {
        val tool = ShellTool(gateway(ExecutionResult("test-shell", null, "", "", timedOut = true)), timeoutSeconds = 1L)
        val result = tool.createInvocation(JSONObject().put("command", "sleep 5")).execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Failure::class.java)
        assertThat((result as ToolExecutionResult.Failure).error).contains("timed out")
        assertThat(result.error).contains("1s")
    }

    @Test
    fun `long output is truncated to configured limit`() = runTest {
        val filler = "a".repeat(5000)
        val tool = ShellTool(gateway(ExecutionResult("test-shell", 0, filler, "")))
        val result = tool.createInvocation(JSONObject().put("command", "echo filler")).execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).contains("[output truncated at 4096 chars]")
    }

    @Test
    fun `formatted output includes exit code`() = runTest {
        val tool = ShellTool(gateway(ExecutionResult("test-shell", 1, "", "")))
        val result = tool.createInvocation(JSONObject().put("command", "false")).execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat((result as ToolExecutionResult.Success).output).startsWith("exit=1")
    }

    @Test
    fun `exit code zero is reflected in output`() = runTest {
        val tool = ShellTool(gateway(ExecutionResult("test-shell", 0, "hello\n", "")))
        val result = tool.createInvocation(JSONObject().put("command", "echo hello")).execute(buildContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).startsWith("exit=0")
        assertThat(output).contains("hello")
    }

    @Test
    fun `pre-cancelled context short-circuits before gateway execution`() = runTest {
        var executed = false
        val tool = ShellTool(gateway(ExecutionResult("test-shell", 0, "", "")) { executed = true })
        val result = tool.createInvocation(JSONObject().put("command", "sleep 30"))
            .execute(buildContext(cancelled = true))

        assertThat(result).isInstanceOf(ToolExecutionResult.Cancelled::class.java)
        assertThat(executed).isFalse()
    }

    private fun gateway(result: ExecutionResult, onExecute: () -> Unit = {}): CapabilityExecutionGateway =
        CapabilityExecutionGateway(
            ExecutionAdapterRegistry(
                listOf(object : ExecutionAdapter {
                    override val id = "test-shell"
                    override val capabilities = setOf(ExecutionCapability.ANDROID_SHELL)
                    override suspend fun probe() = AdapterAvailability.Available
                    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
                        onExecute()
                        return result
                    }
                })
            )
        )

    private fun buildContext(cancelled: Boolean = false): ToolExecutionContext =
        object : ToolExecutionContext {
            override val platform = FakeAndroidPlatform()
            override val currentSnapshot = null
            override fun isCancelled(): Boolean = cancelled
        }
}
