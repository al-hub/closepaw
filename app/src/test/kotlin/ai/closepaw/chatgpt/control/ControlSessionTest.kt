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


    @Test fun sharingPromptHasPriorityAndClearsWithoutResuming() {
        val session = ControlSession()
        assertTrue(session.start())
        val id = session.id
        val startingEpoch = session.epoch
        assertTrue(session.waitForUser(ControlSession.UserAction.ALLOW_SCREEN_SHARE, id))
        val waitingEpoch = session.epoch
        assertNotEquals(startingEpoch, waitingEpoch)

        session.feedback("화면 확인 중", waitingEpoch)
        session.present(ControlSession.VisualPhase.COMPLETED, waitingEpoch)
        session.clearUserActionHint(ControlSession.UserAction.STOP_SCREEN_SHARE)
        assertEquals("공유를 허용하세요", session.label)
        session.clearUserActionHint(ControlSession.UserAction.ALLOW_SCREEN_SHARE)
        assertEquals("사용자 응답 대기", session.label)
        assertEquals(waitingEpoch, session.epoch)
        assertEquals(id, session.id)
        assertEquals("control_waiting_for_user", session.refusal(id, true))

        session.end()
        assertTrue(session.start())
        assertFalse(session.waitForUser(ControlSession.UserAction.STOP_SCREEN_SHARE, id))
        assertTrue(session.waitForUser(ControlSession.UserAction.STOP_SCREEN_SHARE, session.id))
        assertEquals("공유를 중지하세요", session.label)
        session.pause()
        assertEquals("사용자 제어 중 · 일시정지", session.label)
        session.clearUserActionHint()
        assertEquals("사용자 제어 중 · 일시정지", session.label)
    }

    @Test fun outstandingActionFollowsConfirmedSharingWithoutChangingControl() {
        val session = ControlSession().apply { assertTrue(start()) }
        val id = session.id
        val epoch = session.epoch
        val hintEpoch = session.userActionEpoch
        val allow = ControlSession.UserAction.ALLOW_SCREEN_SHARE
        val stop = ControlSession.UserAction.STOP_SCREEN_SHARE
        var interrupts = 0
        session.interrupt = { interrupts++ }
        fun update(revision: Int, action: ControlSession.UserAction, sharing: Boolean) =
            session.updateUserAction(id, hintEpoch, revision, action, sharing, 10L)

        assertEquals(ControlSession.HintUpdate.NOT_NEEDED, update(1, stop, false))
        assertNull(session.currentUserAction(false, 10))
        assertEquals(ControlSession.HintUpdate.SHOWN, update(2, allow, false))
        assertEquals(allow, session.currentUserAction(false, 11)?.action)
        // A button being tapped or text being seen does not change the actual sharing state.
        assertEquals(allow, session.currentUserAction(false, 12)?.action)
        assertNull(session.currentUserAction(true, 13))
        assertNull(session.currentUserAction(false, 14))
        assertEquals(ControlSession.HintUpdate.NOT_NEEDED, update(3, allow, true))
        assertEquals(ControlSession.HintUpdate.SHOWN, update(4, stop, true))
        assertEquals(stop, session.currentUserAction(true, 15)?.action)
        assertNull(session.currentUserAction(false, 16))
        assertEquals(ControlSession.State.ACTIVE, session.state)
        assertEquals(epoch, session.epoch)
        assertEquals(id, session.id)
        assertEquals(0, interrupts)
        assertNull(session.refusal(id, mutation = true))
    }

    @Test fun revisionsDeduplicateReplaceClearAndExpireWithoutRevivingOldActions() {
        val session = ControlSession().apply { start() }
        val allow = ControlSession.UserAction.ALLOW_SCREEN_SHARE
        val stop = ControlSession.UserAction.STOP_SCREEN_SHARE
        fun update(revision: Int, action: ControlSession.UserAction?, sharing: Boolean = false, now: Long = 0) =
            session.updateUserAction(session.id, session.userActionEpoch, revision, action, sharing, now)

        assertEquals(ControlSession.HintUpdate.SHOWN, update(1, allow))
        val original = session.currentUserAction(false, 0)
        assertEquals(ControlSession.HintUpdate.UNCHANGED, update(1, allow, now = 1))
        assertEquals(ControlSession.HintUpdate.UNCHANGED, update(2, allow, now = 2))
        assertSame(original, session.currentUserAction(false, ControlSession.USER_ACTION_TTL_MS - 1))
        assertNull(session.currentUserAction(false, ControlSession.USER_ACTION_TTL_MS))
        assertEquals(ControlSession.HintUpdate.UNCHANGED, update(2, allow, now = ControlSession.USER_ACTION_TTL_MS))
        assertNull(session.currentUserAction(false, ControlSession.USER_ACTION_TTL_MS))

        assertEquals(ControlSession.HintUpdate.SHOWN, update(3, allow, now = 40_000))
        assertEquals(ControlSession.HintUpdate.SHOWN, update(5, stop, sharing = true, now = 40_001))
        assertEquals(ControlSession.HintUpdate.STALE, update(4, null, sharing = true, now = 40_002))
        assertEquals(ControlSession.HintUpdate.STALE, update(5, allow, sharing = true, now = 40_003))
        assertEquals(stop, session.currentUserAction(true, 40_004)?.action)
        assertEquals(ControlSession.HintUpdate.CLEARED, update(6, null, sharing = true, now = 40_005))
        assertNull(session.currentUserAction(true, 40_006))
        assertEquals(ControlSession.State.ACTIVE, session.state)
    }

    @Test fun lockAndControlTransitionsInvalidatePromptAdmissions() {
        val session = ControlSession().apply { start() }
        val id = session.id
        val admission = session.userActionEpoch
        val controlEpoch = session.epoch
        val allow = ControlSession.UserAction.ALLOW_SCREEN_SHARE
        assertEquals(ControlSession.HintUpdate.SHOWN, session.updateUserAction(id, admission, 1, allow, false, 0))
        session.dropUserActionForScreenOff()
        assertNull(session.currentUserAction(false, 1))
        assertEquals(controlEpoch, session.epoch)
        assertEquals(ControlSession.HintUpdate.REFUSED, session.updateUserAction(id, admission, 2, allow, false, 2))
        assertEquals(ControlSession.HintUpdate.SHOWN,
            session.updateUserAction(id, session.userActionEpoch, 2, allow, false, 3))
        session.pause()
        assertNull(session.currentUserAction(false, 4))
        assertEquals(ControlSession.HintUpdate.REFUSED,
            session.updateUserAction(id, session.userActionEpoch, 3, allow, false, 5))
        session.end()
        assertTrue(session.start())
        assertEquals(0, session.userActionRevision)
        assertEquals(ControlSession.HintUpdate.REFUSED,
            session.updateUserAction(id, session.userActionEpoch, 3, allow, false, 6))
        assertNull(session.currentUserAction(false, 7))
    }
}
