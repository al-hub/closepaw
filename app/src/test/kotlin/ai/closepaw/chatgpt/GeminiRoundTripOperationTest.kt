package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import org.junit.Test

class GeminiRoundTripOperationTest {
    private class Ui : GeminiRoundTripUi {
        var locked = false
        var enabled = true
        var foreground = true
        var draft = ""
        var clicks = 0
        var mode = "complete"
        var polls = 0
        var onSnapshot: (() -> Unit)? = null
        override suspend fun isLocked() = locked
        override suspend fun launch() = foreground
        override suspend fun inspect() = DraftComposerState(foreground, 1, draft)
        override suspend fun setIfEmpty(text: String): DraftWriteResult {
            if (draft.isNotBlank()) return DraftWriteResult.EXISTING_DRAFT
            draft = text; return DraftWriteResult.WRITTEN
        }
        override suspend fun matches(text: String) = draft == text
        override suspend fun snapshot(): GeminiUiSnapshot {
            onSnapshot?.invoke()
            val sent = clicks > 0
            if (sent) polls++
            return GeminiUiSnapshot(foreground, "native:1", 1, if(sent) "" else draft, 1, true,
                generating = if(!sent) false else mode == "partial",
                submittedQuestion = if(sent && mode != "unknown") QUESTION else null,
                replies = if(sent && mode in setOf("complete", "partial")) listOf(GeminiReply("new", "answer", mode == "complete")) else emptyList())
        }
        override suspend fun sendIfMatches(text: String, targetKey: String): GeminiSendResult {
            if (mode == "switch") return GeminiSendResult.TARGET_CHANGED
            clicks++
            if (mode == "throw") error("post click failure")
            return GeminiSendResult.CLICKED
        }
    }
    private fun input(extra: JsonObject = buildJsonObject {}) = JsonObject(mapOf("text" to JsonPrimitive(QUESTION)) + extra)
    private fun invocation(extra: JsonObject = buildJsonObject {}) = OperationInvocation("task", input(extra))
    private fun operation(ui: Ui, events: MutableList<String> = mutableListOf(), record: ((String,String,String)->Unit)? = null): GeminiRoundTripOperation {
        return GeminiRoundTripOperation(ui, { ui.enabled }, record ?: { _, stage, reason -> events.add("$stage:$reason") }, { timeout ->
            var time = 0L
            GeminiReplyCollector(ui, { ui.enabled }, clockMillis = { time }, pause = { time += it },
                timeoutMs = timeout, pollMs = 100, stableMs = 100, maxPolls = 100)
        })
    }
    @Test fun successPersistsSubmittingBeforeSingleClickAndReturnsOnlyNewAnswer() = runBlocking {
        val ui = Ui(); val events = mutableListOf<String>()
        val result = operation(ui, events, record = { _, stage, reason ->
            if (stage == "submitting") assertThat(ui.clicks).isEqualTo(0)
            events.add("$stage:$reason")
        }).invoke(invocation())
        assertThat(result.success).isTrue(); assertThat(ui.clicks).isEqualTo(1)
        assertThat(result.stdout).isEqualTo("answer")
        assertThat(events.map { it.substringBefore(':') }).containsAtLeast("not_sent", "submitting", "send_action_invoked", "waiting", "sent", "completed").inOrder()
        assertThat(events.toString()).doesNotContain(QUESTION)
        assertThat(events.toString()).doesNotContain("answer")
    }
    @Test fun existingDraftAndLockPermissionAndSearchNeverSend() = runBlocking {
        for (configure in listOf<Ui.()->Unit>({ draft="preserve" }, { locked=true }, { enabled=false }, { foreground=false })) {
            val ui = Ui().apply(configure)
            val result = operation(ui).invoke(invocation())
            assertThat(result.success).isFalse(); assertThat(ui.clicks).isEqualTo(0)
            if(ui.draft == "preserve") assertThat(ui.draft).isEqualTo("preserve")
        }
    }
    @Test fun persistenceFailureBeforeSendPreventsClick() = runBlocking {
        val ui = Ui()
        val result = operation(ui, record = { _, stage, _ -> if(stage=="submitting") error("disk full") }).invoke(invocation())
        assertThat(ui.clicks).isEqualTo(0); assertThat(result.success).isFalse()
    }
    @Test fun postClickLossIsUnknownAndNeverRetried() = runBlocking {
        val ui = Ui().apply { mode="throw" }
        val result = operation(ui).invoke(invocation())
        assertThat(ui.clicks).isEqualTo(1)
        assertThat(result.data["outcome_unknown"]!!.jsonPrimitive.boolean).isTrue()
    }
    @Test fun uncorrelatedClickIsUnknownAndNeverClaimsQuestionSent() = runBlocking {
        val ui=Ui().apply { mode="unknown" }
        val result=operation(ui).invoke(invocation(buildJsonObject { put("timeout_ms",1000) }))
        assertThat(ui.clicks).isEqualTo(1)
        assertThat(result.data["sent"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(result.data["send_action_invoked"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(result.data["outcome_unknown"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(result.summary).doesNotContain("Question submitted")
    }
    @Test fun targetSwitchDoesNotClick() = runBlocking {
        val ui = Ui().apply { mode="switch" }
        assertThat(operation(ui).invoke(invocation()).success).isFalse()
        assertThat(ui.clicks).isEqualTo(0)
    }
    @Test fun timeoutKeepsPartialAnswerAndDoesNotResend() = runBlocking {
        val ui = Ui().apply { mode="partial" }
        val result = operation(ui).invoke(invocation(buildJsonObject { put("timeout_ms",1000) }))
        assertThat(ui.clicks).isEqualTo(1); assertThat(result.success).isFalse()
        assertThat(result.stdout).isEqualTo("answer"); assertThat(result.data["partial"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(result.data["timed_out"]!!.jsonPrimitive.boolean).isTrue()
    }
    @Test fun unsupportedConversationAndInvalidTimeoutAreRejectedBeforeLaunch() = runBlocking {
        for (extra in listOf(buildJsonObject { put("conversation","new") }, buildJsonObject { put("timeout_ms",999) })) {
            val ui=Ui(); assertThat(operation(ui).invoke(invocation(extra)).success).isFalse(); assertThat(ui.clicks).isEqualTo(0)
            assertThat(OperationSchemaValidator.validate(GeminiRoundTripOperation.descriptor().inputSchema,input(extra))).isNotNull()
        }
    }
    @Test fun cancellationStopsObserverWithoutClaimingGenerationStopped() = runBlocking {
        val ui=Ui().apply { mode="partial" }; val op=operation(ui)
        ui.onSnapshot = { if(ui.clicks>0 && ui.polls>0) runBlocking { assertThat(op.cancel("task")).isFalse() } }
        val result=op.invoke(invocation())
        assertThat(ui.clicks).isEqualTo(1); assertThat(result.success).isFalse()
        assertThat(result.data["observation_stopped"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(result.data["generation_cancelled"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(result.stdout).isEqualTo("answer"); assertThat(result.data["partial"]!!.jsonPrimitive.boolean).isTrue()
    }
    @Test fun cancellationBeforeSendPreventsAnyLaterClick() = runBlocking {
        val ui=Ui(); val op=operation(ui)
        ui.onSnapshot = { runBlocking { assertThat(op.cancel("task")).isFalse() } }
        val result=op.invoke(invocation())
        assertThat(ui.clicks).isEqualTo(0)
        assertThat(result.data["sent"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(result.data["observation_stopped"]!!.jsonPrimitive.boolean).isTrue()
    }
    @Test fun duplicateRequestAndRestartReturnSameTaskWithoutSendingAgain() = runBlocking {
        val file=Files.createTempDirectory("gemini-flow").resolve("tasks.json").toFile()
        try {
            val ui=Ui(); val descriptor=GeminiRoundTripOperation.descriptor()
            val registry=OperationRegistry().apply { register(descriptor,operation(ui)) }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val dispatcher=OperationDispatcher(registry,OperationTaskStore(file),scope)
            val request=buildJsonObject {
                put("operation_id",descriptor.id); put("operation_version",descriptor.version)
                put("schema_hash",descriptor.schemaHash); put("request_id","same-request"); put("input",input())
            }
            val first=dispatcher.execute(request); val second=dispatcher.execute(request)
            assertThat(first["task_id"]).isEqualTo(second["task_id"]); assertThat(ui.clicks).isEqualTo(1)
            val restarted=OperationDispatcher(registry,OperationTaskStore(file),scope).execute(request)
            assertThat(restarted["task_id"]).isEqualTo(first["task_id"]); assertThat(ui.clicks).isEqualTo(1)
            scope.cancel()
        } finally { file.parentFile.deleteRecursively() }
    }
    @Test fun restartDuringObservationPreservesSentBoundaryAndNeverResends() = runBlocking {
        val file=Files.createTempDirectory("gemini-restart").resolve("tasks.json").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val ui=Ui().apply { mode="partial" }; val descriptor=GeminiRoundTripOperation.descriptor()
            val store=OperationTaskStore(file)
            val op=GeminiRoundTripOperation(ui,{true},{ task, stage, reason ->
                check(store.updateActive(task,mapOf("submission_state" to JsonPrimitive(stage),"reason_code" to JsonPrimitive(reason))))
            },{ timeout -> GeminiReplyCollector(ui,timeoutMs=timeout,pause={ awaitCancellation() }) })
            val registry=OperationRegistry().apply { register(descriptor,op) }
            val request=buildJsonObject {
                put("operation_id",descriptor.id); put("operation_version",descriptor.version)
                put("schema_hash",descriptor.schemaHash); put("request_id","restart-request"); put("input",input())
            }
            val accepted=OperationDispatcher(registry,store,scope).execute(request)
            assertThat(ui.clicks).isEqualTo(1)
            val restartStore=OperationTaskStore(file)
            val replay=OperationDispatcher(registry,restartStore,scope).execute(request)
            assertThat(replay["task_id"]).isEqualTo(accepted["task_id"])
            assertThat(replay["state"]!!.jsonPrimitive.content).isEqualTo("outcome_unknown")
            assertThat(replay["submission_state"]!!.jsonPrimitive.content).isEqualTo("waiting")
            assertThat(ui.clicks).isEqualTo(1)
        } finally { scope.cancel(); file.parentFile.deleteRecursively() }
    }
    companion object { private const val QUESTION = "private question" }
}
