package ai.closepaw.chatgpt.operations

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationTaskHistoryStageTest {
    @get:Rule val temp = TemporaryFolder()
    private fun hash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun stagingPreservesLegacyAndCanVerifyAnExistingCopy() {
        val source = File(temp.root, "legacy.json")
        source.writeText("[{\"task_id\":\"old\",\"result\":{\"stdout\":\"retained\"}}]")
        val initial = hash(source)
        val staged = OperationTaskHistoryStage(File(temp.root, "staged"))
        val first = staged.stageLegacy(source)
        assertEquals(initial, hash(source))
        assertEquals(initial, hash(first))
        assertEquals(first.canonicalFile, staged.stageLegacy(source).canonicalFile)
        assertTrue(File(temp.root, "staged").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun eachInjectedStageFailureLeavesSourceUnchangedAndUnpublished() {
        for (boundary in listOf("stage_copy", "stage_fsync", "stage_verify", "stage_publish")) {
            val source = File(temp.root, "source-$boundary.json")
            source.writeText("[{\"task_id\":\"safe\",\"state\":\"outcome_unknown\"}]")
            val initial = hash(source)
            val dir = File(temp.root, "dir-$boundary")
            try {
                OperationTaskHistoryStage(dir).stageLegacy(source) { if (it == boundary) throw IllegalStateException("injected") }
                fail("fault injection did not fail: $boundary")
            } catch (_: IllegalStateException) {}
            assertEquals(initial, hash(source))
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".json") || it.name.endsWith(".tmp") })
        }
    }

    @Test fun existingTamperedDestinationIsNeverAccepted() {
        val source = File(temp.root, "original")
        source.writeText("[{\"task_id\":\"one\"}]")
        val stager = OperationTaskHistoryStage(File(temp.root, "target"))
        val target = stager.stageLegacy(source)
        target.writeText("tampered")
        try {
            stager.stageLegacy(source)
            fail("tampering must refuse")
        } catch (_: IllegalArgumentException) {}
        assertEquals("tampered", target.readText())
        assertEquals("[{\"task_id\":\"one\"}]", source.readText())
    }
}
