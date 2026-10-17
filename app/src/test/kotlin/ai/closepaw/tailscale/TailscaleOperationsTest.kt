package ai.closepaw.tailscale

import ai.closepaw.chatgpt.operations.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TailscaleOperationsTest {
    @Test fun discoveryAndReadOnlyDiagnosisWorkWithoutTermuxOrControlPermission() = runBlocking {
        val registry = OperationRegistry()
        val platform = FakePlatform()
        TailscaleOperations(TailscaleRecovery(platform, { false }), { false }).register(registry)
        assertEquals(setOf(TailscaleOperations.STATUS, TailscaleOperations.ENSURE), registry.all().map { it.descriptor.id }.toSet())
        val query = registry.get(TailscaleOperations.STATUS)!!
        assertTrue(query.descriptor.readOnly)
        assertTrue(query.descriptor.safeReadiness().ready)
        assertFalse(registry.get(TailscaleOperations.ENSURE)!!.descriptor.safeReadiness().ready)
        val result = query.handler.invoke(OperationInvocation("read", buildJsonObject {}))
        assertTrue(result.success)
        assertEquals("app_not_installed", result.data.getValue("state").jsonPrimitive.content)
        assertEquals(0, platform.connects)
    }

    @Test fun mutationRequiresExecuteAndLocalPermissionAndInvalidInputHasNoSideEffect() {
        val registry = OperationRegistry()
        var enabled = false
        val platform = FakePlatform()
        TailscaleOperations(TailscaleRecovery(platform, { enabled }), { enabled }).register(registry)
        val file = File.createTempFile("tailscale-ledger", ".json").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val dispatcher = OperationDispatcher(registry, OperationTaskStore(file), scope,
                executeAllowed = { enabled }, operationAllowed = { enabled && it == TailscaleOperations.ENSURE })
            val args = arguments(registry.get(TailscaleOperations.ENSURE)!!.descriptor, buildJsonObject { put("host", "pc.ts.net") })
            assertEquals("write_operation_requires_execute", dispatcher.query(args)["error_code"]?.jsonPrimitive?.content)
            assertEquals("permission_denied", dispatcher.execute(args)["error_code"]?.jsonPrimitive?.content)
            enabled = true
            val invalid = JsonObject(args + ("input" to buildJsonObject { put("host", "pc.ts.net"); put("timeout_ms", 30000) }))
            assertEquals("invalid_input", dispatcher.execute(invalid)["error_code"]?.jsonPrimitive?.content)
            assertEquals(0, platform.connects)
        } finally { scope.cancel(); file.delete() }
    }

    @Test fun dangerousHostSyntaxAndPortWithoutHostFailBeforePlatformUse() = runBlocking {
        val registry = OperationRegistry()
        val platform = FakePlatform()
        TailscaleOperations(TailscaleRecovery(platform, { true }), { true }).register(registry)
        val query = registry.get(TailscaleOperations.STATUS)!!
        for (input in listOf(buildJsonObject { put("host", "x; touch /tmp/file") }, buildJsonObject { put("port", 22) })) {
            assertFalse(query.handler.invoke(OperationInvocation("invalid", input)).success)
        }
        assertEquals(0, platform.observations)
        assertEquals(0, platform.connects)
    }

    @Test fun reachableTcpDoesNotClaimSshOrOriginalCommandCompletion() = runBlocking {
        val registry = OperationRegistry()
        val platform = FakePlatform().apply { reachable = true }
        TailscaleOperations(TailscaleRecovery(platform, { true }), { true }).register(registry)
        val result = registry.get(TailscaleOperations.ENSURE)!!.handler.invoke(OperationInvocation("tcp", buildJsonObject { put("host", "pc.ts.net") }))
        assertTrue(result.success)
        assertEquals("private_tcp_only", result.data["verification_scope"]?.jsonPrimitive?.content)
        assertFalse(result.data.getValue("original_command_replayed").jsonPrimitive.boolean)
        assertEquals("unattributed", result.data["vpn_owner"]?.jsonPrimitive?.content)
        assertEquals(0, platform.connects)
    }

    @Test fun metadataIsBoundedAndExcludesNetworkIdentityAndShellContent() {
        val file = File.createTempFile("tailscale-diagnostic", ".json").apply { delete() }
        try {
            val diagnostics = TailscaleDiagnostics(file)
            repeat(25) { diagnostics.record(TailscaleOutcome("target_reachable", TailscaleLocalState(versionName = "private-account-text"), TargetProbe("reachable"), true)) }
            val snapshot = TailscaleDiagnostics(file).snapshot()
            val events = snapshot.getValue("events").jsonArray
            assertEquals(20, events.size)
            assertFalse(snapshot.toString().contains("private-account-text"))
            assertEquals(setOf("at_ms", "state", "connect_requested", "target_reachable", "probe_reason"), events.last().jsonObject.keys)
        } finally { file.delete() }
    }

    @Test fun tailscalePermissionCannotCancelAnUnrelatedShellJob() {
        val registry = OperationRegistry()
        var cancelled = false
        registry.register(OperationDescriptor("linux.shell.exec", "Shell", inputSchema = buildJsonObject { put("type", "object") }, readOnly = false), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation) = OperationResult()
            override suspend fun cancel(taskId: String): Boolean { cancelled = true; return true }
        })
        val file = File.createTempFile("tailscale-permission", ".json").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = OperationTaskStore(file)
            store.put(buildJsonObject { put("task_id", "shell-task"); put("operation_id", "linux.shell.exec"); put("state", "running") })
            val dispatcher = OperationDispatcher(registry, store, scope, operationAllowed = { it == TailscaleOperations.ENSURE }, executeAllowed = { true })
            val reply = dispatcher.cancelTask(buildJsonObject { put("task_id", "shell-task") })
            assertEquals("permission_denied", reply["error_code"]?.jsonPrimitive?.content)
            assertFalse(cancelled)
        } finally { scope.cancel(); file.delete() }
    }

    private fun arguments(descriptor: OperationDescriptor, input: JsonObject) = buildJsonObject {
        put("operation_id", descriptor.id); put("operation_version", descriptor.version)
        put("schema_hash", descriptor.schemaHash); put("request_id", "test-request"); put("input", input)
    }

    private class FakePlatform : TailscalePlatform {
        var connects = 0
        var observations = 0
        var reachable = false
        override suspend fun observe(): TailscaleLocalState { observations++; return TailscaleLocalState(installed = false) }
        override suspend fun probe(target: PrivateTarget, timeoutMs: Int) = TargetProbe(if (reachable) "reachable" else "target_unreachable")
        override suspend fun connect(): ConnectDispatch { connects++; return ConnectDispatch(true) }
    }
}
