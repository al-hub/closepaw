package ai.closepaw.chatgpt.control

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VerificationPipelinePrototypeTest {
    private fun proof(id: Int) = (1..id).joinToString("").toLong().let {
        VerificationPipelinePrototype.Proof(id, 1, 1, it, it)
    }
    @Test fun boundedOneAndTwoRetainCorrectOrderAndApplyBackpressure() = runTest {
        for (limit in 1..2) {
            val pipeline = VerificationPipelinePrototype(limit)
            val executed = mutableListOf<Int>()
            val result = pipeline.run(executeAndSnapshot = { id -> executed.add(id); delay(3); proof(id) },
                verify = { delay(50); it.actual == it.expected })
            assertEquals((1..9).toList(), executed)
            assertEquals(9, result.verifiedPrefix)
            assertEquals(limit, result.peakInFlight)
            assertNull(result.reason)
            assertEquals(0, result.residualQueue)
        }
    }
    @Test fun eachSafetySignalStopsAtNativeBoundaryAndDiscardsPendingWork() = runTest {
        for (risk in listOf("app_changed", "locked", "permission_revoked", "x", "user_takeover", "session_ended")) {
            val pipeline = VerificationPipelinePrototype(2)
            var signal: String? = null
            val executed = mutableListOf<Int>()
            val result = pipeline.run(guard = { signal }, executeAndSnapshot = { id ->
                executed.add(id)
                if (id == 2) signal = risk
                proof(id)
            }, verify = { delay(10); true })
            assertEquals(listOf(1, 2), executed)
            assertEquals(risk, result.reason)
            assertEquals(0, result.verifiedPrefix)
            assertTrue(result.entries.all { it.state == VerificationPipelinePrototype.State.OUTCOME_UNKNOWN })
            assertEquals(0, result.residualQueue)
        }
    }
    @Test fun xDuringVerifierDelayCancelsProducerWithoutExecutingOrReplayingQueue() = runTest {
        val pipeline = VerificationPipelinePrototype(2)
        val executed = mutableListOf<Int>()
        val task = async { pipeline.run(executeAndSnapshot = { executed.add(it); proof(it) }, verify = { delay(1000); true }) }
        runCurrent()
        assertEquals(listOf(1, 2), executed)
        pipeline.stop("x")
        advanceUntilIdle()
        val result = task.await()
        assertEquals(listOf(1, 2), executed)
        assertEquals(0, result.verifiedPrefix)
        assertEquals("x", result.reason)
        try { pipeline.run(executeAndSnapshot = { proof(it) }); fail("restarted") }
        catch (_: IllegalStateException) { }
    }
    @Test fun missingDuplicateReversedStaleOrWrongWindowEvidenceCannotVerifySuccess() = runTest {
        val transforms: List<(VerificationPipelinePrototype.Proof) -> VerificationPipelinePrototype.Proof> = listOf(
            { it.copy(hasResult = false) }, // Event only / missing snapshot result.
            { it.copy(command = 1) }, // Duplicate callback delivered for step 2.
            { it.copy(command = 3) }, // Future/reversed callback.
            { it.copy(fresh = false) }, // Old node/snapshot reference.
            { it.copy(generation = 2) }, // Screenshot belongs to another window generation.
            { it.copy(epoch = 2) },
            { it.copy(actual = 99) }, // Late rendering or missing actual UI effect.
            { it.copy(expected = 99, actual = 99) }, // Cannot self-certify a fabricated expectation.
        )
        for (transform in transforms) {
            val pipeline = VerificationPipelinePrototype(1)
            val executed = mutableListOf<Int>()
            val result = pipeline.run(executeAndSnapshot = { id ->
                executed.add(id); if (id == 2) transform(proof(id)) else proof(id)
            })
            assertEquals(listOf(1, 2), executed)
            assertEquals(1, result.verifiedPrefix)
            assertNotNull(result.reason)
            assertEquals(VerificationPipelinePrototype.State.OUTCOME_UNKNOWN, result.entries.last().state)
        }
    }
    @Test fun verifierExceptionAndExternalCancellationNeverResumeExecution() = runTest {
        val pipeline = VerificationPipelinePrototype(2)
        val result = pipeline.run(executeAndSnapshot = { proof(it) }, verify = { error("private payload") })
        assertEquals("exception", result.reason)
        assertFalse(result.toString().contains("private"))
        val other = VerificationPipelinePrototype(2)
        val executed = mutableListOf<Int>()
        val task = launch { other.run(executeAndSnapshot = { executed.add(it); delay(1000); proof(it) }) }
        runCurrent(); task.cancelAndJoin(); advanceUntilIdle()
        assertEquals(listOf(1), executed)
        assertEquals("cancelled", other.result().reason)
        assertEquals(VerificationPipelinePrototype.State.OUTCOME_UNKNOWN, other.result().entries.single().state)
    }
    @Test fun immutableSnapshotSurvivesLaterUiMutationAndDelayedEventsNeedActualResult() = runTest {
        var ui = 0L
        val pipeline = VerificationPipelinePrototype(2)
        val result = pipeline.run(executeAndSnapshot = { id ->
            ui = proof(id).expected
            proof(id).copy(actual = ui)
        }, verify = { captured -> delay(10); captured.actual == captured.expected })
        assertEquals(123456789L, ui)
        assertEquals(9, result.verifiedPrefix)
    }
    @Test fun independentlyCancelledCallbacksStopSiblingWithoutDeadlockOrFalseSuccess() = runTest {
        for (cancelVerifier in listOf(false, true)) {
            val pipeline = VerificationPipelinePrototype(2)
            val result = pipeline.run(executeAndSnapshot = {
                if (!cancelVerifier) throw CancellationException("private callback")
                proof(it)
            }, verify = { throw CancellationException("private verifier") })
            assertEquals("cancelled", result.reason)
            assertEquals(0, result.verifiedPrefix)
            assertTrue(result.entries.all { it.state == VerificationPipelinePrototype.State.OUTCOME_UNKNOWN })
        }
    }
    @Test fun tenPlusTenVirtualTimeModelIsExplicitlyNotAnAndroidPerformanceResult() = runTest {
        suspend fun sample(limit: Int): JsonObject {
            val start = testScheduler.currentTime
            val result = VerificationPipelinePrototype(limit).run(executeAndSnapshot = {
                // Chosen model costs, not observed Android costs. No security call is removed.
                delay(1200); proof(it)
            }, verify = { delay(40); true })
            return buildJsonObject {
                put("modeled_duration_ms", testScheduler.currentTime - start)
                put("verified", result.verifiedPrefix); put("peak_in_flight", result.peakInFlight)
            }
        }
        val baseline = List(10) { sample(1) }
        val candidate = List(10) { sample(2) }
        assertTrue((baseline + candidate).all { it.getValue("verified").jsonPrimitive.int == 9 })
        val output = buildJsonObject {
            put("kind", "VIRTUAL_TIME_MODEL_NOT_DEVICE_MEASUREMENT")
            put("source_commit", System.getenv("GITHUB_SHA") ?: "local_uncommitted")
            put("clock_domain", "kotlin_test_scheduler_virtual_ms")
            put("model_execution_ms_per_command", 1200); put("model_verify_ms_per_command", 40)
            put("baseline", JsonArray(baseline)); put("candidate", JsonArray(candidate))
            put("android_performance", "NOT_TESTED"); put("voice_e2e", "NOT_TESTED")
            put("cpu_memory", "NOT_MEASURED; virtual time is not CPU time")
        }
        File("build/reports/benchmarks/issue161-virtual-model.json").apply {
            parentFile.mkdirs(); writeText(output.toString())
        }
    }
}
