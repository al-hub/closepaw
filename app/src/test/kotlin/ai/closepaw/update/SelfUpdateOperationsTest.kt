package ai.closepaw.update

import ai.closepaw.chatgpt.operations.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SelfUpdateOperationsTest {
    @Test fun discoveryKeepsFixedEnvelopeAndReportsLocalConsent() {
        val updater = mockk<AndroidSelfUpdater>()
        every { updater.enabled() } returns false
        val registry = OperationRegistry()
        SelfUpdateOperations(updater).register(registry)
        assertEquals(3, registry.all().size)
        val install = registry.get(SelfUpdateOperations.INSTALL)!!.descriptor
        assertFalse(install.readOnly)
        assertFalse(install.safeReadiness().ready)
        assertEquals("self_update_disabled", install.safeReadiness().reason)
        assertNotNull(OperationSchemaValidator.validate(install.inputSchema, buildJsonObject {}))
        assertNull(OperationSchemaValidator.validate(install.inputSchema, buildJsonObject { put("expected_version", "0.1.56") }))
    }
    @Test fun submittedInstallDoesNotClaimInstalled() = runTest {
        val updater = mockk<AndroidSelfUpdater>()
        every { updater.enabled() } returns true
        every { updater.start("task", "0.1.56") } returns buildJsonObject { put("state", "installing") }
        val registry = OperationRegistry()
        SelfUpdateOperations(updater).register(registry)
        val result = registry.get(SelfUpdateOperations.INSTALL)!!.handler.invoke(
            OperationInvocation("task", buildJsonObject { put("expected_version", "0.1.56") }))
        assertEquals("installing", result.data["state"]!!.jsonPrimitive.content)
        assertTrue(result.summary.contains("reconnection"))
        verify(exactly = 1) { updater.start("task", "0.1.56") }
    }
    @Test fun statusQueryPreservesFailureAndDoesNotStartAnInstall() = runTest {
        val updater = mockk<AndroidSelfUpdater>()
        every { updater.status() } returns buildJsonObject { put("state", "failed") }
        val registry = OperationRegistry()
        SelfUpdateOperations(updater).register(registry)
        val result = registry.get(SelfUpdateOperations.STATUS)!!.handler.invoke(OperationInvocation("read", buildJsonObject {}))
        assertFalse(result.success)
        verify(exactly = 0) { updater.start(any(), any()) }
    }
}
