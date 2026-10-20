package ai.closepaw.ui.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XHandleGesturePolicyTest {
    @Test fun shortTapTerminates() {
        val policy = XHandleGesturePolicy()
        policy.onDown(20f, 20f, 100L)
        assertThat(policy.onUp(20f, 20f, 200L))
            .isEqualTo(XHandleGesturePolicy.Result.TERMINATE)
    }

    @Test fun longHoldNeverTerminatesOnRelease() {
        val policy = XHandleGesturePolicy()
        policy.onDown(20f, 20f, 100L)
        assertThat(policy.onUp(20f, 20f, 900L))
            .isEqualTo(XHandleGesturePolicy.Result.KEEP_SESSION)
    }

    @Test fun dragNeverTerminatesEvenWithQuickReleaseAtStartPoint() {
        val policy = XHandleGesturePolicy()
        policy.onDown(20f, 20f, 100L)
        policy.onMove(80f, 80f)
        assertThat(policy.onUp(20f, 20f, 200L))
            .isEqualTo(XHandleGesturePolicy.Result.KEEP_SESSION)
    }

    @Test fun cancelAndOrphanReleaseDoNotTerminate() {
        val policy = XHandleGesturePolicy()
        policy.onDown(20f, 20f, 100L)
        policy.onCancel()
        assertThat(policy.onUp(20f, 20f, 110L))
            .isEqualTo(XHandleGesturePolicy.Result.KEEP_SESSION)
    }

    @Test fun moveBelowSlopIsStillTap() {
        val policy = XHandleGesturePolicy()
        policy.onDown(20f, 20f, 100L)
        policy.onMove(23f, 23f)
        assertThat(policy.onUp(23f, 23f, 200L))
            .isEqualTo(XHandleGesturePolicy.Result.TERMINATE)
    }
}
