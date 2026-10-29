package ai.closepaw.platform.virtualdisplay

/**
 * Pure clock-domain boundary for one projection consent generation.
 * SourceClock must be established from an independently trusted producer
 * contract, never selected by comparing image ages against system clocks.
 *
 * This helper intentionally does not choose a clock for MediaProjection.
 * Until a source contract is verified, callers must use UNKNOWN (fail closed).
 */
internal object ProjectionFrameClockValidation {
    enum class SourceClock { UNKNOWN, ELAPSED_REALTIME, UPTIME }

    data class ClockSample(val uptimeMs: Long, val elapsedMs: Long) {
        val offsetMs: Long get() = elapsedMs - uptimeMs
        fun valid() = uptimeMs >= 0L && elapsedMs >= uptimeMs
    }

    /**
     * Returns capture time in elapsedRealtime milliseconds, or null on ambiguity.
     * Bracket samples ensure consistent acquisition-time clocks, but cannot
     * establish the offset at an earlier frame's capture time. In particular,
     * suspension before acquisition can make a stale UPTIME frame look fresh.
     * Refuse UPTIME until trusted capture-through-acquisition offset evidence
     * exists, even if the producer's UPTIME clock is documented.
     */
    fun normalize(
        timestampNs: Long,
        source: SourceClock,
        before: ClockSample,
        after: ClockSample,
        maxAgeMs: Long = 1_000L,
        maxOffsetDriftMs: Long = 50L,
    ): Long? {
        if (source == SourceClock.UNKNOWN || source == SourceClock.UPTIME ||
            timestampNs <= 0L || !before.valid() || !after.valid() ||
            after.uptimeMs < before.uptimeMs || after.elapsedMs < before.elapsedMs ||
            maxAgeMs < 0L || maxOffsetDriftMs < 0L
        ) return null

        val startOffset = before.offsetMs
        val endOffset = after.offsetMs
        if (endOffset < startOffset || endOffset - startOffset > maxOffsetDriftMs) return null

        val sourceMs = timestampNs / 1_000_000L
        if (sourceMs < 0L || sourceMs > after.elapsedMs) return null
        if (after.elapsedMs - sourceMs > maxAgeMs) return null
        return sourceMs
    }
}
