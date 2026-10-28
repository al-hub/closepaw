package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.OperationInvocation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SelfMonitoringOperationTest {
    private fun state(enabled: Boolean) = SelfMonitoringOperation.LocalState(
        accessibilityConnected = true, sessionState = "paused", deviceLocked = true,
        controlEnabled = true, remoteExecutionEnabled = enabled, projectionLeaseActive = false,
    )

    @Test fun passiveSnapshotHasNoRecoveryOrBridgeProbe() = runTest {
        val op = SelfMonitoringOperation { state(true) }
        val descriptor = SelfMonitoringOperation.descriptor { true }
        assertTrue(descriptor.readOnly)
        assertTrue(descriptor.effects.isEmpty())
        assertFalse(SelfMonitoringOperation.descriptor { false }.safeReadiness().ready)
        val data = op.invoke(OperationInvocation("read", JsonObject(emptyMap()))).data
        assertEquals("paused", data.getValue("control_session").jsonPrimitive.content)
        assertTrue(data.getValue("device_locked").jsonPrimitive.boolean)
        assertEquals("not_observed", data.getValue("bridge_health").jsonPrimitive.content)
        assertFalse(data.getValue("recovery_attempted").jsonPrimitive.boolean)
        assertEquals("not_observed", data.getValue("recent_error").jsonPrimitive.content)
    }

    @Test fun disabledBridgeRemainsUnobserved() = runTest {
        val op = SelfMonitoringOperation { state(false) }
        val data = op.invoke(OperationInvocation("read", JsonObject(emptyMap()))).data
        assertEquals("not_observed", data.getValue("bridge_health").jsonPrimitive.content)
    }

}
