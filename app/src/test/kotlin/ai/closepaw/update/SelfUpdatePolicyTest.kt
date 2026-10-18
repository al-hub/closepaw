package ai.closepaw.update

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*

class SelfUpdatePolicyTest {
    private fun verify(pkg: String = "ai.closepaw", version: String? = "0.1.56", code: Long = 57,
        signers: Set<String> = setOf("original")) = SelfUpdatePolicy.verifyIdentity(pkg, "ai.closepaw", version,
        "0.1.56", code, 56, signers, setOf("original"))

    @Test fun newerReleaseWithOriginalIdentityIsAccepted() { verify() }
    @Test fun differentPackageIsRejected() { assertThrows(IllegalArgumentException::class.java) { verify(pkg = "evil.app") } }
    @Test fun unexpectedReleaseIsRejected() { assertThrows(IllegalArgumentException::class.java) { verify(version = "0.1.57") } }
    @Test fun absentVersionIsRejected() { assertThrows(IllegalArgumentException::class.java) { verify(version = null) } }
    @Test fun downgradeIsRejected() { assertThrows(IllegalArgumentException::class.java) { verify(code = 55) } }
    @Test fun sameVersionCodeIsRejected() { assertThrows(IllegalArgumentException::class.java) { verify(code = 56) } }
    @Test fun changedSignerIsRejected() { assertThrows(IllegalArgumentException::class.java) { verify(signers = setOf("replacement")) } }
    @Test fun absentSignerIsRejected() { assertThrows(IllegalArgumentException::class.java) { verify(signers = emptySet()) } }
    @Test fun extraSignerIsRejected() { assertThrows(IllegalArgumentException::class.java) { verify(signers = setOf("original", "extra")) } }
    @Test fun installationRequiresBothVersionAndCodeEvidence() {
        val record = buildJsonObject { put("target_version", "0.1.56"); put("target_code", 57) }
        assertTrue(SelfUpdatePolicy.installed(record, "0.1.56", 57))
        assertFalse(SelfUpdatePolicy.installed(record, "0.1.55", 56))
        assertFalse(SelfUpdatePolicy.installed(record, "0.1.56", 56))
        assertFalse(SelfUpdatePolicy.installed(record, "0.1.57", 57))
        assertFalse(SelfUpdatePolicy.installed(buildJsonObject {}, "0.1.56", 57))
    }
    @Test fun unresolvedAndApprovalStatesBlockAnotherInstall() {
        assertTrue(SelfUpdatePolicy.activeStates.containsAll(setOf("installing", "user_action_required", "install_outcome_unknown")))
        assertFalse("failed" in SelfUpdatePolicy.activeStates)
    }
    @Test fun ledgerSurvivesProcessRecreationAndPreservesTaskIdentity() {
        val dir = Files.createTempDirectory("self-update").toFile()
        try {
            val file = File(dir, "record.json")
            val ledger = SelfUpdateLedger(file)
            ledger.write(buildJsonObject { put("state", "installing"); put("task_id", "task-1"); put("session_id", 42) })
            val restored = SelfUpdateLedger(file)
            restored.patch("state" to JsonPrimitive("user_action_required"))
            assertEquals("task-1", restored.read()["task_id"]!!.jsonPrimitive.content)
            assertEquals(42, restored.read()["session_id"]!!.jsonPrimitive.int)
            assertEquals("user_action_required", ledger.read()["state"]!!.jsonPrimitive.content)
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptLedgerFailsClosedInsteadOfAuthorizingAnotherInstall() {
        val dir = Files.createTempDirectory("self-update").toFile()
        try {
            val file = File(dir, "record.json").apply { writeText("broken") }
            assertThrows(Exception::class.java) { SelfUpdateLedger(file).read() }
        } finally { dir.deleteRecursively() }
    }
}
