package ai.closepaw.qa

import ai.closepaw.chatgpt.control.ControlSession
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureQaContractTest {
    private fun report(runner: String, imageHash: String = runner) = buildJsonObject {
        put("runner", runner); put("schema_version", 1); put("source_commit", "a".repeat(40))
        put("version_name", "0.1.110-capturecandidate"); put("version_code", 111)
        put("fixture_package", "com.android.chrome"); put("requested_scope", "screen_observe")
        put("outcome", "CAPTURE_PASS"); put("reason", "native_observation_contract_passed")
        put("image_sha256", imageHash)
        put("visual", buildJsonObject {
            put("scope", "screen_observe"); put("observation_only", true); put("visual_actions_allowed", false)
            put("source_display_id", 0); put("rotation", 0); put("image_width", 443); put("image_height", 960)
            put("source_width", 1440); put("source_height", 3120)
            put("application_bounds", "0 127 1440 1798"); put("ime_bounds", "0 1798 1440 3120")
            put("outside_requested_regions_masked", true); put("owned_pixels_verified_against_isolated_windows", false)
            put("verified_region_count", 0)
        })
    }
    private fun JsonObject.replacing(key: String, value: JsonElement) = JsonObject(toMutableMap().apply { put(key, value) })

    @Test fun separatelyCapturedPixelsNeedNotBeByteIdentical() {
        assertEquals("numeric_contract_match_pixels_require_separate_review", CaptureQaComparison.compare(report("local", "first-frame"), report("adb", "later-frame")))
    }
    @Test fun differentSourceOrFixtureCannotBeComparedAsAcceptance() {
        assertEquals("mismatch_source_commit", CaptureQaComparison.compare(report("local"), report("adb").replacing("source_commit", JsonPrimitive("b".repeat(40)))))
        assertEquals("mismatch_fixture_package", CaptureQaComparison.compare(report("local"), report("adb").replacing("fixture_package", JsonPrimitive("com.samsung.android.app.notes"))))
        assertEquals("runner_pair_required", CaptureQaComparison.compare(report("local"), report("local")))
    }
    @Test fun equalUnsafeAuthorityClaimsAreRejected() {
        fun unsafe(runner: String): JsonObject {
            val original = report(runner)
            return original.replacing("visual", original.getValue("visual").jsonObject.replacing("visual_actions_allowed", JsonPrimitive(true)))
        }
        assertEquals("invalid_observation_contract", CaptureQaComparison.compare(unsafe("local"), unsafe("adb")))
    }
    @Test fun changedCoordinatesCannotMatch() {
        val original = report("adb")
        val shifted = original.replacing("visual", original.getValue("visual").jsonObject.replacing("ime_bounds", JsonPrimitive("0 1700 1440 3120")))
        assertEquals("mismatch_visual_ime_bounds", CaptureQaComparison.compare(report("local"), shifted))
    }
    @Test fun equalSetupFailuresAreNotPassingEvidence() {
        assertEquals("passing_pair_required", CaptureQaComparison.compare(report("local").replacing("outcome", JsonPrimitive("SETUP_BLOCKED")), report("adb").replacing("outcome", JsonPrimitive("SETUP_BLOCKED"))))
    }
    @Test fun pauseOrResumeCannotRearmAnOwner() {
        val session = ControlSession()
        val owner = requireNotNull(CaptureQaOwner.start(session))
        session.pause(); assertFalse(owner.current()); assertNull(CaptureQaOwner.start(session))
        assertTrue(session.beginResume()); session.finishResume(session.epoch, true)
        assertFalse(owner.current())
        owner.close(); assertEquals(ControlSession.State.ENDED, session.state)
    }
    @Test fun cleanupPreservesNewerAndPreexistingSessions() {
        val session = ControlSession()
        val owner = requireNotNull(CaptureQaOwner.start(session))
        assertNull(CaptureQaOwner.start(session))
        session.end(); assertTrue(session.start())
        val newerId = session.id
        owner.close(); assertEquals(newerId, session.id); assertEquals(ControlSession.State.ACTIVE, session.state)
        session.pause(); assertNull(CaptureQaOwner.start(session)); assertEquals(ControlSession.State.PAUSED, session.state)
    }
    @Test fun concurrentCleanupCannotEndReplacementSession() {
        val session = ControlSession()
        val owner = requireNotNull(CaptureQaOwner.start(session))
        val entered = CountDownLatch(1)
        val cleanup = Thread { entered.countDown(); owner.close() }
        synchronized(session) {
            cleanup.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (cleanup.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, cleanup.state)
            session.end(); assertTrue(session.start())
        }
        cleanup.join(2_000)
        assertFalse(cleanup.isAlive)
        assertEquals(ControlSession.State.ACTIVE, session.state)
    }
    @Test fun evidenceChangesCannotEnterWhileAnotherSharedOperationOwnsTheRunner() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val worker = Thread {
            CaptureQaAcceptance.withIdleEvidence {
                entered.countDown(); finish.await(5, TimeUnit.SECONDS)
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(CaptureQaAcceptance.withIdleEvidence { fail("Concurrent evidence mutation entered"); true })
        } finally { finish.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        assertEquals(true, CaptureQaAcceptance.withIdleEvidence { true })
    }
}
