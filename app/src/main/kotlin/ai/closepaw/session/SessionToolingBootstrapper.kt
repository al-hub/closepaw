package ai.closepaw.session

import android.content.Context
import android.util.Log
import ai.closepaw.bridge.AndroidIntentExecutionAdapter
import ai.closepaw.bridge.AndroidShellExecutionAdapter
import ai.closepaw.bridge.CapabilityExecutionGateway
import ai.closepaw.bridge.ExecutionAdapter
import ai.closepaw.bridge.ExecutionAdapterRegistry
import ai.closepaw.bridge.TermuxLocalBridgeExecutionAdapter
import ai.closepaw.bridge.TermuxRunCommandExecutionAdapter
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.termux.TermuxRunCommandAdapter
import ai.closepaw.agent.cognition.skills.AgentSkillManager
import ai.closepaw.agent.definition.AgentRoleDef
import ai.closepaw.agent.definition.ResolvedAgentRole
import ai.closepaw.agent.definition.DefaultRoleDef
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.termux.TermuxBridgeManager
import ai.closepaw.termux.TermuxBridgeAuth
import ai.closepaw.termux.TermuxCapabilitySnapshot
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolName
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.tool.impl.ActivateSkillTool
import ai.closepaw.tool.impl.CompleteTaskTool
import ai.closepaw.tool.impl.MobileActionTool
import ai.closepaw.tool.impl.OpenAppTool
import ai.closepaw.tool.impl.ScratchpadTool
import ai.closepaw.tool.impl.ShellTool
import ai.closepaw.tool.impl.SystemButtonTool
import ai.closepaw.tool.impl.TermuxShellTool
import ai.closepaw.tool.impl.WaitTool
import ai.closepaw.tool.impl.WriteTodosTool

internal data class SessionToolingBootstrap(
        val policyEngine: PolicyEngine,
        val sessionState: AgentSessionState,
        val toolRegistry: ToolRegistry,
        val toolRouter: ToolRouter
)

/** Creates policy + session state + built-in tools + router for a session. */
internal object SessionToolingBootstrapper {
    private const val TAG = "SessionToolingBootstrap"

    fun create(
        approvalMode: ApprovalMode,
        appClassifier: AppClassifier,
        agentSkillManager: AgentSkillManager? = null,
        agentRoleDef: AgentRoleDef = DefaultRoleDef,
        delegatableRoleDefs: List<AgentRoleDef> = emptyList(),
        termuxSnapshot: TermuxCapabilitySnapshot = TermuxCapabilitySnapshot.Unavailable,
        excludedTools: Set<String> = emptySet(),
        context: Context? = null,
        platform: AndroidPlatform? = null,
    ): SessionToolingBootstrap {
        val policyEngine = PolicyEngine(
            initialApprovalMode = approvalMode,
            appClassifier = appClassifier
        )
        val sessionState = AgentSessionState()
        val allowedToolNames = resolveAllowedToolNames(
            agentRoleDef = agentRoleDef,
            delegatableRoleDefs = delegatableRoleDefs,
            termuxSnapshot = termuxSnapshot,
            excludedTools = excludedTools
        )
        val executionGateway = createExecutionGateway(context, platform)
        val toolRegistry = ToolRegistry().apply {
            registerBuiltInTools(
                sessionState = sessionState,
                allowedToolNames = allowedToolNames,
                context = context,
                executionGateway = executionGateway,
            )
        }

        if (
            ToolName.ActivateSkill.raw in allowedToolNames &&
                agentSkillManager != null &&
                agentSkillManager.catalogPrompt() != null
        ) {
            toolRegistry.register(ActivateSkillTool(agentSkillManager))
            Log.d(TAG, "Registered ActivateSkillTool (catalog non-empty)")
        }

        val toolRouter = ToolRouter(toolRegistry, policyEngine)

        Log.d(TAG, "Created policy/tool stack with ${toolRegistry.size()} built-in tools")

        return SessionToolingBootstrap(
                policyEngine = policyEngine,
                sessionState = sessionState,
                toolRegistry = toolRegistry,
                toolRouter = toolRouter
        )
    }

    private fun resolveAllowedToolNames(
        agentRoleDef: AgentRoleDef,
        delegatableRoleDefs: List<AgentRoleDef>,
        termuxSnapshot: TermuxCapabilitySnapshot,
        excludedTools: Set<String>
    ): Set<String> {
        val excludedToolNames = excludedTools.map { ToolName.from(it) }.toSet()
        val resolvedRoles: List<ResolvedAgentRole> =
            (listOf(agentRoleDef) + delegatableRoleDefs)
                .map { it.resolve(termuxSnapshot, excludedToolNames) }

        return resolvedRoles
            .flatMap { role -> role.allowedTools.map { tool -> tool.raw } }
            .toSet()
    }

    private fun ToolRegistry.registerBuiltInTools(
        sessionState: AgentSessionState,
        allowedToolNames: Set<String>,
        context: Context?,
        executionGateway: CapabilityExecutionGateway?,
    ) {
        if (ToolName.CompleteTask.raw in allowedToolNames) register(CompleteTaskTool())
        if (ToolName.MobileAction.raw in allowedToolNames) register(MobileActionTool())
        if (ToolName.SystemButton.raw in allowedToolNames) register(SystemButtonTool())
        if (ToolName.Wait.raw in allowedToolNames) register(WaitTool())
        if (ToolName.OpenApp.raw in allowedToolNames && executionGateway != null) register(OpenAppTool(executionGateway))
        if (ToolName.Shell.raw in allowedToolNames && executionGateway != null) register(ShellTool(executionGateway))
        if (ToolName.TermuxShell.raw in allowedToolNames && executionGateway != null) registerTermuxShellTool(context, executionGateway)
        if (ToolName.WriteTodos.raw in allowedToolNames) register(WriteTodosTool(sessionState.todos))
        if (ToolName.Scratchpad.raw in allowedToolNames) register(ScratchpadTool(sessionState.scratchpad))
    }

    private fun ToolRegistry.registerTermuxShellTool(
        context: Context?,
        executionGateway: CapabilityExecutionGateway,
    ) {
        if (context != null) {
            TermuxBridgeManager.get(context)
        } else {
            Log.w(TAG, "Registering termux_shell without a Context; bridge manager not touched")
        }
        register(TermuxShellTool(executionGateway))
    }

    private fun createExecutionGateway(
        context: Context?,
        platform: AndroidPlatform?,
    ): CapabilityExecutionGateway? {
        val adapters = mutableListOf<ExecutionAdapter>()
        adapters += AndroidShellExecutionAdapter()
        if (platform != null) {
            adapters += AndroidIntentExecutionAdapter(platform)
        }
        if (context != null) {
            adapters += TermuxRunCommandExecutionAdapter(TermuxRunCommandAdapter(context))
            adapters += TermuxLocalBridgeExecutionAdapter(TermuxBridgeAuth.token(context))
        }
        return CapabilityExecutionGateway(ExecutionAdapterRegistry(adapters))
    }
}
