package ai.closepaw.chatgpt.control

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EmptyOwnedSurfacePolicyTest {
    private val empty = ControlFrame("example.app", 5, "full-scan", emptyList(),
        reason = "screen_unreadable", foregroundWindowId = 4, configurationRevision = 19)

    @Test fun onlyCompletelyScannedEmptyLinkedPopupCanBeRestrictedToNavigation() {
        for (frame in listOf(empty.copy(reason = "authentication_boundary"),
            empty.copy(reason = "blocked_app"), empty.copy(reason = "device_locked"),
            empty.copy(reason = "secure_window"), empty.copy(reason = "owned_window_root_changed"),
            empty.copy(truncated = true), empty.copy(windowId = 4),
            empty.copy(nodes = listOf(ControlNode(1)))))
            assertEquals(frame, EmptyOwnedSurfacePolicy.restrict(frame, true))
        assertEquals(empty, EmptyOwnedSurfacePolicy.restrict(empty, false))
    }

    @Test fun emptyPopupOffersNoUnderlyingNodesImageBackOrGestureButAllowsGuardedExit() = runBlocking {
        for (action in listOf("home", "open_app", "back", "click", "visual_tap")) {
            var effects = 0
            var frame = EmptyOwnedSurfacePolicy.restrict(empty, true)
            val port = object : ControlPort {
                override suspend fun capture() = frame
                override suspend fun apps(filter: String) = JsonArray(emptyList())
                override suspend fun perform(before: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
                    effects++
                    frame = ControlFrame(if (action == "open_app") "other.app" else "launcher", 8, "changed", emptyList())
                    return ControlEffect(true, true, "action_acknowledged")
                }
            }
            val control = GenericControl(port, { true })
            val observed = control.observe(includeImage = true)
            assertTrue("An empty owned popup must retain guarded exit navigation", observed.success)
            assertTrue(observed.data.getValue("security_scan_complete").jsonPrimitive.boolean)
            assertTrue(observed.data.getValue("navigation_only").jsonPrimitive.boolean)
            assertFalse(observed.data.getValue("node_actions_allowed").jsonPrimitive.boolean)
            assertEquals(listOf("home", "open_app"), observed.data.getValue("navigation_actions").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(observed.data.getValue("nodes").jsonArray.isEmpty())
            assertFalse(observed.data.containsKey("visual"))
            val result = control.action(buildJsonObject {
                put("observation_id", observed.data.getValue("observation_id"))
                put("action", action); put("node_id", 1); put("package_name", "other.app")
            })
            val navigation = action in setOf("home", "open_app")
            assertEquals(navigation, result.success)
            assertEquals(if (navigation) 1 else 0, effects)
        }
    }
}
