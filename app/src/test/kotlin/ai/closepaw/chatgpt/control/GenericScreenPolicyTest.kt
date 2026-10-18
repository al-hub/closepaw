package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class GenericScreenPolicyTest {
    @Test fun blockedTierCannotBeSoftenedByVerifiedHostOrLauncher() {
        assertEquals("blocked_app", GenericScreenPolicy.refusal(true, false, true, true, true, true))
    }
    @Test fun sensitiveNodesCannotBeExposedInANormalApp() {
        assertEquals("authentication_boundary", GenericScreenPolicy.refusal(false, true, true, false, false, true))
    }
    @Test fun googleSearchIsNotVerifiedGeminiEvenWithANormalOverride() {
        assertEquals("blocked_or_cautious_app", GenericScreenPolicy.refusal(false, false, true, true, false, true))
    }
    @Test fun knownOrdinaryAppAndVerifiedHostRemainAvailable() {
        assertNull(GenericScreenPolicy.refusal(false, false, true, false, false, true))
        assertNull(GenericScreenPolicy.refusal(false, false, false, true, true, true))
    }
}
