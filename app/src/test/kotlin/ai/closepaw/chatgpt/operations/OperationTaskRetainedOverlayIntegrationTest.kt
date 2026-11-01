package ai.closepaw.chatgpt.operations

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Integration checks against the real OperationTaskStore, not only staging. */
class OperationTaskRetainedOverlayIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun record(i: Int, state: String = "succeeded") = buildJsonObject {
        put("task_id", "id-$i"); put("request_id", "request-$i")
        put("fingerprint", "fp-$i"); put("operation_id", "linux.shell.exec")
        put("state", state); put("created_at_ms", i.toLong())
        put("result", buildJsonObject { put("stdout", "output-$i") })
    }
    private fun original(): File {
        val file = File(temp.root, "tasks.json")
        file.writeText(JsonArray((0 until 2000).map { record(it, if (it == 0) "outcome_unknown" else "succeeded") }).toString())
        return file
    }
    private fun sha(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun originalPreservedAcrossAdmissionResultAndRestart() {
        val file = original(); val initial = sha(file)
        val store = OperationTaskStore(file)
        assertTrue(store.claim(record(2000, "queued")).created)
        assertTrue(store.updateActive("id-2000", mapOf("state" to JsonPrimitive("succeeded"))))
        assertEquals(initial, sha(file))
        val restarted = OperationTaskStore(file)
        assertEquals("succeeded", restarted.get("id-2000")?.get("state")?.jsonPrimitive?.content)
        assertEquals(record(0, "outcome_unknown"), restarted.get("id-0"))
        assertFalse(restarted.claim(record(2000, "queued")).created)
        assertEquals(record(1999), restarted.byRequest("request-1999"))
        assertEquals(initial, sha(file))
    }

    @Test fun firstOverlayFailureMustNotAdmitAndLegacyRemainsUnchanged() {
        val file = original(); val initial = sha(file)
        val store = OperationTaskStore(file, beforeStage = {
            if (it == "overlay_write") throw java.io.IOException("injected")
        })
        try {
            store.claim(record(2000))
            fail("claim must fail closed")
        } catch (e: OperationTaskStorageException) { assertEquals("overlay_write", e.stage) }
        assertEquals(initial, sha(file))
        assertNull(OperationTaskStore(file).get("id-2000"))
        assertTrue(OperationTaskStore(file).claim(record(2000)).created)
        assertEquals(initial, sha(file))
    }
    @Test fun missingHistoryMarkerMustFailClosedRatherThanForgetClaims() {
        val file = original()
        val store = OperationTaskStore(file)
        val claim = record(2000)
        assertTrue(store.claim(claim).created)
        val marker = File(file.path + ".history/enabled")
        assertTrue(marker.exists())
        assertTrue(marker.delete())
        // No new work is allowed when the already-activated history
        // cannot be proven complete. Current implementation mistakenly
        // treats this as an inactive history and may admit a replay.
        try {
            val restarted = OperationTaskStore(file)
            restarted.claim(claim)
            fail("lost history marker silently forgot prior admission")
        } catch (_: OperationTaskStorageException) {
            // Correct behavior is fail-closed until safe repair.
        }
    }

    @Test fun deletedCommittedHistoryRecordMustNotBeSilentlyForgotten() {
        val file = original()
        val originalStore = OperationTaskStore(file)
        val admitted = record(2000)
        assertTrue(originalStore.claim(admitted).created)
        val history = File(file.path + ".history")
        val committed = history.listFiles()!!.single { it.extension == "json" }
        assertTrue(committed.delete())
        try {
            val restarted = OperationTaskStore(file)
            restarted.claim(admitted)
            fail("history record loss must fail closed, never duplicate a claim")
        } catch (_: OperationTaskStorageException) {
            // Only a complete indexed generation can establish safe replay protection.
        }
    }

    @Test fun committedGenerationTamperMustFailClosed() {
        val file = original()
        assertTrue(OperationTaskStore(file).claim(record(2000)).created)
        val history = File(file.path + ".history")
        val marker = File(history, "enabled").readText().trim().split(' ')
        val committed = File(history, marker[1] + ".json")
        assertTrue(committed.exists())
        committed.appendText("tampered")
        try {
            OperationTaskStore(file).claim(record(2000))
            fail("tampered history must never be trusted for dedup")
        } catch (_: OperationTaskStorageException) {}
    }

    @Test fun updatedClaimAndEarlierHistorySurviveMultipleGenerations() {
        val file = original()
        val store = OperationTaskStore(file)
        assertTrue(store.claim(record(2000, "queued")).created)
        assertTrue(store.claim(record(2001, "queued")).created)
        assertTrue(store.updateActive("id-2000", mapOf("state" to JsonPrimitive("succeeded"))))
        val restored = OperationTaskStore(file)
        assertEquals("succeeded", restored.get("id-2000")?.get("state")?.jsonPrimitive?.content)
        assertEquals("outcome_unknown", restored.get("id-2001")?.get("state")?.jsonPrimitive?.content)
        assertFalse(restored.claim(record(2001, "queued")).created)
        assertEquals(record(1999), restored.get("id-1999"))
    }

    @Test fun olderCommittedManifestRollbackMustNotForgetNewerClaim() {
        val file = original()
        val store = OperationTaskStore(file)
        assertTrue(store.claim(record(2000)).created)
        val marker = File(file.path + ".history/enabled")
        val earlier = marker.readBytes()
        assertTrue(store.claim(record(2001)).created)
        marker.writeBytes(earlier)
        try {
            OperationTaskStore(file).claim(record(2001))
            fail("restored old manifest must not replay a newer committed request")
        } catch (_: OperationTaskStorageException) {}
    }

    @Test fun oldManifestRollbackMustNotRevertTerminalResultForSameTaskId() {
        val file = original()
        val store = OperationTaskStore(file)
        assertTrue(store.claim(record(2000, "queued")).created)
        val manifest = File(file.path + ".history/enabled")
        val old = manifest.readBytes()
        assertTrue(store.updateActive("id-2000", mapOf(
            "state" to JsonPrimitive("succeeded"),
            "result" to buildJsonObject { put("stdout", "FINAL_PRESERVED") }
        )))
        manifest.writeBytes(old)
        try {
            val restored = OperationTaskStore(file)
            val task = restored.get("id-2000")
            fail("manifest rollback accepted old state: " + task?.get("state"))
        } catch (_: OperationTaskStorageException) {}
    }

    @Test fun previousUnknownToTerminalTransitionAcrossRestartMustRemainReadable() {
        val file = original()
        val store = OperationTaskStore(file)
        val submitted = record(2000, "queued")
        assertTrue(store.claim(submitted).created)
        assertTrue(store.updateActive("id-2000", mapOf(
            "state" to JsonPrimitive("outcome_unknown"),
            "summary" to JsonPrimitive("not confirmed")
        )))
        val restarted = OperationTaskStore(file)
        assertEquals("outcome_unknown", restarted.get("id-2000")?.get("state")?.jsonPrimitive?.content)
        assertFalse(restarted.claim(submitted).created)
    }

    @Test fun committedGenerationDisappearanceMustBlockNewAdmission() {
        val file = original()
        val store = OperationTaskStore(file)
        assertTrue(store.claim(record(2000)).created)
        assertTrue(store.claim(record(2001)).created)
        val marker = File(file.path + ".history/enabled").readText().trim().split(' ')
        val current = File(file.path + ".history/" + marker[1] + ".json")
        assertTrue(current.delete())
        try {
            OperationTaskStore(file).claim(record(2002))
            fail("missing committed generation must fail closed")
        } catch (_: OperationTaskStorageException) {}
    }

    @Test fun sameStateProgressUpdateMustRemainDurableAcrossGenerations() {
        val file = original()
        val store = OperationTaskStore(file)
        assertTrue(store.claim(record(2000, "queued")).created)
        assertTrue(store.updateActive("id-2000", mapOf(
            "summary" to JsonPrimitive("progress received")
        )))
        val restarted = OperationTaskStore(file)
        assertEquals("outcome_unknown", restarted.get("id-2000")?.get("state")?.jsonPrimitive?.content)
        assertEquals("progress received", restarted.get("id-2000")?.get("summary")?.jsonPrimitive?.content)
        assertFalse(restarted.claim(record(2000, "queued")).created)
    }


}
