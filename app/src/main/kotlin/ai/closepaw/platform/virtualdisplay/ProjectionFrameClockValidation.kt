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
     * The two samples bracket acquisition and must have a stable suspend offset.
     * A valid uptime timestamp is mapped into the elapsed clock only after the
     * caller supplies an explicitly verified source contract.
     */
    fun normalize(
        timestampNs: Long,
        source: SourceClock,
        before: ClockSample,
        after: ClockSample,
        maxAgeMs: Long = 1_000L,
        maxOffsetDriftMs: Long = 50L,
    ): Long? {
        if (source == SourceClock.UNKNOWN || timestampNs <= 0L ||
            !before.valid() || !after.valid() ||
            after.uptimeMs < before.uptimeMs || after.elapsedMs < before.elapsedMs ||
            maxAgeMs < 0L || maxOffsetDriftMs < 0L
        ) return null

        val startOffset = before.offsetMs
        val endOffset = after.offsetMs
        if (endOffset < startOffset || endOffset - startOffset > maxOffsetDriftMs) return null

        val sourceMs = timestampNs / 1_000_000L
        val normalized = when (source) {
            SourceClock.UNKNOWN -> return null
            SourceClock.ELAPSED_REALTIME -> sourceMs
            SourceClock.UPTIME -> {
                if (sourceMs > after.uptimeMs || before.uptimeMs - sourceMs > maxAgeMs) return null
                if (sourceMs > Long.MAX_VALUE - endOffset) return null
                sourceMs + endOffset
            }
        }
        if (normalized < 0L || normalized > after.elapsedMs) return null
        if (after.elapsedMs - normalized > maxAgeMs) return null
        return normalized
    }
}
