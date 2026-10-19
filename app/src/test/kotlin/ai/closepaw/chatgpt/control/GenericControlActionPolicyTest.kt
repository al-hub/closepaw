package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class GenericControlActionPolicyTest {
    private val frame = ControlFrame("example.app", 4, "same", emptyList(), truncated = true)
    @Test fun onlyHomeAndAppLaunchAreIndependentOfNodeCompleteness() {
        for (action in listOf("home", "open_app")) assertNull(GenericControlActionPolicy.refusal(frame, frame, action))
        for (action in listOf("click", "tap", "type", "scroll", "long_press", "back", "unknown"))
            assertEquals("truncated_observation", GenericControlActionPolicy.refusal(frame, frame, action))
    }
    @Test fun everyNavigationRetainsPolicyAndForegroundChecks() {
        for (action in listOf("home", "open_app")) {
            assertEquals("secure_window", GenericControlActionPolicy.refusal(frame, frame.copy(reason = "secure_window"), action))
            assertEquals("blocked_app", GenericControlActionPolicy.refusal(frame, frame.copy(reason = "blocked_app"), action))
            assertEquals("device_locked", GenericControlActionPolicy.refusal(frame, frame.copy(reason = "device_locked"), action))
            assertEquals("screen_changed", GenericControlActionPolicy.refusal(frame, frame.copy(windowId = 5), action))
            assertEquals("screen_changed", GenericControlActionPolicy.refusal(frame, frame.copy(packageName = "other.app"), action))
        }
    }
    @Test fun globalNavigationIgnoresOnlyOrdinaryContentRefresh() {
        for (action in listOf("home", "open_app"))
            assertNull(GenericControlActionPolicy.refusal(frame, frame.copy(fingerprint = "new-content"), action))
    }
    @Test fun nodeActionsAndBackStillRequireTheObservedContent() {
        val complete = frame.copy(truncated = false)
        for (action in listOf("click", "tap", "long_press", "type", "scroll", "back"))
            assertEquals("screen_changed", GenericControlActionPolicy.refusal(complete, complete.copy(fingerprint = "new-content"), action))
    }

    @Test fun tapRetainsSecurityAndIdentityRefusalsBeforeGeometry() {
        val complete = frame.copy(truncated = false, touchContext = "same-window")
        for (reason in listOf("secure_window", "blocked_app", "device_locked", "authentication_boundary", "local_agent_busy"))
            assertEquals(reason, GenericControlActionPolicy.refusal(complete, complete.copy(reason = reason), "tap"))
        assertEquals("screen_changed", GenericControlActionPolicy.refusal(complete, complete.copy(windowId = 5), "tap"))
        assertEquals("screen_changed", GenericControlActionPolicy.refusal(complete, complete.copy(packageName = "foreign.app"), "tap"))
    }
    @Test fun unchangedPopupCannotBeReusedAfterItsForegroundOwnerChanges() {
        val popup = frame.copy(truncated = false, foregroundWindowId = 1, touchContext = "same")
        for (action in listOf("click", "tap", "back", "home", "open_app"))
            assertEquals("screen_changed", GenericControlActionPolicy.refusal(popup, popup.copy(foregroundWindowId = 2), action))
    }

}
