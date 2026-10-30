package ai.closepaw.chatgpt.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class ControlDiagnosticsProjectionTest {
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
        val snapshot = ControlDiagnostics.projectionSnapshot()
        assertEquals(1, snapshot["schema_version"]?.jsonPrimitive?.int)
        assertEquals(16, snapshot["stored_events"]?.jsonPrimitive?.int)
        val events = snapshot["events"]!!.jsonArray
        assertEquals(16, events.size)
        assertTrue(events.all { it.jsonObject["stage"]?.jsonPrimitive?.content == "projection_frame_acquire" })
        assertTrue(events.all { it.jsonObject["reason_code"]?.jsonPrimitive?.content == "timeout" })
        assertFalse(snapshot.toString().contains("private_marker"))
        assertFalse(snapshot.toString().contains("must_not_export"))
    }
}
