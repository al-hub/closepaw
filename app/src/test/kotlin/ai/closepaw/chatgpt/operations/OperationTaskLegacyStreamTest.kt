package ai.closepaw.chatgpt.operations

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationTaskLegacyStreamTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun streamsCompleteRecordsAndHashesExactOriginalBytes() {
        val file = File(directory.root, "tasks.json")
        file.writeText(""" [ {"task_id":"a","request_id":"r","future":[{"n":2}],"result":{"stdout":"한글\n\""}}, {"task_id":"b","state":null} ] """)
        val expected = Json.parseToJsonElement(file.readText()).jsonArray
        var count = 0
        val result = OperationTaskLegacyStream.read(file) { assertEquals(expected[count++], it) }
        assertEquals(2, count)
        assertEquals(2, result.counts.tasks)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }, result.sha256)
    }
    @Test fun perRecordLimitAndDuplicateIdentitiesStillFailClosed() {
        val file = File(directory.root, "tasks.json")
        listOf(
            """[{"task_id":"a"},{"task_id":"a"}]""",
            """[{"task_id":"a","request_id":"r"},{"task_id":"b","request_id":"r"}]""",
            """[{"task_id":"a","task_id":"b"}]""",
            """[{"task_id":"a","result":""" + "\"" + "x".repeat(OperationTaskDiagnosticScanner.MAX_RECORD_CHARS) + "\"}]"
        ).forEach { raw ->
            file.writeText(raw)
            try { OperationTaskLegacyStream.read(file) { }; fail("invalid source accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(raw, file.readText())
        }
    }
    @Test fun sourceReplacementDuringReadIsRejected() {
        val file = File(directory.root, "tasks.json"); file.writeText("[]")
        try {
            OperationTaskLegacyStream.read(file, openInput = {
                val input = file.inputStream()
                File(directory.root, "replacement").apply { writeText("[ ]") }.renameTo(file)
                input
            })
            fail("source change accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
