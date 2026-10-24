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
    @Test fun manualTakeoverInvalidatesInFlightWorkAndCannotBeOverriddenByFeedback() {
        val s = ControlSession(); s.start(); val before = s.epoch
        var interrupts = 0; s.interrupt = { interrupts++ }
        s.manualTakeover()
        assertEquals(ControlSession.State.PAUSED, s.state)
        assertNotEquals(before, s.epoch); assertEquals(1, interrupts)
        assertEquals("직접 조작 중 · AI 일시정지", s.label)
        s.feedback("AI command")
        assertEquals("직접 조작 중 · AI 일시정지", s.label)
        assertEquals("control_session_paused", s.refusal(s.id, true))
        s.end(); s.manualTakeover()
        assertEquals(ControlSession.State.ENDED, s.state)
    }
    @Test fun waitingCanAlwaysPauseWithoutAcknowledgingTheUserResponse() {
        val s = ControlSession(); s.start(); s.waitForUser(); s.pause()
        assertEquals(ControlSession.State.PAUSED, s.state)
        assertFalse(s.userResponded())
        assertEquals("control_session_paused", s.refusal(s.id, true))
    }
    @Test fun persistedSessionNeverResumesOnRestart() {
        for (state in listOf("active", "waiting", "reobserving", "ended"))
            assertEquals(ControlSession.State.ENDED, ControlSession(state).state)
        assertEquals(ControlSession.State.PAUSED, ControlSession("paused").state)
    }
    @org.junit.Test fun lateVisualEventsCannotOverrideUserControlOrNewSession() {
        val session = ControlSession(); session.start()
        val epoch = session.epoch
        session.present(ControlSession.VisualPhase.EXECUTING, epoch)
        org.junit.Assert.assertEquals(ControlSession.VisualPhase.EXECUTING, session.visualPhase)
        session.pause()
        session.present(ControlSession.VisualPhase.COMPLETED, epoch)
        org.junit.Assert.assertEquals(ControlSession.VisualPhase.EXECUTING, session.visualPhase)
        session.end(); session.start()
        session.present(ControlSession.VisualPhase.FAILED, epoch)
        session.feedback("old result", epoch)
        org.junit.Assert.assertEquals("AI 제어 중", session.label)
        org.junit.Assert.assertEquals(ControlSession.VisualPhase.PREPARING, session.visualPhase)
    }

    @Test fun visualPhaseUpdatesTruthfulLabelAndDeduplicatesUnchangedEvents() {
        val session = ControlSession(); session.start()
        var changes = 0; session.changed = { changes++ }
        session.present(ControlSession.VisualPhase.OBSERVING, session.epoch)
        assertEquals("화면 확인 중", session.label)
        session.present(ControlSession.VisualPhase.OBSERVING, session.epoch)
        assertEquals(1, changes)
        session.present(ControlSession.VisualPhase.WAITING, session.epoch)
        assertEquals("응답 대기 중", session.label)
        session.present(ControlSession.VisualPhase.COMPLETED, session.epoch)
        assertEquals("실행 단계 확인 완료", session.label)
        session.present(ControlSession.VisualPhase.FAILED, session.epoch)
        assertEquals("결과 미확인 · 재관찰 필요", session.label)
        val epoch = session.epoch
        session.end()
        session.present(ControlSession.VisualPhase.COMPLETED, epoch)
        assertEquals("AI 제어 종료", session.label)
    }
    @Test fun geminiProgressHasOnlyObservedStageAndNoInventedStepCount() {
        val session = ControlSession(); session.start(); val epoch = session.epoch
        session.geminiProgress("waiting", epoch)
        assertEquals("응답 대기 중", session.label)
        assertTrue(session.progressDetail!!.contains("제출/완료 별도 확인"))
        assertFalse(session.progressDetail!!.contains("1/"))
        session.geminiProgress("completed", epoch)
        assertEquals("Gemini 응답 확인 완료", session.label)
        session.geminiProgress("interrupted", epoch)
        assertTrue(session.progressDetail!!.contains("Gemini 생성 취소 미확인"))
        session.end()
        session.geminiProgress("completed", epoch)
        assertEquals("AI 제어 종료", session.label)
        assertNull(session.progressDetail)
    }

    @Test fun geminiDetailCoversEveryStageWithoutClaimingUnverifiedCompletion() {
        val session = ControlSession(); session.start(); val epoch = session.epoch
        val stages = listOf("launching", "observing", "inputting", "submitting", "waiting",
            "collecting", "scrolling", "completed", "interrupted", "failed")
        for (stage in stages) {
            session.geminiProgress(stage, epoch)
            val detail = requireNotNull(session.progressDetail)
            for (field in listOf("목표:", "대상:", "완료:", "현재:", "다음:", "상태:"))
                assertTrue("$stage missing $field", detail.contains(field))
            assertFalse("unknown total must not be invented", detail.contains("1/"))
            if (stage != "completed") assertTrue(detail.contains("전체 목표 완료 미확정"))
            if (stage in listOf("interrupted", "failed")) {
                assertTrue(detail.contains("자동 재전송 금지"))
                assertTrue(detail.contains("자동 재실행 없음"))
            }
        }
        session.geminiProgress("submitting", epoch)
        assertTrue(session.progressDetail!!.contains("제출 성공 미확정"))
        session.geminiProgress("interrupted", epoch)
        assertTrue(session.progressDetail!!.contains("Gemini 생성 취소 미확인"))
        val detail = session.progressDetail
        var changes = 0; session.changed = { changes++ }
        session.geminiProgress("interrupted", epoch)
        session.geminiProgress("unsupported", epoch)
        assertEquals(0, changes); assertEquals(detail, session.progressDetail)
        session.end(); session.start()
        session.geminiProgress("completed", epoch)
        assertNull(session.progressDetail)
    }

}
