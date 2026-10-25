package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class GestureDispatchGateTest {
    @Test fun endRestartPauseOrCancellationDuringFinalGuardNeverAdmitsOldGesture() {
        for (race in listOf("end", "restart", "pause", "cancel")) {
            val session = ControlSession().apply { start() }
            val admitted = session.epoch
            var active = true
            // Injection models a remote stop/cancel while final Binder reads execute.
            when (race) {
                "end" -> session.end()
                "restart" -> { session.end(); session.start() }
                "pause" -> session.pause()
                "cancel" -> active = false
            }
            var dispatched = 0
            if (GestureDispatchGate.refusal(session, admitted, active) == null) dispatched++
            assertEquals(0, dispatched)
        }
    }
    @Test fun unchangedSessionAdmitsOnlyAnActiveContinuation() {
        val session = ControlSession().apply { start() }
        assertNull(GestureDispatchGate.refusal(session, session.epoch, true))
        assertNotNull(GestureDispatchGate.refusal(session, session.epoch, false))
    }
}
