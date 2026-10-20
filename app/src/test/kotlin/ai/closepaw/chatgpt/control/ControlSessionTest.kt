package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class ControlSessionTest {
    @Test fun stopWinsOverPauseAndLateResume() {
        val s = ControlSession(); s.start(); s.pause(); assertTrue(s.beginResume())
        val epoch = s.epoch; s.end(); s.finishResume(epoch, true); s.pause()
        assertEquals(ControlSession.State.ENDED, s.state)
        assertFalse(s.beginResume()); assertEquals("control_session_ended", s.refusal(s.id, true))
    }
    @Test fun pauseInterruptsAndResumeNeedsFreshScreen() {
        val s = ControlSession(); var cancelled = 0; s.interrupt = { cancelled++ }
        s.activateLegacy(); val before = s.epoch; s.pause()
        assertEquals(1, cancelled); assertNotEquals(before, s.epoch)
        assertEquals("control_session_paused", s.refusal(mutation = true))
        s.beginResume(); assertEquals("control_reobservation_required", s.refusal(mutation = true))
        s.finishResume(s.epoch, false); assertEquals(ControlSession.State.PAUSED, s.state)
        s.beginResume(); s.finishResume(s.epoch, true); assertNull(s.refusal(mutation = true))
    }
    @Test fun terminatedSessionRequiresNewIdentityAndCannotBeSilentlyActivated() {
        val s = ControlSession(); s.start(); val old = s.id; s.end(); s.activateLegacy()
        assertEquals(ControlSession.State.ENDED, s.state)
        assertTrue(s.start()); assertNotEquals(old, s.id)
        assertEquals("control_session_id_required", s.refusal(old, true)); assertNull(s.refusal(s.id, true))
        assertFalse(s.start())
    }
    @Test fun waitBlocksAiAndRequiresPhysicalResponse() {
        val s = ControlSession(); s.start(); assertTrue(s.waitForUser())
        assertEquals("control_waiting_for_user", s.refusal(s.id, true))
        s.feedback("AI command"); assertEquals(ControlSession.State.WAITING, s.state)
        assertTrue(s.userResponded()); assertTrue(s.beginResume())
        s.finishResume(s.epoch, true); assertEquals(ControlSession.State.ACTIVE, s.state)
    }
    @Test fun persistedSessionNeverResumesOnRestart() {
        for (state in listOf("active", "waiting", "reobserving", "ended"))
            assertEquals(ControlSession.State.ENDED, ControlSession(state).state)
        assertEquals(ControlSession.State.PAUSED, ControlSession("paused").state)
    }
}
