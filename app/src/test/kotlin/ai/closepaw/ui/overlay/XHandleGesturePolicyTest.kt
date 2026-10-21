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

    @Test fun releaseAtLongPressBoundaryDoesNotStop() {
        val policy = XHandleGesturePolicy()
        policy.onDown(10f, 10f, 100L)
        assertThat(policy.onUp(10f, 10f, 550L))
            .isEqualTo(XHandleGesturePolicy.Result.KEEP_SESSION)
    }

    @Test fun interruptedAndResetGestureNeverLeaksOldDragState() {
        val policy = XHandleGesturePolicy()
        policy.onDown(10f, 10f, 100L)
        policy.onMove(80f, 80f)
        policy.onCancel()
        assertThat(policy.onUp(10f, 10f, 150L))
            .isEqualTo(XHandleGesturePolicy.Result.KEEP_SESSION)
        policy.onDown(10f, 10f, 200L)
        assertThat(policy.onUp(10f, 10f, 250L))
            .isEqualTo(XHandleGesturePolicy.Result.TERMINATE)
    }

    @Test fun repeatedLongDragsNeverStop() {
        repeat(10) { attempt ->
            val policy = XHandleGesturePolicy()
            val start = attempt * 1000L
            policy.onDown(25f, 25f, start)
            policy.onMove(90f, 100f)
            assertThat(policy.onUp(90f, 100f, start + 500L))
                .isEqualTo(XHandleGesturePolicy.Result.KEEP_SESSION)
        }
    }

    @Test fun secondPointerCancellationNeverTerminates() {
        val policy = XHandleGesturePolicy()
        policy.onDown(25f, 25f, 100L)
        policy.onCancel()
        assertThat(policy.onUp(25f, 25f, 150L))
            .isEqualTo(XHandleGesturePolicy.Result.KEEP_SESSION)
    }

    @Test fun reversedTimestampsCannotTriggerStop() {
        val policy = XHandleGesturePolicy()
        policy.onDown(10f, 10f, 200L)
        assertThat(policy.onUp(10f, 10f, 100L))
            .isEqualTo(XHandleGesturePolicy.Result.KEEP_SESSION)
    }
}
