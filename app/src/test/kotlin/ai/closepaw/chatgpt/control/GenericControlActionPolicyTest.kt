package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class GenericControlActionPolicyTest {
    private val frame = ControlFrame("example.app", 4, "same", emptyList(), truncated = true)
    @Test fun onlyHomeAndAppLaunchAreIndependentOfNodeCompleteness() {
        for (action in listOf("home", "open_app")) assertNull(GenericControlActionPolicy.refusal(frame, frame, action))
        for (action in listOf("click", "type", "scroll", "long_press", "back", "unknown"))
            assertEquals("truncated_observation", GenericControlActionPolicy.refusal(frame, frame, action))
    }
    @Test fun everyNavigationRetainsPolicyAndForegroundChecks() {
        for (action in listOf("home", "open_app")) {
            assertEquals("secure_window", GenericControlActionPolicy.refusal(frame, frame.copy(reason = "secure_window"), action))
            assertEquals("blocked_app", GenericControlActionPolicy.refusal(frame, frame.copy(reason = "blocked_app"), action))
            assertEquals("device_locked", GenericControlActionPolicy.refusal(frame, frame.copy(reason = "device_locked"), action))
            assertEquals("screen_changed", GenericControlActionPolicy.refusal(frame, frame.copy(windowId = 5), action))
            assertEquals("screen_changed", GenericControlActionPolicy.refusal(frame, frame.copy(fingerprint = "changed"), action))
        }
    }
}
