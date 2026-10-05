package ai.closepaw.agent.definition

import ai.closepaw.termux.TermuxBridgeStatus
import ai.closepaw.termux.TermuxCapabilitySnapshot
import ai.closepaw.tool.ToolName
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentRoleResolveTest {

    @Test
    fun `available snapshot exposes termux shell and workspace prompt`() {
        val resolved = DefaultRoleDef.resolve(availableSnapshot)

        assertThat(resolved.allowedTools).contains(ToolName.TermuxShell)
        assertThat(resolved.systemPrompt).contains("Workspace Shell")
    }

    @Test
    fun `enabled Termux shell remains exposed while bridge needs setup`() {
        val snapshot = TermuxCapabilitySnapshot(
            available = false,
            enabled = true,
            status = TermuxBridgeStatus.NeedsSetup(ai.closepaw.termux.NeedsSetupReason.HEALTH_TIMEOUT)
        )

        val resolved = DefaultRoleDef.resolve(snapshot)

        assertThat(resolved.allowedTools).contains(ToolName.TermuxShell)
        assertThat(resolved.systemPrompt).contains("Workspace Shell")
    }

    @Test
    fun `unavailable snapshot hides termux shell and workspace prompt`() {
        val resolved = DefaultRoleDef.resolve(unavailableSnapshot)

        assertThat(resolved.allowedTools).doesNotContain(ToolName.TermuxShell)
        assertThat(resolved.systemPrompt).doesNotContain("Workspace Shell")
    }

    @Test
    fun `excluded termux shell hides tool and workspace prompt`() {
        val resolved = DefaultRoleDef.resolve(
            snapshot = availableSnapshot,
            excludedTools = setOf(ToolName.TermuxShell)
        )

        assertThat(resolved.allowedTools).doesNotContain(ToolName.TermuxShell)
        assertThat(resolved.systemPrompt).doesNotContain("Workspace Shell")
    }

    @Test
    fun `default role workspace prompt includes the inline-execution directive`() {
        val resolved = DefaultRoleDef.resolve(availableSnapshot)

        assertThat(resolved.systemPrompt).contains(MAIN_WORKSPACE_SHELL_DIRECTIVE)
    }

    @Test
    fun `available snapshot makes explicit Termux requests route to termux shell`() {
        val resolved = DefaultRoleDef.resolve(availableSnapshot)

        assertThat(resolved.systemPrompt)
            .contains("If the user explicitly asks to run a command in Termux, always use termux_shell")
        assertThat(resolved.systemPrompt)
            .contains("echo, pwd, whoami, or ssh")
        assertThat(resolved.systemPrompt)
            .contains("Do not emulate Termux execution through shell")
    }

    @Test
    fun `default role without termux does not contain the directive`() {
        val resolved = DefaultRoleDef.resolve(unavailableSnapshot)

        assertThat(resolved.systemPrompt).doesNotContain(MAIN_WORKSPACE_SHELL_DIRECTIVE)
    }

    private companion object {
        const val MAIN_WORKSPACE_SHELL_DIRECTIVE =
            "For workspace commands (termux_shell), execute directly instead of delegating."
        val availableSnapshot = TermuxCapabilitySnapshot(
            available = true,
            enabled = true,
            status = TermuxBridgeStatus.Ready
        )
        val unavailableSnapshot = TermuxCapabilitySnapshot(
            available = false,
            enabled = true,
            status = TermuxBridgeStatus.Disabled
        )
    }
}
