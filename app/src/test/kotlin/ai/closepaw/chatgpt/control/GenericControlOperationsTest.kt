package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GenericControlOperationsTest {
    private class Port : ControlPort {
        var controlSession: ControlSession? = null
        override fun session() = controlSession
        var writes = 0
        val activity = mutableListOf<Boolean>()
        var captureWait = 0L
        var captureFails = false
        override suspend fun activity(active: Boolean) { activity.add(active) }
        var testWait = 0L
        override suspend fun feedbackTest(durationMs: Long): OperationResult {
            activity(true)
            try { delay(testWait); return OperationResult(data = buildJsonObject {
                put("reason_code", "duration_completed"); put("human_touch_verified", false)
            }) } finally { activity(false) }
        }
        var appsFail = false
        var appsWait = 0L
        override suspend fun capture(): ControlFrame {
            delay(captureWait)
            if (captureFails) error("capture failed")
            return ControlFrame("example.app", 1, "stable-$writes", listOf(ControlNode(1, "Menu", clickable = true)))
        }
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            writes++; return ControlEffect(true, true, "acknowledged")
        }
        override suspend fun apps(filter: String): JsonArray {
            delay(appsWait)
            if (appsFail) error("private package lookup exception")
            return JsonArray(emptyList())
        }
    }
    private fun registry(port: Port, mutex: Mutex = Mutex()) = OperationRegistry().also {
        GenericControlOperations(GenericControl(port, { true }), port, mutex) { OperationReadiness() }.register(it)
    }
    @Test fun pauseAndStopBlockEvenNewObservationsAndActions() = runBlocking {
        val session = ControlSession().apply { activateLegacy() }
        val port = Port().apply { controlSession = session }; val registry = registry(port)
        for (stop in listOf(false, true)) {
            if (stop) session.end() else session.pause()
            for (operation in listOf(GenericControlOperations.OBSERVE, GenericControlOperations.ACTION)) {
                val result = registry.get(operation)!!.handler.invoke(OperationInvocation("blocked", buildJsonObject {}))
                assertFalse(result.success)
                assertEquals(if (stop) "control_session_ended" else "control_session_paused", result.summary)
            }
        }
        assertEquals(0, port.writes); assertTrue(port.activity.isEmpty())
    }
    @Test fun physicalPauseCancelsCurrentCaptureAndReleasesMutex() = runBlocking {
        val session = ControlSession().apply { activateLegacy() }
        val port = Port().apply { controlSession = session; captureWait = 60_000 }
        val mutex = Mutex(); val registry = registry(port, mutex)
        val job = launch { registry.get(GenericControlOperations.OBSERVE)!!.handler.invoke(OperationInvocation("slow", buildJsonObject {})) }
        yield(); session.pause(); job.join()
        assertTrue(job.isCancelled); assertFalse(mutex.isLocked); assertEquals(0, port.writes)
    }
    @Test fun sessionDescriptorsValidateAndNoAiResumeExists() {
        val registry = registry(Port())
        for (command in listOf("start", "status", "wait", "end"))
            OperationSchemaValidator.checkSchema(registry.get("android.control.session.$command")!!.descriptor.inputSchema)
        assertNull(registry.get("android.control.session.resume"))
    }
    @Test fun remoteObservationShowsActivityOnlyUntilTheOperationEnds() = runBlocking {
        val port = Port(); val registry = registry(port)
        assertTrue(registry.get(GenericControlOperations.OBSERVE)!!.handler.invoke(
            OperationInvocation("observe", buildJsonObject { put("filter", "Menu") })).success)
        assertEquals(listOf(true, false), port.activity)
        port.activity.clear()
        registry.get(GenericControlOperations.APPS)!!.handler.invoke(OperationInvocation("apps", buildJsonObject { put("filter", "Example") }))
        assertTrue("App metadata lookup does not imply screen control", port.activity.isEmpty())
    }
    @Test fun activityAndMutexAreReleasedOnCaptureFailure() = runBlocking {
        val port = Port().apply { captureFails = true }; val mutex = Mutex()
        try {
            registry(port, mutex).get(GenericControlOperations.OBSERVE)!!.handler.invoke(OperationInvocation("fail", buildJsonObject {}))
            fail("Capture failure should propagate")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(true, false), port.activity)
        assertFalse(mutex.isLocked)
    }
    @Test fun activityAndMutexAreReleasedOnTimeout() = kotlinx.coroutines.test.runTest {
        val port = Port().apply { captureWait = 10_001 }; val mutex = Mutex()
        try {
            registry(port, mutex).get(GenericControlOperations.OBSERVE)!!.handler.invoke(OperationInvocation("timeout", buildJsonObject {}))
            fail("Capture should time out")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(listOf(true, false), port.activity)
        assertFalse(mutex.isLocked)
    }
    @Test fun runtimeApplyRollbackInvalidatesObservationAndRejectsStaleRevision() = runBlocking {
        var disk: String? = null
        val runtime = RuntimeControlSettings({ disk }, { disk = it })
        val port = Port(); val control = GenericControl(port, { true })
        val registry = OperationRegistry()
        GenericControlOperations(control, port, Mutex(), runtime) { OperationReadiness() }.register(registry)
        registry.get(GenericControlOperations.CONFIGURE)!!.descriptor.inputSchema.let(OperationSchemaValidator::checkSchema)
        val observed = control.observe()
        val handler = registry.get(GenericControlOperations.CONFIGURE)!!.handler
        val changed = handler.invoke(OperationInvocation("apply", buildJsonObject {
            put("expected_revision", 0); put("action", "apply"); put("config", RuntimeControlSettings.defaults())
        }))
        assertTrue(changed.success)
        assertEquals(JsonPrimitive(1L), changed.data["revision"])
        assertFalse(control.action(buildJsonObject {
            put("observation_id", observed.data.getValue("observation_id")); put("action", "home")
        }).success)
        assertEquals(0, port.writes)
        val stale = handler.invoke(OperationInvocation("stale", buildJsonObject {
            put("expected_revision", 0); put("action", "rollback")
        }))
        assertFalse(stale.success); assertEquals("configuration_revision_changed", stale.summary)
        assertTrue(handler.invoke(OperationInvocation("rollback", buildJsonObject {
            put("expected_revision", 1); put("action", "rollback")
        })).success)
        assertEquals(2L, runtime.snapshot().revision)
    }
    @Test fun visibilityFlagIsNotAnInventedOsDiagnosis() = runBlocking {
        val result = registry(Port()).get(GenericControlOperations.APPS)!!.handler.invoke(
            OperationInvocation("apps", buildJsonObject { put("filter", "Calculator") }))
        assertFalse("A constant true is not measured visibility evidence", result.data.containsKey("visibility_limited"))
    }
    @Test fun descriptorsKeepQueriesSeparateAndRejectCoordinates() {
        val registry = registry(Port())
        assertTrue(registry.get(GenericControlOperations.OBSERVE)!!.descriptor.readOnly)
        val action = registry.get(GenericControlOperations.ACTION)!!.descriptor
        assertFalse(action.readOnly)
        assertNotNull(OperationSchemaValidator.validate(action.inputSchema, buildJsonObject {
            put("observation_id", "id"); put("action", "click"); put("x", 1); put("y", 2)
        }))
    }
    @Test fun genericAndDedicatedHandlersShareTheSameMutex() = runBlocking {
        val mutex = Mutex(locked = true); val registry = registry(Port(), mutex)
        val busy = registry.get(GenericControlOperations.OBSERVE)!!.handler.invoke(OperationInvocation("test", buildJsonObject {}))
        assertFalse(busy.success); assertEquals("ui_busy", busy.summary)
        mutex.unlock()
        assertTrue(registry.get(GenericControlOperations.OBSERVE)!!.handler.invoke(OperationInvocation("test", buildJsonObject {})).success)
    }
    @Test fun sameRequestIdDoesNotRepeatNativeClick() = runBlocking {
        val port = Port(); val registry = registry(port)
        val storeFile = File.createTempFile("generic-tasks", ".json").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val dispatcher = OperationDispatcher(registry, OperationTaskStore(storeFile), scope)
            val observed = registry.get(GenericControlOperations.OBSERVE)!!.handler.invoke(OperationInvocation("observe", buildJsonObject {}))
            val descriptor = registry.get(GenericControlOperations.ACTION)!!.descriptor
            val args = buildJsonObject {
                put("operation_id", descriptor.id); put("operation_version", descriptor.version); put("schema_hash", descriptor.schemaHash)
                put("request_id", "single-click"); put("input", buildJsonObject {
                    put("observation_id", observed.data.getValue("observation_id")); put("action", "click"); put("node_id", 1)
                })
            }
            val task = dispatcher.execute(args).getValue("task_id")
            withTimeout(2000) {
                while (dispatcher.getTask(buildJsonObject { put("task_id", task) })["state"]?.jsonPrimitive?.content != "succeeded") delay(10)
            }
            assertEquals(task, dispatcher.execute(args)["task_id"]); assertEquals(1, port.writes)
        } finally { scope.cancel(); storeFile.delete() }
    }
    @Test fun latestUnresolvedDedicatedSendRemainsProtectedAfterRestart() {
        val file = File.createTempFile("question-ledger", ".json").apply { delete() }
        try {
            val store = OperationTaskStore(file)
            fun task(id: String, time: Long, state: String) = buildJsonObject {
                put("task_id", id); put("request_id", id); put("operation_id", "gemini.question.ask")
                put("created_at_ms", time); put("state", state)
            }
            store.put(task("unknown", 1, "outcome_unknown"))
            assertTrue(OperationTaskStore(file).latestOutcomeUnknown("gemini.question.ask"))
            store.put(task("confirmed", 2, "succeeded"))
            assertFalse(store.latestOutcomeUnknown("gemini.question.ask"))
        } finally { file.delete() }
    }
    @Test fun appLookupFailureReturnsSafeReasonInsteadOfEscapingTheReadHandler() = runBlocking {
        val port = Port().apply { appsFail = true }
        val result = registry(port).get(GenericControlOperations.APPS)!!.handler.invoke(OperationInvocation("apps", buildJsonObject { put("filter", "Example") }))
        assertFalse(result.success)
        assertEquals("apps_query_failed", result.data["reason_code"]?.jsonPrimitive?.content)
        assertFalse(result.toString().contains("private package"))
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun slowCatalogHasExplicitTimeoutAndReleasesUiMutex() = kotlinx.coroutines.test.runTest {
        val port = Port().apply { appsWait = 10_001 }
        val mutex = Mutex(); val registry = registry(port, mutex)
        val result = registry.get(GenericControlOperations.APPS)!!.handler.invoke(OperationInvocation("slow-apps", buildJsonObject { put("filter", "Example") }))
        assertFalse(result.success)
        assertEquals("apps_query_timeout", result.data["reason_code"]?.jsonPrimitive?.content)
        assertFalse(mutex.isLocked)
    }
    @Test fun filteredObservationTimeoutIsNeverReportedAsAppCatalogTimeout() = kotlinx.coroutines.test.runTest {
        val port = Port().apply { captureWait = 10_001 }
        try {
            registry(port).get(GenericControlOperations.OBSERVE)!!.handler.invoke(
                OperationInvocation("filtered-timeout", buildJsonObject { put("filter", "Menu") }))
            fail("Screen timeout must propagate")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(listOf(true, false), port.activity)
    }
    @Test fun explicitFeedbackTestNeverClicksAndInvalidatesExistingObservation() = runBlocking {
        val port = Port(); val control = GenericControl(port, { true }); val registry = OperationRegistry()
        GenericControlOperations(control, port, Mutex()) { OperationReadiness() }.register(registry)
        val old = control.observe()
        val result = registry.get(GenericControlOperations.FEEDBACK_TEST)!!.handler.invoke(
            OperationInvocation("feedback", buildJsonObject {}))
        assertTrue(result.success); assertEquals(0, port.writes)
        assertEquals(JsonPrimitive(false), result.data["human_touch_verified"])
        assertEquals(listOf(true, false), port.activity)
        assertFalse(control.action(buildJsonObject {
            put("observation_id", old.data.getValue("observation_id")); put("action", "home")
        }).success)
    }
    @Test fun feedbackCancellationReleasesIndicatorAndMutex() = kotlinx.coroutines.test.runTest {
        val port = Port().apply { testWait = 5_000 }; val mutex = Mutex()
        val job = launch { registry(port, mutex).get(GenericControlOperations.FEEDBACK_TEST)!!.handler.invoke(
            OperationInvocation("cancel-feedback", buildJsonObject {})) }
        testScheduler.runCurrent()
        assertEquals(listOf(true), port.activity)
        job.cancelAndJoin()
        assertEquals(listOf(true, false), port.activity); assertFalse(mutex.isLocked)
    }


    @Test fun batchWithoutExplicitStartMustNotActivateIdleSession() = runBlocking {
        val session = ControlSession()
        val port = Port().apply { controlSession = session }
        val registry = registry(port)
        val result = registry.get(GenericControlOperations.SEQUENCE)!!.handler.invoke(
            OperationInvocation("unauthorized-batch", buildJsonObject {
                put("session_id", "arbitrary")
                put("labels", JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive("2"))))
            })
        )
        assertFalse(result.success)
        assertEquals(ControlSession.State.IDLE, session.state)
        assertFalse(session.explicit)
        assertEquals(0, port.writes)
        assertTrue("A denied batch must not block a later authorized start", session.start())
    }

    @Test fun calculatorSequenceRequiresExplicitSessionAndHasBoundedSchema() = runBlocking {
        val port = Port()
        val registry = registry(port)
        val descriptor = registry.get(GenericControlOperations.SEQUENCE)!!.descriptor
        OperationSchemaValidator.checkSchema(descriptor.inputSchema)
        assertFalse(descriptor.readOnly)
        assertNotNull(OperationSchemaValidator.validate(descriptor.inputSchema, buildJsonObject {
            put("labels", JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive("2"))))
        }))
        val result = registry.get(GenericControlOperations.SEQUENCE)!!.handler.invoke(
            OperationInvocation("no-session", buildJsonObject {
                put("labels", JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive("2"))))
                put("session_id", "arbitrary")
            })
        )
        assertFalse(result.success)
        assertEquals(0, port.writes)
    }

    @Test fun localInterOperationIdleMetricUsesMonotonicClockWithoutScreenText() = runBlocking {
        val port = Port()
        val registry = registry(port)
        val handler = registry.get(GenericControlOperations.OBSERVE)!!.handler
        assertTrue(handler.invoke(OperationInvocation("gap-first-test", buildJsonObject {})).success)
        assertTrue(handler.invoke(OperationInvocation("gap-second-test", buildJsonObject {})).success)
        val events = ControlDiagnostics.snapshot().getValue("events").jsonArray
        val entry = events.map { it.jsonObject }.last {
            it["task_id"]?.jsonPrimitive?.content == "gap-second-test" &&
                it["reason_code"]?.jsonPrimitive?.content == "started"
        }
        val metrics = entry.getValue("metrics").jsonObject
        assertEquals("monotonic_process", metrics["clock_domain"]?.jsonPrimitive?.content)
        assertTrue(metrics.getValue("inter_operation_idle_ms").jsonPrimitive.long >= 0L)
        assertFalse(entry.toString().contains("stable-"))
    }
    @Test fun physicalSessionEndPersistsVerifiedPrefixOnlyAfterExecutorUnwinds() = kotlinx.coroutines.test.runTest {
        val session = ControlSession().also { check(it.start()) }
        var writes = 0
        val port = object : ControlPort {
            override fun session() = session
            override suspend fun capture() = ControlFrame("com.sec.android.app.popupcalculator", 7,
                "digits-$writes", (0..9).map { ControlNode(it, it.toString(), clickable = true) })
            override suspend fun apps(filter: String) = JsonArray(emptyList())
            override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
                writes++
                if (writes == 3) { session.end(); currentCoroutineContext().ensureActive() }
                return ControlEffect(true, true, "acknowledged")
            }
        }
        val registry = OperationRegistry()
        val mutex = Mutex()
        GenericControlOperations(GenericControl(port, { true }), port, mutex) { OperationReadiness() }.register(registry)
        val descriptor = registry.get(GenericControlOperations.SEQUENCE)!!.descriptor
        val file = File.createTempFile("sequence-stop", ".json").apply { delete() }
        try {
            val store = OperationTaskStore(file)
            val dispatcher = OperationDispatcher(registry, store, this, operationEpoch = { session.epoch })
            val args = buildJsonObject {
                put("operation_id", descriptor.id); put("operation_version", descriptor.version)
                put("schema_hash", descriptor.schemaHash); put("request_id", "physical-x")
                put("input", buildJsonObject {
                    put("session_id", session.id); put("labels", JsonArray((1..9).map { JsonPrimitive(it.toString()) }))
                })
            }
            val accepted = dispatcher.execute(args)
            testScheduler.advanceUntilIdle()
            val task = dispatcher.getTask(buildJsonObject { put("task_id", accepted.getValue("task_id")) })
            assertEquals("outcome_unknown", task["state"]?.jsonPrimitive?.content)
            val data = task.getValue("result").jsonObject.getValue("data").jsonObject
            assertEquals(JsonPrimitive(2), data["completed_verified_steps"])
            assertEquals(JsonPrimitive(3), data["interrupted_step"])
            assertEquals(JsonPrimitive(6), data["pending_steps_not_started"])
            assertEquals(JsonPrimitive(true), data["executor_stopped_confirmed"])
            assertEquals(JsonPrimitive(true), data["external_effect_uncertain"])
            assertEquals(JsonPrimitive("control_session_ended"), data["terminal_reason"])
            assertEquals(3, writes); assertFalse(mutex.isLocked)
            assertEquals(accepted["task_id"], dispatcher.execute(args)["task_id"])
            assertEquals(3, writes)
        } finally { file.delete() }
    }


    @Test fun newInputActionsNeverActivateLegacyIdleSessions() = runBlocking {
        for (action in listOf("input", "focus")) {
            val session = ControlSession()
            val port = Port().apply { controlSession = session }
            val result = registry(port).get(GenericControlOperations.ACTION)!!.handler.invoke(
                OperationInvocation("explicit-required", buildJsonObject { put("action", action) }))
            assertFalse(result.success)
            assertEquals("explicit_active_session_required", result.summary)
            assertEquals(ControlSession.State.IDLE, session.state)
            assertEquals(0, port.writes); assertTrue(port.activity.isEmpty())
        }
    }

    @Test fun newInputActionsRequireExactExplicitActiveSession() = runBlocking {
        for (state in listOf("legacy", "paused", "ended", "waiting", "reobserving", "wrong-id", "explicit")) {
            val session = ControlSession().apply {
                if (state == "legacy") activateLegacy() else start()
                when (state) {
                    "paused" -> pause()
                    "ended" -> end()
                    "waiting" -> waitForUser()
                    "reobserving" -> { pause(); beginResume() }
                }
            }
            for (action in listOf("input", "focus")) {
                val port = Port().apply { controlSession = session }
                val input = buildJsonObject { put("action", action); put("session_id", if (state == "wrong-id") "wrong" else session.id) }
                val result = registry(port).get(GenericControlOperations.ACTION)!!.handler.invoke(OperationInvocation("session-test", input))
                assertFalse(result.success)
                assertEquals(when (state) {
                    "wrong-id" -> "control_session_id_mismatch"
                    "explicit" -> "observation_required" // admission succeeded; still needs single-use observation
                    else -> "explicit_active_session_required"
                }, result.summary)
                assertEquals(0, port.writes)
                if (state !in setOf("explicit")) assertTrue(port.activity.isEmpty())
            }
        }
    }
}
