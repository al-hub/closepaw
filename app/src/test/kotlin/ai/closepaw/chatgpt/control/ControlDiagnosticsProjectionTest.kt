package ai.closepaw.chatgpt.control

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.*

class ControlDiagnosticsProjectionTest {
    @Test fun projectionRequestCorrelationExcludesUnapprovedMetadata() {
        val requestId = "abcdef0123456789"
        ControlDiagnostics.record("projection_frame_acquire", "timeout",
            metrics = buildJsonObject { put("request_id", requestId); put("attempt_id", 123L); put("screen_text", "leak_marker") })
        ControlDiagnostics.record("projection_observe_result", "projection_frame_unavailable",
            metrics = buildJsonObject { put("request_id", requestId); put("extra", "hidden_marker") })
        val matched = ControlDiagnostics.projectionSnapshot()["events"]!!.jsonArray
            .map { it.jsonObject }.filter { it["metrics"]?.jsonObject?.get("request_id")?.jsonPrimitive?.content == requestId }
        assertTrue(matched.any { it["stage"]?.jsonPrimitive?.content == "projection_frame_acquire" })
        assertTrue(matched.any { it["stage"]?.jsonPrimitive?.content == "projection_observe_result" })
        assertFalse(matched.toString().contains("leak_marker"))
        assertFalse(matched.toString().contains("hidden_marker"))
    }

    @Test fun projectionSnapshotOnlyIncludesBoundedAcquireEvents() {
        ControlDiagnostics.record("android.ui.action", "completed",
            metrics = buildJsonObject { put("private_marker", "must_not_export") })
        repeat(20) { index ->
            ControlDiagnostics.record("projection_frame_acquire", "timeout",
                metrics = buildJsonObject {
                    put("null_reads", index)
                    put("source_clock_contract", "unverified")
                })
        }
        ControlDiagnostics.record("projection_frame_acquire", "timeout",
            metrics = buildJsonObject {
                put("screen_text", "sensitive_marker")
                put("attempt_id", 123)
                put("first_clock_sample", buildJsonObject {
                    put("image_timestamp_ns", 123456L)
                    put("secret", "hidden_marker")
                })
            })
        val snapshot = ControlDiagnostics.projectionSnapshot()
        assertEquals(1, snapshot["schema_version"]?.jsonPrimitive?.int)
        assertEquals(16, snapshot["stored_events"]?.jsonPrimitive?.int)
        val events = snapshot["events"]!!.jsonArray
        assertEquals(16, events.size)
        assertTrue(events.all { it.jsonObject["stage"]?.jsonPrimitive?.content == "projection_frame_acquire" })
        assertTrue(events.all { it.jsonObject["reason_code"]?.jsonPrimitive?.content == "timeout" })
        assertEquals(snapshot.toString(), ControlDiagnostics.projectionExportJson())
        assertFalse(snapshot.toString().contains("private_marker"))
        assertFalse(snapshot.toString().contains("must_not_export"))
        assertFalse(snapshot.toString().contains("sensitive_marker"))
        assertFalse(snapshot.toString().contains("hidden_marker"))
        assertFalse(snapshot.toString().contains("screen_text"))
    }
}
