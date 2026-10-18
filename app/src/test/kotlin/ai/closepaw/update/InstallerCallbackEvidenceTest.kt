package ai.closepaw.update

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InstallerCallbackEvidenceTest {
    @Test fun confirmationHandlerNeverProvesWarningOwnership() {
        val evidence = InstallerCallbackEvidence.build(-1, null, "android.intent.action.CONFIRM_INSTALL",
            "com.android.packageinstaller/.InstallActivity", "com.android.packageinstaller", 42L)
        assertEquals("com.android.packageinstaller", evidence["confirmation_resolved_package"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, evidence["warning_owner"])
        assertEquals(-1, evidence["status"]!!.jsonPrimitive.int)
        assertEquals(42L, evidence["observed_at_ms"]!!.jsonPrimitive.long)
    }
    @Test fun untrustedMetadataCannotLeakUrisOrMultilineMessages() {
        val evidence = InstallerCallbackEvidence.build(1, "com.example.verifier", "content://private/apk?token=secret",
            "x".repeat(257), "private\nmessage", 1L)
        assertFalse(evidence.containsKey("confirmation_action"))
        assertFalse(evidence.containsKey("confirmation_component"))
        assertFalse(evidence.containsKey("confirmation_resolved_package"))
        assertEquals("com.example.verifier", evidence["related_package"]!!.jsonPrimitive.content)
        assertFalse(evidence.toString().contains("secret"))
    }
}
