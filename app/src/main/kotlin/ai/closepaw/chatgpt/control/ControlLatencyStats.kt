package ai.closepaw.chatgpt.control

import kotlin.math.ceil

internal data class ControlLatencySummary(
    val count: Int,
    val minMs: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val maxMs: Long,
    val meanMs: Double,
)

/** Statistics only; the caller must verify identical conditions and the timing source. */
internal object ControlLatencyStats {
    fun summarize(samplesMs: List<Long>): ControlLatencySummary? {
        if (samplesMs.size < 10 || samplesMs.any { it < 0 }) return null
        val sorted = samplesMs.sorted()
        fun percentile(p: Double): Long =
            sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(sorted.indices)]
        return ControlLatencySummary(
            count = sorted.size,
            minMs = sorted.first(),
            p50Ms = percentile(0.5),
            p95Ms = percentile(0.95),
            p99Ms = percentile(0.99),
            maxMs = sorted.last(),
            meanMs = sorted.map(Long::toDouble).average(),
        )
    }
}
