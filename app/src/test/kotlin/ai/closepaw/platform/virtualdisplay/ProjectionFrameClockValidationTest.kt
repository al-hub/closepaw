package ai.closepaw.platform.virtualdisplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectionFrameClockValidationTest {
    private val clock = ProjectionFrameClockValidation
    private val unknown = ProjectionFrameClockValidation.SourceClock.UNKNOWN
    private val uptime = ProjectionFrameClockValidation.SourceClock.UPTIME
    private val elapsed = ProjectionFrameClockValidation.SourceClock.ELAPSED_REALTIME

    @Test fun `observed paired samples stay blocked without trusted producer contract`() {
        val samples = listOf(
            Triple(596111006200151L, 792317482L, 596111022L),
            Triple(596112508350502L, 792318976L, 596112516L),
        )
        for ((ns, elapsedMs, uptimeMs) in samples) {
            // Synthetic bracketing proves arithmetic, not actual capture-time offset.
            val before = ProjectionFrameClockValidation.ClockSample(uptimeMs - 2, elapsedMs - 2)
            val after = ProjectionFrameClockValidation.ClockSample(uptimeMs, elapsedMs)
            assertThat(clock.normalize(ns, unknown, before, after)).isNull()
            assertThat(clock.normalize(ns, uptime, before, after)).isNull()
            assertThat(clock.normalize(ns, elapsed, before, after)).isNull()
        }
    }

    @Test fun `suspend before acquisition cannot freshen an old uptime frame`() {
        // Captured at (uptime 1000, elapsed 1200), then slept 10000 ms.
        val before = ProjectionFrameClockValidation.ClockSample(1001, 11201)
        val after = ProjectionFrameClockValidation.ClockSample(1002, 11202)
        assertThat(clock.normalize(1_000_000_000L, uptime, before, after)).isNull()
    }

    @Test fun `small allowed offset drift cannot freshen an old uptime frame`() {
        val before = ProjectionFrameClockValidation.ClockSample(1999, 2199)
        val after = ProjectionFrameClockValidation.ClockSample(2000, 2250)
        assertThat(clock.normalize(1_000_000_000L, uptime, before, after)).isNull()
    }

    @Test fun `suspend offset jump and future timestamps fail closed`() {
        val before = ProjectionFrameClockValidation.ClockSample(1000, 1200)
        val suspended = ProjectionFrameClockValidation.ClockSample(1005, 2205)
        assertThat(clock.normalize(1002_000_000L, uptime, before, suspended)).isNull()
        val after = ProjectionFrameClockValidation.ClockSample(1005, 1205)
        assertThat(clock.normalize(1006_000_000L, uptime, before, after)).isNull()
        assertThat(clock.normalize(-1L, uptime, before, after)).isNull()
    }

    @Test fun `elapsed source obeys age and bracket guards`() {
        val before = ProjectionFrameClockValidation.ClockSample(2000, 2200)
        val after = ProjectionFrameClockValidation.ClockSample(2010, 2210)
        assertThat(clock.normalize(2_205_000_000L, elapsed, before, after)).isEqualTo(2205L)
        assertThat(clock.normalize(1_000_000L, elapsed, before, after)).isNull()
        assertThat(clock.normalize(2_211_000_000L, elapsed, before, after)).isNull()
        assertThat(clock.normalize(2_205_000_000L, unknown, before, after)).isNull()
    }
}
