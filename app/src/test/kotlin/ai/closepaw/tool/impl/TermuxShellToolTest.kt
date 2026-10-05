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
import ai.closepaw.tool.ValidationResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

class TermuxShellToolTest {

    @Test
    fun `missing command is invalid`() {
        assertThat(TermuxShellTool(gateway()).validate(JSONObject()))
            .isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `non-string env value is invalid`() {
        val params = JSONObject().put("command", "env").put("env", JSONObject().put("COUNT", 1))
        assertThat(TermuxShellTool(gateway()).validate(params))
            .isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `non-integer timeout seconds is invalid`() {
        val params = JSONObject().put("command", "pwd").put("timeout_seconds", "10")
        assertThat(TermuxShellTool(gateway()).validate(params))
            .isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `non-string cwd is invalid`() {
        val params = JSONObject().put("command", "pwd").put("cwd", 42)
        assertThat(TermuxShellTool(gateway()).validate(params))
            .isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `timeout above maximum is clamped before gateway execution`() = runTest {
        var captured: ExecutionRequest? = null
        val tool = TermuxShellTool(gateway { request ->
            captured = request
            ExecutionResult("test-linux", 0, "ok\n", "")
        })

        val result = tool.createInvocation(
            JSONObject().put("command", "echo ok").put("timeout_seconds", 999)
        ).execute(testContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(captured!!.timeoutMs).isEqualTo(120_000L)
    }

    @Test
    fun `gateway request preserves command cwd env and timeout`() = runTest {
        var captured: ExecutionRequest? = null
        val tool = TermuxShellTool(gateway { request ->
            captured = request
            ExecutionResult("test-linux", 0, "ok\n", "")
        })
        val params = JSONObject()
            .put("command", "python3 script.py")
            .put("cwd", "~/closepaw/workspace/project")
            .put("timeout_seconds", 3)
            .put("env", JSONObject().put("TERM", "dumb").put("LC_ALL", "C"))

        val result = tool.createInvocation(params).execute(testContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(captured!!.capability).isEqualTo(ExecutionCapability.LINUX_SHELL)
        assertThat(captured!!.command).isEqualTo("python3 script.py")
        assertThat(captured!!.workingDirectory).isEqualTo("~/closepaw/workspace/project")
        assertThat(captured!!.timeoutMs).isEqualTo(3_000L)
        assertThat(captured!!.environment).containsExactly("TERM", "dumb", "LC_ALL", "C")
    }

    @Test
    fun `execution result is normalized as tool success`() = runTest {
        val tool = TermuxShellTool(gateway {
            ExecutionResult("test-linux", 7, "", "bad")
        })

        val result = tool.createInvocation(JSONObject().put("command", "exit 7")).execute(testContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = JSONObject((result as ToolExecutionResult.Success).output)
        assertThat(output.getString("adapter_id")).isEqualTo("test-linux")
        assertThat(output.getInt("exit_code")).isEqualTo(7)
        assertThat(output.getString("stderr")).isEqualTo("bad")
    }

    @Test
    fun `timed out result keeps null exit code`() = runTest {
        val tool = TermuxShellTool(gateway {
            ExecutionResult("test-linux", null, "", "", timedOut = true)
        })

        val result = tool.createInvocation(JSONObject().put("command", "sleep 999")).execute(testContext())

        val output = JSONObject((result as ToolExecutionResult.Success).output)
        assertThat(output.getBoolean("timed_out")).isTrue()
        assertThat(output.isNull("exit_code")).isTrue()
    }

    @Test
    fun `unavailable capability maps to execution unavailable failure`() = runTest {
        val gateway = CapabilityExecutionGateway(ExecutionAdapterRegistry(emptyList()))
        val result = TermuxShellTool(gateway)
            .createInvocation(JSONObject().put("command", "echo hi"))
            .execute(testContext())

        assertThat(result).isInstanceOf(ToolExecutionResult.Failure::class.java)
        val error = JSONObject((result as ToolExecutionResult.Failure).error)
        assertThat(error.getString("reason")).isEqualTo("execution_unavailable")
    }

    @Test
    fun `pre-cancelled context does not execute gateway`() = runTest {
        var executed = false
        val tool = TermuxShellTool(gateway {
            executed = true
            ExecutionResult("test-linux", 0, "", "")
        })
        val result = tool.createInvocation(JSONObject().put("command", "echo hi"))
            .execute(testContext(cancelled = true))

        assertThat(result).isInstanceOf(ToolExecutionResult.Cancelled::class.java)
        assertThat(executed).isFalse()
    }

    private fun gateway(
        execute: suspend (ExecutionRequest) -> ExecutionResult = {
            ExecutionResult("test-linux", 0, "", "")
        }
    ) = CapabilityExecutionGateway(
        ExecutionAdapterRegistry(
            listOf(object : ExecutionAdapter {
                override val id = "test-linux"
                override val capabilities = setOf(ExecutionCapability.LINUX_SHELL)
                override suspend fun probe() = AdapterAvailability.Available
                override suspend fun execute(request: ExecutionRequest) = execute(request)
            })
        )
    )

    private fun testContext(cancelled: Boolean = false): ToolExecutionContext =
        object : ToolExecutionContext {
            override val platform = FakeAndroidPlatform()
            override val currentSnapshot = null
            override fun isCancelled(): Boolean = cancelled
        }
}
