package ai.closepaw.qa

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.McpExecutionSettings
import ai.closepaw.chatgpt.control.*
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android accessibility InputMethod binding to a separate noneditable fixture app. */
@RunWith(AndroidJUnit4::class)
class TerminalInputConnectionTest {
    @Test fun officialInputConnectionCommitsExactEchoAndRefusesPasswordBinding() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) { automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        shell("settings put secure enabled_accessibility_services ai.closepaw/ai.closepaw.app.AgentService")
        shell("settings put secure accessibility_enabled 1")
        fun await(check: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("Native input binding must be available", check())
        }
        await { AgentService.instance != null }
        val service = AgentService.instance!!
        val settings = McpExecutionSettings(service)
        val prefs = service.getSharedPreferences("mcp_execution", 0)
        val before = prefs.all.toMap()
        val session = service.remoteControlSession
        try {
            prefs.edit().putBoolean("generic_control_enabled", true).putString("runtime_control_v1",
                """{"revision":1,"config":{"node_budget":4096,"output_limit":80,"apps":{"com.termux":"interact"}}}""").commit()
            instrumentation.runOnMainSync { session.end(); assertTrue(session.start()) }
            fun launch(password: Boolean) = ApplicationProvider.getApplicationContext<android.content.Context>().startActivity(
                Intent().setClassName("com.termux", "com.termux.TerminalFixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("password", password))
            launch(false)
            await {
                var ready = false
                instrumentation.runOnMainSync { ready = TerminalInputBinding.capture(service) != null }
                ready
            }
            val control = GenericControl(AndroidControlPort(service, runtime = settings.runtimeControl) { settings.genericControlEnabled }, { settings.genericControlEnabled })
            val observed = control.observe(); assertTrue(observed.toString(), observed.success)
            val target = observed.data.getValue("nodes").jsonArray.map { it.jsonObject }.single { it["terminal_input_supported"]?.jsonPrimitive?.booleanOrNull == true }
            val input = buildJsonObject { put("action", "terminal_input"); put("session_id", session.id)
                put("observation_id", observed.data.getValue("observation_id")); put("node_id", target.getValue("node_id"))
                put("terminal_authorized", true); put("text", "한글 A1!") }
            val result = control.action(input); assertTrue(result.toString(), result.success)
            assertFalse(control.action(input).success)
            launch(true)
            await {
                var denied = false
                instrumentation.runOnMainSync { denied = service.inputMethod?.currentInputEditorInfo?.inputType != 0 }
                denied
            }
            val protected = control.observe()
            if (protected.success) {
                val node = protected.data.getValue("nodes").jsonArray.map { it.jsonObject }.single { it["resource_id"]?.jsonPrimitive?.content == "com.termux:id/terminal_view" }
                val refused = control.action(buildJsonObject { put("action", "terminal_input"); put("session_id", session.id)
                    put("observation_id", protected.data.getValue("observation_id")); put("node_id", node.getValue("node_id"))
                    put("terminal_authorized", true); put("text", "never-entered") })
                assertFalse(refused.success); assertEquals(JsonPrimitive(false), refused.data["action_attempted"])
            }
        } finally {
            instrumentation.runOnMainSync { session.end() }
            val edit = prefs.edit().clear()
            before.forEach { (k, v) -> when (v) { is Boolean -> edit.putBoolean(k, v); is String -> edit.putString(k, v); is Int -> edit.putInt(k, v); is Long -> edit.putLong(k, v) } }
            edit.commit(); shell("am force-stop com.termux")
        }
    }
}
