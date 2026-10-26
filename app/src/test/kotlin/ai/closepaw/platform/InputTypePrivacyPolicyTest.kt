package ai.closepaw.platform

import org.junit.Assert.*
import org.junit.Test

class InputTypePrivacyPolicyTest {
    @Test fun passwordEditorIsSensitiveEvenBeforeImeWindowExists() {
        for(type in listOf(0x81,0x91,0xe1,0x12,0x80081)) assertTrue(InputTypePrivacyPolicy.password(type))
        for(type in listOf(0,1,2,0x80090,0x80001)) assertFalse(InputTypePrivacyPolicy.password(type))
    }
}
