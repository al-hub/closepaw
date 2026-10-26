package ai.closepaw.platform

import org.junit.Assert.*
import org.junit.Test

class InputTypePrivacyPolicyTest {
    @Test fun passwordEditorIsSensitiveEvenBeforeImeWindowExists() {
        for(type in listOf(0x81,0x91,0xe1,0x12,0x80081)) assertTrue(InputTypePrivacyPolicy.password(type))
        for(type in listOf(0,1,2,0x80090,0x80001)) assertFalse(InputTypePrivacyPolicy.password(type))
    }
    @Test fun sensitiveTransitionCannotHideBetweenMatchingEndpointStates() {
        assertTrue(InputTypePrivacyPolicy.sameGeneration(0,0))
        assertFalse(InputTypePrivacyPolicy.sameGeneration(0,2)) // password start+finish
        assertFalse(InputTypePrivacyPolicy.sameGeneration(2,4)) // restart as normal
        assertFalse(InputTypePrivacyPolicy.sameGeneration(-1,-1))
        assertFalse(InputTypePrivacyPolicy.sameGeneration(0,null))
    }

}
