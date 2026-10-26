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
            if (!check()) {
                var metadata="service_missing"
                instrumentation.runOnMainSync { metadata=AgentService.instance?.inputMethod?.let { im ->
                    val info=im.currentInputEditorInfo
                    "started=${im.currentInputStarted},package=${info?.packageName},field=${info?.fieldId},type=${info?.inputType}"
                } ?: "input_method_unavailable" }
                fail("Native binding unavailable: $metadata")
            }
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
            val port = AndroidControlPort(service, runtime = settings.runtimeControl) { settings.genericControlEnabled }
            val control = GenericControl(port, { settings.genericControlEnabled })
            var fixtureStage="initial_terminal"
            suspend fun settled(requireIme: Boolean = true): ai.closepaw.chatgpt.operations.OperationResult {
                var previous=port.capture()
                var stableSince=SystemClock.elapsedRealtime()
                val deadline=stableSince+15_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    kotlinx.coroutines.delay(200)
                    val next=port.capture()
                    // Two early identical app frames can precede the delayed IME launch.
                    // Wait for the actual IME window and a continuous stable interval;
                    // never retry an action or loosen production freshness checks.
                    val stable=(!requireIme || next.keyboard != null) && GenericControlActionPolicy.refusal(previous,next,"terminal_input") == null && previous.touchContext == next.touchContext
                    if (!stable) stableSince=SystemClock.elapsedRealtime()
                    if (stable && SystemClock.elapsedRealtime()-stableSince >= 1_200)
                        return control.observe()
                    previous=next
                }
                fail("Fixture screen did not settle at $fixtureStage: ${previous.reason}")
                return control.observe()
            }
            val observed = settled(); assertTrue(observed.toString(), observed.success)
            val target = observed.data.getValue("nodes").jsonArray.map { it.jsonObject }.single { it["terminal_input_supported"]?.jsonPrimitive?.booleanOrNull == true }
            val input = buildJsonObject { put("action", "terminal_input"); put("session_id", session.id)
                put("observation_id", observed.data.getValue("observation_id")); put("node_id", target.getValue("node_id"))
                put("terminal_authorized", true); put("text", "한글 A1!") }
            val result = control.action(input); assertTrue("$result; ${ControlDiagnostics.snapshot()}", result.success)
            assertFalse(control.action(input).success)
            var priorGeneration=0L
            instrumentation.runOnMainSync { priorGeneration=TerminalInputBinding.capture(service)?.generation ?: 0 }
            launch(false)
            await {
                var ready=false
                instrumentation.runOnMainSync {
                    val binding=TerminalInputBinding.capture(service)
                    val root=service.rootInActiveWindow
                    val focus=root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                    try { ready=binding != null && binding.generation > priorGeneration && focus != null &&
                        binding.evidence(focus)?.echo?.let(TerminalInputPolicy::emptyPrompt) == true }
                    finally { if(focus !== root) focus?.recycle(); root?.recycle() }
                }
                ready
            }
            val pending = settled(); assertTrue(pending.success)
            val terminalNode = pending.data.getValue("nodes").jsonArray.map { it.jsonObject }.single { it["terminal_input_supported"]?.jsonPrimitive?.booleanOrNull == true }
            val command = "printf fixture_ok"
            val typed = control.action(buildJsonObject { put("action","terminal_input");put("session_id",session.id);put("terminal_authorized",true)
                put("text",command);put("observation_id",pending.data.getValue("observation_id"));put("node_id",terminalNode.getValue("node_id")) })
            assertTrue(typed.toString(), typed.success)
            val submitObservation = settled(); assertTrue(submitObservation.success)
            val submitted = control.action(buildJsonObject { put("action","terminal_submit");put("session_id",session.id);put("terminal_authorized",true);put("submit_authorized",true)
                put("text",command);put("expected_output","fixture_ok");put("observation_id",submitObservation.data.getValue("observation_id"));put("node_id",terminalNode.getValue("node_id")) })
            assertTrue(submitted.toString(), submitted.success)
            val visual = control.observe(includeImage=true); assertTrue(visual.toString(),visual.success)
            val visualMeta=visual.data.getValue("visual").jsonObject
            val osBounds=visual.data.getValue("nodes").jsonArray.map { it.jsonObject }.single { it["resource_id"]?.jsonPrimitive?.content == "com.termux:id/terminal_view" }.getValue("bounds").jsonPrimitive.content.split(' ').map{it.toInt()}
            val window=visualMeta.getValue("window_bounds").jsonPrimitive.content.split(' ').map{it.toInt()}
            val ix=((osBounds[0]+80-window[0]).toLong()*visualMeta.getValue("image_width").jsonPrimitive.int/(window[2]-window[0])).toInt()
            val iy=((osBounds[1]+160-window[1]).toLong()*visualMeta.getValue("image_height").jsonPrimitive.int/(window[3]-window[1])).toInt()
            val tapped=control.action(buildJsonObject { put("action","visual_tap");put("session_id",session.id);put("observation_id",visual.data.getValue("observation_id"));put("visual_id",visualMeta.getValue("visual_id"));put("visual_target_confirmed",true)
                put("image_rect",JsonArray(listOf(ix-2,iy-2,ix+2,iy+2).map{JsonPrimitive(it)})) })
            assertTrue(tapped.toString(),tapped.success)
            // Positive display capture uses the fixture app alone. The emulator's
            // default IME exposes an authentication boundary when its whole tree
            // is admitted; do not relax that production scan to make QA pass.
            // Hide the observed IME once, using only this throwaway emulator.
            fixtureStage="display_app_only"
            val beforeDisplay=port.capture();assertNotNull(beforeDisplay.keyboard)
            shell("input keyevent 4")
            val imeGoneDeadline=SystemClock.elapsedRealtime()+10_000
            var displayFrame=port.capture()
            while(displayFrame.keyboard!=null && SystemClock.elapsedRealtime()<imeGoneDeadline) {
                kotlinx.coroutines.delay(200);displayFrame=port.capture()
            }
            assertNull("Fixture IME did not hide",displayFrame.keyboard)
            assertTrue(settled(requireIme=false).success)
            val cached=control.observe(includeImage=true,imageScope="display");assertTrue(cached.toString(),cached.success)
            val displayMeta=cached.data.getValue("visual").jsonObject
            assertEquals(JsonPrimitive("display"),displayMeta["scope"])
            assertEquals(JsonPrimitive(0),displayMeta["source_display_id"])
            assertTrue(displayMeta.getValue("source_width").jsonPrimitive.int>0)
            val image=VisualImageCache.imageFor(buildJsonObject {put("data",cached.data)})!!
            val decoded=android.graphics.BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.size)
            assertEquals(image.width,decoded.width);assertEquals(image.height,decoded.height)
            // The passive full-screen decoration must retain the fixture's
            // light interior while masking its rendered perimeter/controls.
            val center=decoded.getPixel(decoded.width/2,decoded.height/3)
            assertTrue("Trusted transparent decoration hid the app interior",android.graphics.Color.red(center)>180)
            decoded.recycle()
            val task=buildJsonObject { put("result",buildJsonObject { put("data",cached.data) }) }
            assertNotNull(VisualImageCache.imageFor(task))
            try {
                shell("input keyevent 26")
                await { !service.getSystemService(android.os.PowerManager::class.java).isInteractive }
                assertNull("Locked device must never disclose an earlier screenshot",VisualImageCache.imageFor(task))
            } finally {
                shell("input keyevent 224")
                shell("wm dismiss-keyguard")
            }
            await { service.getSystemService(android.os.PowerManager::class.java).isInteractive &&
                !service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked }
            assertNull("Unlock must not resurrect a revoked screenshot",VisualImageCache.imageFor(task))
            fixtureStage="protected_editor"
            launch(true)
            await {
                var denied = false
                instrumentation.runOnMainSync { denied = service.inputMethod?.currentInputEditorInfo?.let { it.packageName == "com.termux" && it.inputType != 0 } == true }
                denied
            }
            // A display request with the unreadable password IME must never
            // disclose an image. Retain the original official-connection refusal.
            val protectedDisplay=control.observe(includeImage=true,imageScope="display")
            assertFalse(protectedDisplay.toString(),protectedDisplay.success)
            assertNull(VisualImageCache.imageFor(buildJsonObject {put("data",protectedDisplay.data)}))
            // Unlock/restartInput may remove the IME window. Re-observe only after
            // that OS transition settles; never bypass the secure-window guard.
            val protected = settled(requireIme=false)
            assertTrue(protected.toString(),protected.success)
            run {
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
