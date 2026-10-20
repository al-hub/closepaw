package ai.closepaw.app

import org.junit.Assert.*
import org.junit.Test

class PremiumMotionPolicyTest {
    @Test fun reducedMotionAndUserControlNeverScheduleAmbientFrames() {
        assertTrue(PremiumMotionPolicy.animate(true, true, true, false, false))
        assertFalse(PremiumMotionPolicy.animate(false, true, true, false, false))
        assertFalse(PremiumMotionPolicy.animate(true, false, true, false, false))
        assertFalse(PremiumMotionPolicy.animate(true, true, false, false, false))
        assertFalse(PremiumMotionPolicy.animate(true, true, true, true, false))
        assertFalse(PremiumMotionPolicy.animate(true, true, true, false, true))
    }
    @Test fun transientEffectsHaveBoundedLifetimeAndMonotonicFade() {
        assertEquals(0f, PremiumMotionPolicy.progress(100, 200, 720))
        assertEquals(1f, PremiumMotionPolicy.progress(1000, 200, 720))
        assertEquals(1f, PremiumMotionPolicy.progress(100, 200, 0))
        assertTrue(PremiumMotionPolicy.fade(.2f) > PremiumMotionPolicy.fade(.8f))
        assertEquals(0f, PremiumMotionPolicy.fade(1.5f))
    }
}
