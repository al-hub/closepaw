package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class KeyboardScanDiagnosticsTest {
    @Test fun diagnosticCandidatesDoNotGrantKeyAuthorityOrExposeSuggestions() {
        val scan = KeyboardScanDiagnostics()
        scan.record("a", "", "android.widget.Button", true, true, true, false, false)
        scan.record("private suggestion", "private text", "android.widget.Button", true, true, true, false, false)
        scan.record("token=private", "", "android.widget.Button", true, true, true, true, false)
        scan.record("b", "", "android.widget.Button", false, true, true, true, true)
        val result = scan.json()
        assertEquals(4, result.getValue("visited").jsonPrimitive.int)
        assertEquals(3, result.getValue("visible").jsonPrimitive.int)
        assertEquals(0, result.getValue("recognized_keys").jsonPrimitive.int)
        assertEquals(1, result.getValue("candidates").jsonArray.size)
        assertFalse(result.toString().contains("private"))
        assertNull(KeyboardKeyPolicy.identify("a", "", "android.widget.Button", true, false))
    }
    @Test fun candidateOutputIsBounded() {
        val scan = KeyboardScanDiagnostics()
        repeat(100) { scan.record("a", "", "Button", true, true, true, false, false) }
        assertEquals(40, scan.json().getValue("candidates").jsonArray.size)
    }
    @Test fun missingClickFlagsAreDiagnosedWithoutMakingAnActionableKey() {
        val scan = KeyboardScanDiagnostics()
        scan.record("ㄱ", "", "KeyboardKey", true, true, false, false, false)
        val candidate = scan.json().getValue("candidates").jsonArray.single().jsonObject
        assertFalse(candidate.getValue("clickable").jsonPrimitive.boolean)
        assertFalse(candidate.getValue("recognized").jsonPrimitive.boolean)
        assertNull(KeyboardKeyPolicy.identify("ㄱ", "", "KeyboardKey", false, false))
        assertEquals(1, scan.json().getValue("classes").jsonObject.getValue("KeyboardKey").jsonPrimitive.int)
    }
}
