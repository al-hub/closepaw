package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import ai.closepaw.termux.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TermuxBridgeStatusOperationTest {
    @Test fun offlineDiagnosticsRemainQueryableWithoutUiOrShellReadiness() = runTest {
        var observations = 0
        val op = TermuxBridgeStatusOperation({
            observations++
            TermuxBridgeDiagnosticSnapshot(TermuxInstallState.RunCommandUnavailable, HealthProbe.Unavailable)
        })
        val descriptor = TermuxBridgeStatusOperation.descriptor { true }
        assertTrue(descriptor.readOnly)
        assertTrue(descriptor.safeReadiness().ready)
        assertTrue(descriptor.effects.isEmpty())
        assertFalse(TermuxBridgeStatusOperation.descriptor { false }.safeReadiness().ready)
        val data = op.invoke(OperationInvocation("read", JsonObject(emptyMap()))).data
        assertEquals(1, observations)
        assertEquals("not_observed", data.getValue("process_state").jsonPrimitive.content)
        assertEquals("not_observed", data.getValue("supervisor_state").jsonPrimitive.content)
        assertEquals("run_existing_recovery_hook_once_in_termux", data.getValue("recommended_action").jsonPrimitive.content)
        for (key in listOf("recovery_attempted", "command_submitted", "pairing_changed", "run_command_supported")) {
            assertFalse(data.getValue(key).jsonPrimitive.boolean)
        }
    }

    @Test fun healthAndRecoveryCapabilityRemainIndependent() = runTest {
        val cases = listOf(
            HealthProbe.Ready to "none",
            HealthProbe.AuthMismatch to "inspect_pairing_without_rotating_token",
            HealthProbe.InvalidIdentity to "inspect_port_owner_do_not_kill",
            HealthProbe.BridgeOutdated to "explicit_bridge_upgrade_required",
            HealthProbe.UnverifiedListener to "inspect_unverified_listener_do_not_change_pairing",
            HealthProbe.ResponseTimeout to "inspect_unresponsive_bridge_do_not_replay",
        )
        for ((health, action) in cases) {
            val op = TermuxBridgeStatusOperation({
                TermuxBridgeDiagnosticSnapshot(TermuxInstallState.RunCommandUnavailable, health)
            })
            val data = op.invoke(OperationInvocation("read", JsonObject(emptyMap()))).data
            assertEquals(action, data.getValue("recommended_action").jsonPrimitive.content)
            assertFalse(data.getValue("run_command_supported").jsonPrimitive.boolean)
        }
    }
}
