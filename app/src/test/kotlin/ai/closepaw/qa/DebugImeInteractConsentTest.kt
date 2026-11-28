package ai.closepaw.qa

import ai.closepaw.chatgpt.control.RuntimeControlSettings
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugImeInteractConsentTest {
    private fun config(ime: String? = null, other: String = "deny") = buildJsonObject {
        put("node_budget", 4096)
        put("output_limit", 50)
        put("apps", buildJsonObject {
            put("example.other", other)
            if (ime != null) put(DebugImeInteractConsent.SAMSUNG_IME, ime)
        })
    }

    @Test fun grantIsExactAndPreservesExistingPoliciesAndBudgets() {
        val before = config()
        val after = requireNotNull(DebugImeInteractConsent.nextConfig(before, grant = true))
        assertTrue(RuntimeControlSettings.validate(after))
        assertEquals("interact", after.getValue("apps").jsonObject.getValue(
            DebugImeInteractConsent.SAMSUNG_IME).jsonPrimitive.content)
        assertEquals("deny", after.getValue("apps").jsonObject.getValue("example.other").jsonPrimitive.content)
        assertEquals(before.getValue("node_budget"), after.getValue("node_budget"))
        assertEquals(before.getValue("output_limit"), after.getValue("output_limit"))
        assertNull(before.getValue("apps").jsonObject[DebugImeInteractConsent.SAMSUNG_IME])
    }

    @Test fun priorExplicitDenyOrReadIsNotOverwritten() {
        assertNull(DebugImeInteractConsent.nextConfig(config("deny"), grant = true))
        assertNull(DebugImeInteractConsent.nextConfig(config("read"), grant = true))
    }

    @Test fun explicitGrantIsIdempotentAndRevokeOnlyRemovesTarget() {
        val granted = requireNotNull(DebugImeInteractConsent.nextConfig(config(), grant = true))
        assertEquals(granted, DebugImeInteractConsent.nextConfig(granted, grant = true))
        val revoked = requireNotNull(DebugImeInteractConsent.nextConfig(granted, grant = false))
        assertEquals(config(), revoked)
        assertNull(DebugImeInteractConsent.nextConfig(config(), grant = false))
        assertNull(DebugImeInteractConsent.nextConfig(config("read"), grant = false))
    }

    @Test fun otherAppSettingsCannotBeBroadenedAndFullListRefusesGrant() {
        val before = config(other = "read")
        val after = requireNotNull(DebugImeInteractConsent.nextConfig(before, grant = true))
        assertEquals("read", after.getValue("apps").jsonObject.getValue("example.other").jsonPrimitive.content)
        val full = buildJsonObject {
            put("node_budget", 4096); put("output_limit", 50)
            put("apps", buildJsonObject {
                for (i in 1..64) put("example.app$i", "deny")
            })
        }
        assertNull(DebugImeInteractConsent.nextConfig(full, grant = true))
    }

    @Test fun malformedConfigurationIsRejectedWithoutChangingIt() {
        val malformed = JsonObject(config().toMutableMap().apply { put("output_limit", JsonPrimitive(999)) })
        assertNull(DebugImeInteractConsent.nextConfig(malformed, grant = true))
    }
}
