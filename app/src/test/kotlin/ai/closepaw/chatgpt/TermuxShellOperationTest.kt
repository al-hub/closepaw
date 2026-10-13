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
