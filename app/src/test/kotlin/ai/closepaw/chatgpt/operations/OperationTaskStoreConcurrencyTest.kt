package ai.closepaw.chatgpt.operations

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OperationTaskStoreConcurrencyTest {
    private fun task(id: String, request: String = id, fingerprint: String = "same") = buildJsonObject {
        put("task_id", id); put("request_id", request); put("fingerprint", fingerprint); put("state", "queued")
    }
    @Test fun independentInstancesRetainAllConcurrentWrites() {
        val file = File.createTempFile("task-ledger", ".json").apply { delete() }
        val stores = listOf(OperationTaskStore(file), OperationTaskStore(File(file.parentFile, "./${file.name}")))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val writes = (0 until 80).map { index -> executor.submit {
                check(start.await(5, TimeUnit.SECONDS))
                stores[index % 2].put(task("t$index"))
                stores[(index + 1) % 2].updateActive("t$index", mapOf("state" to JsonPrimitive("succeeded")))
            } }
            start.countDown()
            writes.forEach { it.get(20, TimeUnit.SECONDS) }
            for(index in 0 until 80) {
                assertEquals("succeeded", stores[0].get("t$index")!!["state"]!!.jsonPrimitive.content)
                assertEquals("t$index", stores[1].byRequest("t$index")!!["task_id"]!!.jsonPrimitive.content)
            }
            assertEquals(80, Json.parseToJsonElement(file.readText()).jsonArray.size)
        } finally { executor.shutdownNow() }
    }
    @Test fun concurrentClaimsCreateOneDurableRequestAndRejectDifferentFingerprint() {
        val file = File.createTempFile("task-ledger", ".json").apply { delete() }
        val stores = listOf(OperationTaskStore(file), OperationTaskStore(file))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val writes = (0 until 40).map { index -> executor.submit<OperationTaskClaim> {
                check(start.await(5, TimeUnit.SECONDS))
                stores[index % 2].claim(task("candidate$index", "one-request"))
            } }
            start.countDown()
            val claims = writes.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, claims.count { it.created })
            assertEquals(1, claims.map { it.task["task_id"] }.distinct().size)
            assertFalse(claims.any { it.conflict })
            val conflicting = stores[0].claim(task("other", "one-request", "different"))
            assertTrue(conflicting.conflict)
            assertFalse(conflicting.created)
            assertEquals(1, Json.parseToJsonElement(file.readText()).jsonArray.size)
        } finally { executor.shutdownNow() }
    }
    @Test fun crossInstanceTerminalGuardAndRestartRecoveryStayConsistent() {
        val file = File.createTempFile("task-ledger", ".json").apply { delete() }
        val first = OperationTaskStore(file)
        first.put(task("active"))
        first.put(JsonObject(task("complete") + ("state" to JsonPrimitive("succeeded"))))
        val recovered = OperationTaskStore(file)
        assertEquals("outcome_unknown", recovered.get("active")!!["state"]!!.jsonPrimitive.content)
        assertEquals("outcome_unknown", first.get("active")!!["state"]!!.jsonPrimitive.content)
        assertFalse(first.updateActive("complete", mapOf("state" to JsonPrimitive("running"))))
        assertEquals("succeeded", recovered.get("complete")!!["state"]!!.jsonPrimitive.content)
    }
    @Test fun corruptLedgerCannotBeReplacedByAnExistingInstance() {
        val file = File.createTempFile("task-ledger", ".json").apply { delete() }
        val first = OperationTaskStore(file)
        first.put(task("original"))
        file.writeText("corrupt")
        try { first.put(task("new")); fail("corrupt ledger accepted") } catch (_: IllegalArgumentException) {}
        assertEquals("corrupt", file.readText())
    }
}
