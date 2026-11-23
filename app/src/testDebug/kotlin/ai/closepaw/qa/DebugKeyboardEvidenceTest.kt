package ai.closepaw.qa

import ai.closepaw.chatgpt.control.ControlFrame
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugKeyboardEvidenceTest {
    private fun observed() = buildJsonObject {
        put("keyboard", buildJsonObject {
            put("scan", buildJsonObject {
                put("visited", 120)
                put("candidate_list_limit", 40)
                put("candidate_list_omitted", 9)
                put("tracked_key_counts", buildJsonObject {
                    put("ㄱ", buildJsonObject {
                        put("semantic_seen", 2)
                        put("visible", 2)
                        put("enabled", 1)
                        put("disabled", 1)
                        put("native_recognized", 0)
                    })
                })
            })
        })
        put("matched_node_count", 2)
        put("output_truncated", true)
        put("security_scan_complete", true)
        put("visual", buildJsonObject { put("scope", "keyboard"); put("secret", "PRIVATE_PIXEL_PATH") })
        put("keyboard_preflight", buildJsonObject {
            put("status", "BLOCKED")
            put("reason_code", "physical_keys_not_unique")
        })
        put("nodes", buildJsonArray {
            add(buildJsonObject {
                put("keyboard_key", "ㄱ"); put("enabled", true); put("node_id", 11)
                put("text", "PRIVATE_EDITOR_CONTENT")
                put("bounds", "1 2 3 4")
                put("keyboard_image_candidate", false)
            })
        })
        put("url", "https://private.invalid")
    }

    @Test fun candidateAuditSeparatesFullScanFromLimitedReturnedNodes() {
        val result = DebugKeyboardEvidence.fromObservation(observed(), true, listOf("ㄱ"))
        assertEquals(120, result.getValue("full_scan_visited").jsonPrimitive.int)
        assertEquals(9, result.getValue("scan_list_omitted").jsonPrimitive.int)
        assertEquals(2, result.getValue("matched_full_frame_count").jsonPrimitive.int)
        assertEquals(1, result.getValue("returned_candidate_count").jsonPrimitive.int)
        assertTrue(result.getValue("presentation_limited").jsonPrimitive.boolean)
        assertTrue(result.getValue("visual_scope_proof_present").jsonPrimitive.boolean)
        assertFalse(result.getValue("pixel_role_certified").jsonPrimitive.boolean)
        assertFalse(result.getValue("native_touch_authorized_by_diagnostic").jsonPrimitive.boolean)
        assertEquals(1, result.getValue("full_scan_key_counts").jsonObject
            .getValue("ㄱ").jsonObject.getValue("disabled").jsonPrimitive.int)
        assertEquals("physical_keys_not_unique", result.getValue("structural_preflight_reason").jsonPrimitive.content)
    }

    @Test fun auditNeverPersistsNodeIdsPixelsPrivateContentOrUntrustedLabels() {
        val result = DebugKeyboardEvidence.fromObservation(observed(), false,
            listOf("ㄱ", "SECRET", "ㄱ"))
        assertEquals(listOf("ㄱ"), result.getValue("requested_keys").jsonArray.map { it.jsonPrimitive.content })
        for (secret in listOf("PRIVATE", "private.invalid", "node_id", "bounds", "SECRET", "11", "1 2 3 4")) {
            assertFalse(secret, result.toString().contains(secret))
        }
        assertFalse(result.getValue("observation_succeeded").jsonPrimitive.boolean)
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(buildJsonObject {
            put("outcome", "DIAGNOSTIC_COMPLETE"); put("goal_verified", false)
            put("completed_keys", 0); put("touch_attempts", 0)
        }))
    }

    @Test fun followUpNeverStartsWithoutAuthorityAndNeverInputs() =
        kotlinx.coroutines.runBlocking {
            val baseline = ControlFrame("com.android.chrome", 42, "baseline", emptyList())
            var captures = 0
            val blocked = DebugKeyboardEvidence.inspectAmbiguousCandidates(
                capture = { captures++; baseline }, baseline = baseline, keyIndex = 0,
                allowed = { false })
            assertEquals(0, captures)
            assertEquals(0, blocked.reads)
            assertNull(blocked.evidence)

            val accepted = DebugKeyboardEvidence.inspectAmbiguousCandidates(
                capture = { captures++; baseline }, baseline = baseline, keyIndex = 0,
                allowed = { true })
            assertEquals(1, captures)
            assertEquals(1, accepted.reads)
            assertEquals("captured_read_only", accepted.status)
            assertFalse(accepted.evidence!!.getValue("touch_attempted").jsonPrimitive.boolean)
        }

    @Test fun followUpDoesNotTurnPassiveNativeReadIntoKeyAuthority() {
        val frame = ControlFrame("com.android.chrome", 42, "PRIVATE_NATIVE_FINGERPRINT",
            emptyList(), reason = "foreground_application_required")
        val result = DebugKeyboardEvidence.fromFollowUp(frame, editorSame = false, prefixSame = false)
        assertEquals("foreground_application_required",
            result.getValue("capture_refusal").jsonPrimitive.content)
        assertFalse(result.getValue("touch_attempted").jsonPrimitive.boolean)
        assertFalse(result.getValue("image_or_pixel_recertified").jsonPrimitive.boolean)
        assertFalse(result.toString().contains("PRIVATE_NATIVE_FINGERPRINT"))
    }
}
