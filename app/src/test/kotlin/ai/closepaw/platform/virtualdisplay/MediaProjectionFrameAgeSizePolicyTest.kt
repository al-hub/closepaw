package ai.closepaw.platform.virtualdisplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM security/regression tests for frame-age and geometry bounds of the issue #165
 * diagnostic mirror policy. Proves freshness and size budget boundaries are inclusive
 * at the documented edge, fail closed on unknown/future clocks and malformed (zero,
 * negative, overflow-scale) geometry, and that no input can flip them open.
 */
class MediaProjectionFrameAgeSizePolicyTest {

    private val BOUND_MS = 1_000L
    private val BUDGET_PX = 1_000_000L

    // ── Frame age ────────────────────────────────────────────────────

    @Test
    fun `exact age bound is fresh and one millisecond past it is stale`() {
        val policy = MediaProjectionFramePolicy(maxFrameAgeMs = BOUND_MS)
        // Inclusive upper edge.
        assertThat(policy.isFrameFresh(capturedAtMs = 0, nowMs = BOUND_MS)).isTrue()
        // One past the bound is refused.
        assertThat(policy.isFrameFresh(capturedAtMs = 0, nowMs = BOUND_MS + 1)).isFalse()
        // Far past the bound stays refused (no wraparound at large scales).
        assertThat(policy.isFrameFresh(capturedAtMs = 0, nowMs = Long.MAX_VALUE)).isFalse()
    }

    @Test
    fun `zero max age accepts only ageless frames`() {
        val policy = MediaProjectionFramePolicy(maxFrameAgeMs = 0)
        assertThat(policy.isFrameFresh(capturedAtMs = 0, nowMs = 0)).isTrue()
        assertThat(policy.isFrameFresh(capturedAtMs = 1, nowMs = 1)).isTrue()
        assertThat(policy.isFrameFresh(capturedAtMs = 0, nowMs = 1)).isFalse()
    }

    @Test
    fun `future-clocked and unknown-clocked frames never pass at any bound`() {
        val generous = MediaProjectionFramePolicy(maxFrameAgeMs = Long.MAX_VALUE)
        assertThat(generous.isFrameFresh(capturedAtMs = 100, nowMs = 99)).isFalse()
        assertThat(generous.isFrameFresh(capturedAtMs = -1, nowMs = 99)).isFalse()
        assertThat(generous.isFrameFresh(capturedAtMs = Long.MIN_VALUE, nowMs = 0)).isFalse()
        // A mono-clock regression (now walked backwards) is treated as a future frame.
        assertThat(generous.isFrameFresh(capturedAtMs = Long.MAX_VALUE - 1, nowMs = 0)).isFalse()
    }

    @Test
    fun `freshness is monotonic in capture time for a fixed now`() {
        val policy = MediaProjectionFramePolicy(maxFrameAgeMs = BOUND_MS)
        val now = 50_000L
        val ages = longArrayOf(0, 1, 500, BOUND_MS)
        for (age in ages) {
            assertThat(policy.isFrameFresh(now - age, now)).isTrue()
        }
        for (age in (BOUND_MS + 1)..200_000L step 997) {
            assertThat(policy.isFrameFresh(now - age, now)).isFalse()
        }
    }

    // ── Geometry size budget ─────────────────────────────────────────

    @Test
    fun `exact pixel budget is accepted and one pixel over is refused`() {
        val policy = MediaProjectionFramePolicy(maxMirrorPixels = BUDGET_PX)
        assertThat(policy.acceptsMirrorGeometry(1_000, 1_000)).isTrue()
        assertThat(policy.acceptsMirrorGeometry(1_000, 1_001)).isFalse()
        assertThat(policy.acceptsMirrorGeometry(4_096, 256)).isTrue() // exactly BUDGET_PX
    }

    @Test
    fun `malformed geometry fails closed`() {
        val policy = MediaProjectionFramePolicy(maxMirrorPixels = BUDGET_PX)
        assertThat(policy.acceptsMirrorGeometry(0, 1_000)).isFalse()
        assertThat(policy.acceptsMirrorGeometry(1_000, 0)).isFalse()
        assertThat(policy.acceptsMirrorGeometry(0, 0)).isFalse()
        assertThat(policy.acceptsMirrorGeometry(-1, 1_000)).isFalse()
        assertThat(policy.acceptsMirrorGeometry(1_000, -1)).isFalse()
        assertThat(policy.acceptsMirrorGeometry(Int.MIN_VALUE, Int.MAX_VALUE)).isFalse()
    }

    @Test
    fun `extreme dimensions cannot overflow the budget into an acceptance`() {
        val policy = MediaProjectionFramePolicy(maxMirrorPixels = BUDGET_PX)
        // Int.MAX_VALUE^2 needs 64-bit math but must simply exceed any sane budget.
        assertThat(
            policy.acceptsMirrorGeometry(Int.MAX_VALUE, Int.MAX_VALUE)
        ).isFalse()
        assertThat(
            policy.acceptsMirrorGeometry(Int.MAX_VALUE, Int.MIN_VALUE)
        ).isFalse()
        // A 16K display is far beyond the default 8MP cap.
        assertThat(
            MediaProjectionFramePolicy().acceptsMirrorGeometry(7_680, 4_320)
        ).isFalse()
    }

    @Test
    fun `size cap scales down with a tighter budget but never opens upward`() {
        val strict = MediaProjectionFramePolicy(maxMirrorPixels = 1)
        assertThat(strict.acceptsMirrorGeometry(1, 1)).isTrue()
        assertThat(strict.acceptsMirrorGeometry(2, 1)).isFalse()
        assertThat(strict.acceptsMirrorGeometry(1_000, 1_000)).isFalse()
    }
}
