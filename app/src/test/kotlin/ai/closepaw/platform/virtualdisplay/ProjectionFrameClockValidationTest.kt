package ai.closepaw.platform.virtualdisplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectionFrameClockValidationTest {
    private val clock = ProjectionFrameClockValidation

    @Test fun `observed paired samples stay blocked without trusted producer contract`() {
        val samples = listOf(
            Triple(596111006200151L, 792317482L, 596111022L),
            Triple(596112508350502L, 792318976L, 596112516L),
        )
        for ((ns, elapsed, uptime) in samples) {
            val before = ProjectionFrameClockValidation.ClockSample(uptime - 2, elapsed - 2)
            val after = ProjectionFrameClockValidation.ClockSample(uptime, elapsed)
            assertThat(clock.normalize(ns, clock.SourceClock.UNKNOWN, before, after)).isNull()
            assertThat(clock.normalize(ns, clock.SourceClock.UPTIME, before, after))
                .isEqualTo((ns / 1_000_000L) + (elapsed - uptime))
            assertThat(clock.normalize(ns, clock.SourceClock.ELAPSED_REALTIME, before, after)).isNull()
        }
    }

    @Test fun `suspend offset jump and future timestamps fail closed`() {
        val before = ProjectionFrameClockValidation.ClockSample(1000, 1200)
        val suspended = ProjectionFrameClockValidation.ClockSample(1005, 2205)
        assertThat(clock.normalize(1002_000_000L, clock.SourceClock.UPTIME, before, suspended)).isNull()
        val after = ProjectionFrameClockValidation.ClockSample(1005, 1205)
        assertThat(clock.normalize(1006_000_000L, clock.SourceClock.UPTIME, before, after)).isNull()
        assertThat(clock.normalize(-1L, clock.SourceClock.UPTIME, before, after)).isNull()
    }

    @Test fun `elapsed source obeys age and bracket guards`() {
        val before = ProjectionFrameClockValidation.ClockSample(2000, 2200)
        val after = ProjectionFrameClockValidation.ClockSample(2010, 2210)
        assertThat(clock.normalize(2_205_000_000L, clock.SourceClock.ELAPSED_REALTIME, before, after))
            .isEqualTo(2205L)
        assertThat(clock.normalize(1_000_000L, clock.SourceClock.ELAPSED_REALTIME, before, after))
            .isNull()
        assertThat(clock.normalize(2_211_000_000L, clock.SourceClock.ELAPSED_REALTIME, before, after))
            .isNull()
    }
}
