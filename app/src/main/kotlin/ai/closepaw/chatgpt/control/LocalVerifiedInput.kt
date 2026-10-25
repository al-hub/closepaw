package ai.closepaw.chatgpt.control

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.McpExecutionSettings
import ai.closepaw.platform.VerifiedInputRequest
import ai.closepaw.protocol.SessionState
import ai.closepaw.tool.action.ActionOutcome
import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Adapter for already policy-approved local tool invocations. A captured local
 * owner session and cancellation token remain live throughout every native check.
 * No remote session activation, permissive app overrides or alternate action path. */
internal object LocalVerifiedInput {
    suspend fun run(accessibility: AccessibilityService, request: VerifiedInputRequest, cancelled: () -> Boolean): ActionOutcome {
        fun fail(reason: String) = ActionOutcome.Failed(reason, listOf("unified_input:no_replay"))
        val service = accessibility as? AgentService ?: return fail("local_agent_service_required")
        val owner = service.getActiveSession() ?: return fail("local_agent_session_required")
        val remote = service.remoteControlSession
        val remoteEpoch = remote.epoch
        if (remote.state !in setOf(ControlSession.State.IDLE, ControlSession.State.ENDED)) return fail("remote_control_busy")
        val settings = McpExecutionSettings(service)
        val pkg = withContext(Dispatchers.Main) {
            service.rootInActiveWindow?.let { root -> try { root.packageName?.toString() } finally { root.recycle() } }
        } ?: return fail("active_root_missing")
        if (pkg != request.approvedPackageName) return fail("local_input_package_approval_mismatch")
        if (request.clipboardConsent && request.clipboardReplaceAuthorized && !request.replacementApprovalVerified)
            return fail("clipboard_replacement_approval_required")
        if (request.terminalAuthorized && !request.terminalApprovalVerified) return fail("terminal_input_approval_required")
        fun live() = AgentService.instance === service && service.getActiveSession() === owner &&
            owner.state.value == SessionState.Running && !cancelled() && remote.epoch == remoteEpoch &&
            remote.state in setOf(ControlSession.State.IDLE, ControlSession.State.ENDED) && settings.genericControlEnabled
        if (!live()) return fail("local_input_authority_unavailable")
        if (!ControlScreenLease.mutex.tryLock()) return fail("ui_busy")
        val authority = LocalInputAuthority(::live)
        val port = AndroidControlPort(service, runtime = settings.runtimeControl, localAuthority = authority, allowed = ::live)
        val control = GenericControl(port, ::live)
        return try { withTimeout(120_000) {
            val observed = control.observe()
            if (!observed.success) return@withTimeout fail(observed.summary)
            val frameNodes = observed.data["nodes"]?.jsonArray.orEmpty()
            val element = request.element
            val bounds = "${element.bounds.left} ${element.bounds.top} ${element.bounds.right} ${element.bounds.bottom}"
            if (System.currentTimeMillis() - request.snapshot.timestamp !in 0L..15_000L ||
                port.session() !== authority.session || !live() || observed.data["package_name"]?.jsonPrimitive?.content != pkg)
                return@withTimeout fail("local_input_snapshot_stale")
            val target = frameNodes.filter { json ->
                val node = json.jsonObject
                node["resource_id"]?.jsonPrimitive?.content == element.resourceId &&
                    node["class_name"]?.jsonPrimitive?.content == element.className &&
                    node["bounds"]?.jsonPrimitive?.content == bounds &&
                    node["focused"]?.jsonPrimitive?.booleanOrNull == true &&
                    node["text"]?.jsonPrimitive?.content == element.text.take(100)
            }.singleOrNull()?.jsonObject ?: return@withTimeout fail("local_input_target_changed")
            val terminal = target["terminal_input_supported"]?.jsonPrimitive?.booleanOrNull == true
            if (terminal && (!request.terminalAuthorized || request.method !in setOf("auto", "direct")))
                return@withTimeout fail("terminal_input_authorization_required")
            val result = control.action(buildJsonObject {
                put("action", if (terminal) "terminal_input" else "input"); put("terminal_authorized", request.terminalAuthorized); put("session_id", authority.session.id)
                put("observation_id", observed.data.getValue("observation_id")); put("node_id", target.getValue("node_id"))
                put("text", request.text); put("method", request.method); put("mode", "insert")
                put("clipboard_consent", request.clipboardConsent); put("clipboard_replace_authorized", request.clipboardReplaceAuthorized)
            })
            if (result.success) ActionOutcome.Success("Exact unified input result verified", null, listOf("unified_input:verified"), true)
            else fail(result.data["reason_code"]?.jsonPrimitive?.content ?: "unified_input_unknown")
        } } catch (e: CancellationException) { throw e }
        catch (_: Exception) { fail("unified_input_unknown") }
        finally { control.invalidate(); authority.close(); ControlScreenLease.mutex.unlock() }
    }
}
