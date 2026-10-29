package ai.closepaw.qa

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.*
import ai.closepaw.chatgpt.control.AndroidControlPort
import ai.closepaw.chatgpt.operations.*
import ai.closepaw.termux.*
import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SelfMonitoringRuntimeTest {
    @Test fun deviceConsentConnectsRegistryRunnerGuardAndIndependentPostCheck() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) { automation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        } }
        shell("settings put secure enabled_accessibility_services ai.closepaw/ai.closepaw.app.AgentService")
        shell("settings put secure accessibility_enabled 1")
        suspend fun await(check: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!check() && SystemClock.elapsedRealtime() < deadline) delay(100)
            assertTrue("Monitoring fixture did not reach its expected state", check())
        }
        await { AgentService.instance != null }
        val service = AgentService.instance!!
        val prefs = service.getSharedPreferences("mcp_execution", 0)
        val before = prefs.all.toMap()
        val settings = McpExecutionSettings(service)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dispatches = AtomicInteger()
        val probes = AtomicInteger()
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val bridge = TermuxBridgeManager(object : TermuxCommandRunner {
            override suspend fun runShell(command: String, stdinBase64: String?, timeoutMs: Long): RunCommandResult = error("unguarded fixture dispatch")
            override suspend fun runShellGuarded(command: String, timeoutMs: Long, guard: (() -> Unit) -> Boolean): RunCommandResult {
                assertTrue(guard { dispatches.incrementAndGet(); ready.set(true) })
                return RunCommandResult("", "", 0)
            }
        }, TermuxHealthProbe { probes.incrementAndGet(); if (ready.get()) HealthProbe.Ready else HealthProbe.Unavailable },
            TermuxInstallProbe { TermuxInstallState.Available }, { error("no payload deployment permitted") }, scope)
        try {
            SelfMonitoringAuthority.stop()
            settings.enabled = true; settings.genericControlEnabled = true
            instrumentation.runOnMainSync { service.remoteControlSession.end(); assertTrue(service.remoteControlSession.start()) }
            val registry = OperationRegistry()
            SelfMonitoringOperations(service, scope, settings, bridge).register(registry)
            val result = registry.get(SelfMonitoringOperations.START)!!.handler.invoke(OperationInvocation("monitor-ui-fixture", buildJsonObject {
                put("session_id", service.remoteControlSession.id); put("duration_seconds", 10)
                put("recovery", SelfMonitoringAuthority.ACTION)
            }))
            assertTrue(result.toString(), result.success)
            assertEquals("awaiting_consent", SelfMonitoringAuthority.snapshot()!!.phase)
            assertEquals(0, probes.get()); assertEquals(0, dispatches.get())
            val port = AndroidControlPort(service) { true }
            assertEquals("device_monitoring_consent_required", port.capture().reason)
            var activity: SelfMonitoringConsentActivity? = null
            await {
                instrumentation.runOnMainSync {
                    activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<SelfMonitoringConsentActivity>().singleOrNull()
                }
                activity?.consentDialog?.isShowing == true
            }
            // Emulator-only button action. Production remote UI operations are
            // blocked above and cannot invoke this on-device approval path.
            instrumentation.runOnMainSync {
                val dialog = activity!!.consentDialog!!
                assertTrue(dialog.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }
            await { SelfMonitoringAuthority.snapshot()?.phase == "finished" }
            assertEquals(1, dispatches.get())
            assertEquals(3, probes.get()) // observation, final preflight, independent post-check
            val status = registry.get(SelfMonitoringOperations.STATUS)!!.handler.invoke(OperationInvocation("status", buildJsonObject {}))
            assertEquals(JsonPrimitive("ready"), status.data["post_check"])
            assertEquals(JsonPrimitive(true), status.data["recovery_attempted"])
            assertFalse(SelfMonitoringAuthority.approve("monitor-ui-fixture", SystemClock.elapsedRealtime()))
            instrumentation.runOnMainSync { service.remoteControlSession.end() }
            assertFalse(SelfMonitoringAuthority.dispatch("monitor-ui-fixture", { MonitoringRecoveryGate.State(SystemClock.elapsedRealtime(), true, true) }) {
                fail("No dispatch after end")
            })
        } finally {
            SelfMonitoringAuthority.stop(); scope.cancel()
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<SelfMonitoringConsentActivity>().forEach { it.finish() }
                service.remoteControlSession.end()
            }
            val edit = prefs.edit().clear()
            before.forEach { (k, v) -> when (v) {
                is Boolean -> edit.putBoolean(k, v); is String -> edit.putString(k, v)
                is Int -> edit.putInt(k, v); is Long -> edit.putLong(k, v)
            } }
            edit.commit()
        }
    }
}
