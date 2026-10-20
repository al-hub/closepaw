package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlResultPolicyTest {
    private val node = ControlNode(1, resourceId = "switch", bounds = "0 0 50 50", checkable = true)
    private val before = ControlFrame("settings", 1, "before", listOf(node))
    @Test fun hostedLaunchNeedsPositiveCompleteApplicationIdentity() {
        val input = buildJsonObject { put("package_name", "gemini.app") }
        val host = before.copy(packageName = "google.host", fingerprint = "host")
        assertFalse(ControlResultPolicy.verify("open_app", before, host, null, input).confirmed)
        val verified = host.copy(verifiedApplicationPackage = "gemini.app")
        assertTrue(ControlResultPolicy.verify("open_app", before, verified, null, input).confirmed)
        assertFalse(ControlResultPolicy.verify("open_app", before, verified.copy(truncated = true), null, input).confirmed)
        assertFalse(ControlResultPolicy.verify("open_app", before, verified.copy(reason = "secure_window"), null, input).confirmed)
        assertFalse(verified.matchesApplication("another.app"))
    }
    @Test fun unrelatedScreenChangeDoesNotProveToggle() {
        val result = ControlResultPolicy.verify("click", before, before.copy(fingerprint = "clock tick"), node, buildJsonObject {})
        assertFalse(result.confirmed)
        assertEquals("checked_state_unconfirmed", result.reason)
    }
    @Test fun checkedStateAndExactAppMustMatch() {
        assertTrue(ControlResultPolicy.verify("tap", before, before.copy(nodes = listOf(node.copy(checked = true))), node, buildJsonObject {}).confirmed)
        assertFalse(ControlResultPolicy.verify("tap", before, before.copy(packageName = "other", nodes = listOf(node.copy(checked = true))), node, buildJsonObject {}).confirmed)
        val input = buildJsonObject { put("package_name", "gemini") }
        assertFalse(ControlResultPolicy.verify("open_app", before, before, null, input).confirmed)
        assertTrue(ControlResultPolicy.verify("open_app", before, before.copy(packageName = "gemini"), null, input).confirmed)
    }
    @Test fun ambiguousOrIncompleteTargetsCannotVerify() {
        val checked = node.copy(checked = true)
        for (after in listOf(before.copy(nodes = listOf(checked, checked)), before.copy(truncated = true), before.copy(reason = "device_locked")))
            assertFalse(ControlResultPolicy.verify("click", before, after, node, buildJsonObject {}).confirmed)
    }
    @Test fun appSearchDoesNotRevokeOtherExactSelectionAndNeverGrantsPolicy() {
        val selections = ControlAppSelections()
        selections.add(listOf("gemini")); selections.add(listOf("notes"))
        assertNull(selections.refusal("gemini", true))
        assertEquals("app_launch_blocked", selections.refusal("gemini", false))
        assertEquals("app_selection_required", selections.refusal("unknown", true))
        selections.clear()
        assertEquals("app_selection_required", selections.refusal("gemini", true))
    }
}
