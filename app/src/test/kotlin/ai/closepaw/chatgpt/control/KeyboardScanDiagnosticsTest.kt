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
    @Test fun caseSwitchEvidenceCountsFullScanNotOnlyFirstFortyCandidates() {
        val scan = KeyboardScanDiagnostics()
        repeat(45) { scan.record("a", "", "android.widget.TextView", true, true, false, false, false) }
        scan.record("Shift", "", "android.view.ViewGroup", true, true, false, false, false)
        scan.record("⇧", "", "android.view.ViewGroup", true, true, false, false, false)
        scan.record("Caps lock", "", "android.widget.Button", true, true, true, true, true)
        val result = scan.json()
        assertEquals(40, result.getValue("candidates").jsonArray.size)
        assertEquals(3, result.getValue("case_switch_semantic_nodes").jsonPrimitive.int)
        assertEquals(1, result.getValue("case_switch_native_recognized").jsonPrimitive.int)
        assertNull(KeyboardKeyPolicy.identify("Shift", "", "android.view.ViewGroup", false, false))
        assertTrue(result.getValue("diagnostic_only").jsonPrimitive.boolean)
    }

    @Test fun missingCaseSwitchLabelIsNotGuessedFromAnAnonymousWrapper() {
        val scan = KeyboardScanDiagnostics()
        scan.record("", "", "android.view.ViewGroup", true, true, false, false, false)
        scan.record("q", "", "android.widget.TextView", true, true, false, false, false)
        val result = scan.json()
        assertEquals(0, result.getValue("case_switch_semantic_nodes").jsonPrimitive.int)
        assertEquals(0, result.getValue("case_switch_native_recognized").jsonPrimitive.int)
        assertEquals(0, result.getValue("text_entry_keys").jsonPrimitive.int)
    }

    @Test fun requestedHangulKeyCountIgnoresFortyEntryPresentationCap() {
        val scan = KeyboardScanDiagnostics()
        repeat(45) { scan.record("a", "", "Button", true, true, true, false, false) }
        scan.record("ㄱ", "", "KeyboardKey", true, true, false, false, false)
        scan.record("ㄱ", "", "KeyboardKey", true, false, false, false, false)
        scan.record("ㅏ", "", "KeyboardKey", false, true, false, true, true)
        scan.record("ㄴ", "", "KeyboardKey", true, true, true, true, true)
        val data = scan.json()
        assertEquals(40, data.getValue("candidates").jsonArray.size)
        assertEquals(7, data.getValue("candidate_list_omitted").jsonPrimitive.int)
        val stats = data.getValue("tracked_key_counts").jsonObject
        val giyeok = stats.getValue("ㄱ").jsonObject
        assertEquals(2, giyeok.getValue("semantic_seen").jsonPrimitive.int)
        assertEquals(1, giyeok.getValue("disabled").jsonPrimitive.int)
        assertEquals(0, giyeok.getValue("native_recognized").jsonPrimitive.int)
        assertEquals(1, stats.getValue("ㅏ").jsonObject.getValue("semantic_seen").jsonPrimitive.int)
        assertEquals(0, stats.getValue("ㅏ").jsonObject.getValue("visible").jsonPrimitive.int)
        assertEquals(1, stats.getValue("ㄴ").jsonObject.getValue("native_recognized").jsonPrimitive.int)
        assertFalse(data.toString().contains("private suggestion"))
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
