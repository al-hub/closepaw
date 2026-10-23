package ai.closepaw.chatgpt.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ControlLatencyStatsTest {
    @Test fun `ten measurements produce nearest rank latency percentiles`() {
        val samples = listOf(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L)
        val result = ControlLatencyStats.summarize(samples)
        assertThat(result).isNotNull()
        assertThat(result!!.count).isEqualTo(10)
        assertThat(result.p50Ms).isEqualTo(5L)
        assertThat(result.p95Ms).isEqualTo(10L)
        assertThat(result.p99Ms).isEqualTo(10L)
        assertThat(result.meanMs).isWithin(0.0001).of(5.5)
        assertThat(result.minMs).isEqualTo(1L)
        assertThat(result.maxMs).isEqualTo(10L)
    }

    @Test fun `insufficient or invalid samples never produce a benchmark claim`() {
        assertThat(ControlLatencyStats.summarize(emptyList())).isNull()
        assertThat(ControlLatencyStats.summarize(List(9) { 100L })).isNull()
        assertThat(ControlLatencyStats.summarize(List(9) { 100L } + -1L)).isNull()
    }

    @Test fun `large values do not overflow during aggregation`() {
        val result = ControlLatencyStats.summarize(List(10) { Long.MAX_VALUE })
        assertThat(result).isNotNull()
        assertThat(result!!.p95Ms).isEqualTo(Long.MAX_VALUE)
        assertThat(result.meanMs).isGreaterThan(0.0)
    }
}
