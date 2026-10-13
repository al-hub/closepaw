package ai.closepaw.chatgpt

import ai.closepaw.bridge.*
import ai.closepaw.chatgpt.operations.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** Observes daemon-owned jobs. Re-observation uses persisted links, never resubmits. */
internal class TermuxShellOperation(
    private val client: TermuxJobClient,
    private val links: TermuxJobLinkStore,
) : OperationHandler {
    override suspend fun invoke(invocation: OperationInvocation): OperationResult {
        val input = invocation.input
        val request = ExecutionRequest(
            ExecutionCapability.LINUX_SHELL,
            input.getValue("command").jsonPrimitive.content,
            workingDirectory = input["cwd"]?.jsonPrimitive?.content,
            timeoutMs = input["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 120_000L,
        )
        val reply = client.start(invocation.taskId, request)
        if (reply is TermuxJobReply.Failure) throw IllegalStateException(reply.code)
        val started = (reply as TermuxJobReply.Success).snapshot
        links.put(invocation.taskId, started.jobId)
        while (true) {
            val snapshot = snapshot(invocation.taskId) ?: throw IllegalStateException("bridge_observer_unavailable")
            if (snapshot.terminal) return result(snapshot)
            delay(1000)
        }
    }
    suspend fun snapshot(taskId: String): TermuxJobSnapshot? {
        val jobId = links.get(taskId) ?: return null
        val first = (client.poll(jobId, limit = 65536) as? TermuxJobReply.Success)?.snapshot ?: return null
        if (!first.terminal) return first
        val stdout = StringBuilder(first.stdout)
        val stderr = StringBuilder(first.stderr)
        var current = first
        // Both streams retain at most 128 KiB each. Keep byte offsets separate from decoded text lengths.
        repeat(3) {
            val next = (client.poll(jobId, current.stdoutNextOffset, current.stderrNextOffset, 65536) as? TermuxJobReply.Success)?.snapshot ?: return current.copy(stdout=stdout.toString(), stderr=stderr.toString())
            if(next.stdoutNextOffset == current.stdoutNextOffset && next.stderrNextOffset == current.stderrNextOffset) return current.copy(stdout=stdout.toString(), stderr=stderr.toString())
            stdout.append(next.stdout); stderr.append(next.stderr); current = next
        }
        return current.copy(stdout=stdout.toString(), stderr=stderr.toString())
    }
    override suspend fun cancel(taskId: String): Boolean {
        val jobId = links.get(taskId) ?: return false
        val requested = (client.cancel(jobId) as? TermuxJobReply.Success)?.snapshot ?: return false
        if(requested.cancelConfirmed) return true
        repeat(10) {
            delay(100)
            val observed = (client.poll(jobId) as? TermuxJobReply.Success)?.snapshot ?: return false
            if(observed.cancelConfirmed) return true
            if(observed.terminal) return false
        }
        return false
    }
    fun result(snapshot: TermuxJobSnapshot): OperationResult = OperationResult(
        success = snapshot.state == "succeeded" && snapshot.exitCode == 0 && !snapshot.outcomeUnknown,
        stdout = snapshot.stdout, stderr = snapshot.stderr, exitCode = snapshot.exitCode,
        summary = "Termux job ${snapshot.state}; " + if(snapshot.outcomeUnknown) "outcome not confirmed, do not automatically retry." else "exit code ${snapshot.exitCode ?: "unavailable"}.",
        data = buildJsonObject {
            put("job_id", snapshot.jobId); put("state", snapshot.state); put("adapter_id", "termux-local-bridge-jobs")
            put("timed_out", snapshot.timedOut); put("output_truncated", snapshot.truncated)
            put("outcome_unknown", snapshot.outcomeUnknown); put("duration_ms", snapshot.durationMs)
            put("cancel_confirmed", snapshot.cancelConfirmed)
        },
    )
}
