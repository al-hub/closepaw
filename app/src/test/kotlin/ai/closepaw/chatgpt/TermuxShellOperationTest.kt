package ai.closepaw.chatgpt

import ai.closepaw.bridge.*
import ai.closepaw.chatgpt.operations.*
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TermuxShellOperationTest {
    @get:Rule val directory = TemporaryFolder()
    private val taskId = UUID.randomUUID().toString()
    private val jobId = UUID.randomUUID().toString().replace("-", "")
    @Test fun nonzeroExitPreservesOutputAndFailure() = runTest {
        val client = mockk<TermuxJobClient>()
        val completed = TermuxJobSnapshot(jobId,"failed",stdout="out",stderr="err",exitCode=7)
        coEvery { client.start(any(),any()) } returns TermuxJobReply.Success(completed)
        coEvery { client.poll(any(),any(),any(),any()) } returns TermuxJobReply.Success(completed)
        val operation = TermuxShellOperation(client,TermuxJobLinkStore(File(directory.root,"links")))
        val result = operation.invoke(OperationInvocation(taskId,buildJsonObject { put("command","false") }))
        assertThat(result.success).isFalse(); assertThat(result.exitCode).isEqualTo(7)
        assertThat(result.stdout).isEqualTo("out"); assertThat(result.stderr).isEqualTo("err")
    }
    @Test fun restartReobservesStoredJobWithoutSubmitting() = runTest {
        val file = File(directory.root,"links")
        TermuxJobLinkStore(file).put(taskId,jobId)
        val client = mockk<TermuxJobClient>()
        coEvery { client.poll(any(),any(),any(),any()) } returns TermuxJobReply.Success(TermuxJobSnapshot(jobId,"succeeded",exitCode=0))
        val operation = TermuxShellOperation(client,TermuxJobLinkStore(file))
        assertThat(operation.snapshot(taskId)!!.state).isEqualTo("succeeded")
        coVerify(exactly=0) { client.start(any(),any()) }
    }
    @Test fun cancellationRequiresExecutorConfirmation() = runTest {
        val file = File(directory.root,"links"); TermuxJobLinkStore(file).put(taskId,jobId)
        val client = mockk<TermuxJobClient>()
        coEvery { client.cancel(any()) } returns TermuxJobReply.Success(TermuxJobSnapshot(jobId,"running"))
        coEvery { client.poll(any(),any(),any(),any()) } returns TermuxJobReply.Success(TermuxJobSnapshot(jobId,"cancelled",cancelConfirmed=true))
        assertThat(TermuxShellOperation(client,TermuxJobLinkStore(file)).cancel(taskId)).isTrue()
    }
    @Test fun fullJobLinkLedgerNeverSubmitsCommand() = runTest {
        val file = File(directory.root, "full-links")
        file.writeText((0 until 1000).joinToString("\n", postfix = "\n") {
            "${UUID(0, it.toLong())}\t${UUID(1, it.toLong())}"
        })
        val client = mockk<TermuxJobClient>()
        val operation = TermuxShellOperation(client, TermuxJobLinkStore(file))
        val result = operation.invoke(OperationInvocation(taskId, buildJsonObject { put("command", "kv") }))
        assertThat(result.success).isFalse()
        assertThat(result.summary).contains("not submitted")
        coVerify(exactly = 0) { client.start(any(), any()) }
        coVerify(exactly = 0) { client.poll(any(), any(), any(), any()) }
    }

    @Test fun fullLedgerReclaimsDurableResultAndReturnsNewExitAndOutputWithoutReplay() = runTest {
        val file = File(directory.root, "full-links")
        file.writeText((0 until 1000).joinToString("\n", postfix = "\n") {
            "${UUID(0, it.toLong())}\t${UUID(1, it.toLong())}"
        })
        val tasks = OperationTaskStore(File(directory.root, "tasks.json"))
        val oldTask = UUID(0, 0).toString()
        val oldJob = UUID(1, 0).toString()
        val client = mockk<TermuxJobClient>()
        val completed = TermuxJobSnapshot(jobId, "succeeded", stdout="bridge-ok\n", stderr="", exitCode=0)
        coEvery { client.start(any(), any()) } returns TermuxJobReply.Success(completed)
        coEvery { client.poll(any(), any(), any(), any()) } returns TermuxJobReply.Success(completed)
        tasks.put(buildJsonObject {
            put("task_id", oldTask); put("request_id", "retained-request"); put("fingerprint", "original")
            put("operation_id", "linux.shell.exec"); put("state", "succeeded")
            put("result", buildJsonObject {
                put("status", "succeeded"); put("stdout", "old-output"); put("stderr", ""); put("exit_code", 0)
                put("data", buildJsonObject { put("job_id", oldJob); put("state", "succeeded"); put("outcome_unknown", false) })
            })
        })
        val original = tasks.get(oldTask)
        val links = TermuxJobLinkStore(file, tasks::retainedShellLinks)
        val operation = TermuxShellOperation(client, links)
        val invocation = OperationInvocation(taskId, buildJsonObject { put("command", "printf 'bridge-ok\\n'") })
        val result = operation.invoke(invocation)
        assertThat(result.success).isTrue()
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).isEqualTo("bridge-ok\n")
        assertThat(links.get(oldTask)).isNull()
        assertThat(OperationTaskStore(File(directory.root, "tasks.json")).byRequest("retained-request")).isEqualTo(original)
        assertThat(operation.invoke(invocation).stdout).isEqualTo(result.stdout)
        coVerify(exactly=1) { client.start(taskId, any()) }
    }

    @Test fun definiteRefusalReleasesOnlyUnsubmittedReservation() = runTest {
        val file = File(directory.root, "links")
        val links = TermuxJobLinkStore(file)
        val client = mockk<TermuxJobClient>()
        coEvery { client.start(any(), any()) } returns TermuxJobReply.Failure("bridge_auth_mismatch", "rejected", 401)
        val result = TermuxShellOperation(client, links).invoke(OperationInvocation(taskId,
            buildJsonObject { put("command", "printf once") }))
        assertThat(result.summary).contains("not submitted")
        assertThat(links.reserve(UUID.randomUUID().toString())).isEqualTo(TermuxJobLinkStore.Admission.RESERVED)
        assertThat(file.readLines()).hasSize(1)
        coVerify(exactly=1) { client.start(any(), any()) }
    }

    @Test fun ambiguousSubmissionNeverAutomaticallyReplays() = runTest {
        val file = File(directory.root, "links")
        val client = mockk<TermuxJobClient>()
        coEvery { client.start(any(), any()) } returns TermuxJobReply.Failure("bridge_transport_error", "lost response")
        val input = OperationInvocation(taskId, buildJsonObject { put("command", "printf once") })
        try { TermuxShellOperation(client, TermuxJobLinkStore(file)).invoke(input) } catch (_: IllegalStateException) { }
        try { TermuxShellOperation(client, TermuxJobLinkStore(file)).invoke(input) } catch (_: IllegalStateException) { }
        coVerify(exactly=1) { client.start(any(), any()) }
    }

    @Test fun laterOutputPageFailureDoesNotReturnPartialConfirmedSnapshot() = runTest {
        val file = File(directory.root, "links")
        TermuxJobLinkStore(file).put(taskId, jobId)
        val client = mockk<TermuxJobClient>()
        coEvery { client.poll(any(), any(), any(), any()) } returnsMany listOf(
            TermuxJobReply.Success(TermuxJobSnapshot(jobId, "succeeded", stdout = "first page",
                exitCode = 0, stdoutNextOffset = 10)),
            TermuxJobReply.Failure("bridge_http_error", "Local bridge HTTP 500", 500),
        )
        val operation = TermuxShellOperation(client, TermuxJobLinkStore(file))
        assertThat(operation.snapshot(taskId)).isNull()
        coVerify(exactly = 2) { client.poll(any(), any(), any(), 32768) }
        coVerify(exactly = 0) { client.start(any(), any()) }
    }

}
