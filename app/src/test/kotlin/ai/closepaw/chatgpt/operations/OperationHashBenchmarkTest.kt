package ai.closepaw.chatgpt.operations

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** No speed assertion: host scheduling and JIT make latency thresholds flaky.
 * This compares only the SHA hex helper, never Android traversal or Voice E2E. */
class OperationHashBenchmarkTest {
    @Test fun producesTenPairedHostSamplesWithoutChangingFingerprints() {
        val inputs = (0..255).map { "node-$it:한글:visible=true:enabled=true:${"field".repeat(it % 8)}" }
        fun previous(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val expected = inputs.map(::previous)
        fun measure(hash: (String) -> String): Long {
            val start = System.nanoTime()
            val actual = inputs.map(hash)
            val elapsed = System.nanoTime() - start
            assertEquals(expected, actual)
            return elapsed
        }
        repeat(3) { measure(::previous); measure(::operationHash) }
        val samples = (0..9).map { index ->
            // Alternate order to reduce systematic first-run bias.
            val pair = if (index % 2 == 0) measure(::previous) to measure(::operationHash)
                else { val optimized = measure(::operationHash); measure(::previous) to optimized }
            buildJsonObject { put("sample", index + 1); put("baseline_ns", pair.first); put("optimized_ns", pair.second) }
        }
        val sourceCommit = System.getenv("GITHUB_SHA")?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
            ?: ProcessBuilder("git", "rev-parse", "HEAD").start().let { process ->
                check(process.waitFor(3, TimeUnit.SECONDS)) { "Cannot resolve benchmark source commit" }
                check(process.exitValue() == 0) { "Cannot resolve benchmark source commit" }
                process.inputStream.bufferedReader().use { it.readText().trim() }
            }
        check(sourceCommit.matches(Regex("[0-9a-f]{40}"))) { "Immutable source commit is required" }
        val conditions = buildJsonObject {
            put("device", "same_host_process_" + System.getProperty("os.arch"))
            put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
            put("runtime", System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version"))
            put("configuration", "warmup_pairs=3; ten paired trials; alternating arm order")
            put("workload", "256 deterministic UTF-8 synthetic accessibility-like strings per trial")
            put("execution_path", "in-process SHA256 digest and hex conversion; no Android traversal")
            put("network", "not_applicable")
            put("scope", "host_jvm_sha256_hex; not_android_or_voice_e2e")
            put("clock_domain", "process_monotonic")
        }
        fun cohort(field: String, implementation: String) = buildJsonObject {
            put("source_commit", sourceCommit)
            put("implementation", implementation)
            put("conditions", conditions)
            put("samples", JsonArray(samples.map { sample ->
                val durationNs = sample.getValue(field).jsonPrimitive.long
                buildJsonObject {
                    put("sample", sample.getValue("sample"))
                    put("duration_ns", durationNs)
                    put("duration_ms", durationNs / 1_000_000.0)
                    put("correct", true); put("calls", inputs.size); put("retries", 0)
                    put("stage_ms", buildJsonObject { put("sha256_hex", durationNs / 1_000_000.0) })
                }
            }))
        }
        val output = File("build/reports/benchmarks/operation-hash.json")
        output.parentFile.mkdirs()
        output.writeText(buildJsonObject {
            put("scope", "host_jvm_sha256_hex_256_inputs; not_android_or_voice_e2e")
            put("clock_domain", "process_monotonic"); put("warmup_pairs", 3)
            put("samples_per_arm", 10); put("identical_outputs", true)
            put("paired_raw_samples", JsonArray(samples))
            put("baseline", cohort("baseline_ns", "previous String.format per-byte hex recreated in this test"))
            put("candidate", cohort("optimized_ns", "production operationHash fixed-output hex"))
        }.toString())
    }
}
