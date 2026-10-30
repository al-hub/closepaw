package ai.closepaw.chatgpt.control

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.serialization.json.*

class ProjectionDiagnosticZipTest {
    @Test fun zipContainsExactlyRemoteProjectionJsonWithoutOtherEvents() {
        ControlDiagnostics.record("android.ui.action", "not_for_projection_export",
            metrics = buildJsonObject { put("screen_text", "private-marker") })
        ControlDiagnostics.record("projection_frame_acquire", "timeout",
            metrics = buildJsonObject { put("source_clock_contract", "unverified"); put("null_reads", 1) })
        val remoteJson = ControlDiagnostics.projectionSnapshot().toString()
        val data = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { ProjectionDiagnosticZip.write(it, remoteJson) }
        }.toByteArray()
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            assertEquals(ProjectionDiagnosticZip.ENTRY_NAME, zip.nextEntry?.name)
            val exported = zip.readBytes().toString(Charsets.UTF_8)
            assertEquals(Json.parseToJsonElement(remoteJson), Json.parseToJsonElement(exported))
            assertFalse(exported.contains("private-marker"))
            assertFalse(exported.contains("screen_text"))
            assertNull(zip.nextEntry)
        }
    }
}
