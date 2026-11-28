package ai.closepaw.chatgpt.control

import ai.closepaw.security.ImageRevocationAuthority
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test fun twoStageGate_14999To15001DuringBinder_exact15000Allow_exact15001Refuse() {
        // 1. 14_999ms pre-Binder -> 15_001ms during Binder -> Stage 2 refuses, 0 mark, 0 dispatch
        val sessionA = ControlSession().apply { assertTrue(start()) }
        var nowA = 25_000L
        val budgetA = SharedQaRecoveryBudget(maxAttempts = 3, maxElapsedMs = 15_000L, clockMs = { nowA })
        assertTrue(budgetA.tryClaim())
        nowA = 25_000L + 14_999L
        val timingA = ControlTiming({ nowA * 1_000_000L })
        var binderCallsA = 0
        var markCountA = 0
        var dispatchCountA = 0
        var refusedA: String? = null
        val allowedA = GestureDispatchGate.guardAndDispatch(
            session = { sessionA },
            expectedEpoch = sessionA.epoch,
            active = { true },
            boundaryRefusal = { if (!budgetA.withinWindow()) "recovery_episode_elapsed_budget" else null },
            policyRefusal = {
                binderCallsA++
                nowA = 25_000L + 15_001L // clock crosses 15s during Binder IPC
                null
            },
            onRefused = { refusedA = it },
            onMarkDispatch = { markCountA++; timingA.mark(ControlTiming.Event.GESTURE_DISPATCH, 1, 4) },
            onDispatch = { dispatchCountA++ },
        )
        assertFalse(allowedA)
        assertEquals(1, binderCallsA)
        assertEquals("recovery_episode_elapsed_budget", refusedA)
        assertEquals(0, markCountA)
        assertEquals(0, dispatchCountA)
        assertEquals(0, timingA.snapshot()["events"]!!.jsonArray.size)
        sessionA.end()

        // 2. Exact 15_000ms after Binder -> ADMITS, exactly 1 mark, exactly 1 dispatch
        val sessionB = ControlSession().apply { assertTrue(start()) }
        var nowB = 25_000L
        val budgetB = SharedQaRecoveryBudget(maxAttempts = 3, maxElapsedMs = 15_000L, clockMs = { nowB })
        assertTrue(budgetB.tryClaim())
        nowB = 25_000L + 14_999L
        val timingB = ControlTiming({ nowB * 1_000_000L })
        var binderCallsB = 0
        var markCountB = 0
        var dispatchCountB = 0
        var refusedB: String? = null
        val allowedB = GestureDispatchGate.guardAndDispatch(
            session = { sessionB },
            expectedEpoch = sessionB.epoch,
            active = { true },
            boundaryRefusal = { if (!budgetB.withinWindow()) "recovery_episode_elapsed_budget" else null },
            policyRefusal = {
                binderCallsB++
                nowB = 25_000L + 15_000L // exact inclusive 15_000ms boundary
                null
            },
            onRefused = { refusedB = it },
            onMarkDispatch = { markCountB++; timingB.mark(ControlTiming.Event.GESTURE_DISPATCH, 2, 4) },
            onDispatch = { dispatchCountB++ },
        )
        assertTrue(allowedB)
        assertEquals(1, binderCallsB)
        assertNull(refusedB)
        assertEquals(1, markCountB)
        assertEquals(1, dispatchCountB)
        val eventsB = timingB.snapshot()["events"]!!.jsonArray
        assertEquals(1, eventsB.size)
        assertEquals("GESTURE_DISPATCH", eventsB[0].jsonObject["event"]?.jsonPrimitive?.content)
        sessionB.end()

        // 3. Exact 15_001ms BEFORE Binder (pre-fail) -> ZERO Binder calls, 0 mark, 0 dispatch
        val sessionC = ControlSession().apply { assertTrue(start()) }
        var nowC = 25_000L
        val budgetC = SharedQaRecoveryBudget(maxAttempts = 3, maxElapsedMs = 15_000L, clockMs = { nowC })
        assertTrue(budgetC.tryClaim())
        nowC = 25_000L + 15_001L
        var binderCallsC = 0
        var markCountC = 0
        var dispatchCountC = 0
        var refusedC: String? = null
        val allowedC = GestureDispatchGate.guardAndDispatch(
            session = { sessionC },
            expectedEpoch = sessionC.epoch,
            active = { true },
            boundaryRefusal = { if (!budgetC.withinWindow()) "recovery_episode_elapsed_budget" else null },
            policyRefusal = { binderCallsC++; null },
            onRefused = { refusedC = it },
            onMarkDispatch = { markCountC++ },
            onDispatch = { dispatchCountC++ },
        )
        assertFalse(allowedC)
        assertEquals("Pre-Binder fail-fast must make ZERO Binder calls", 0, binderCallsC)
        assertEquals("recovery_episode_elapsed_budget", refusedC)
        assertEquals(0, markCountC)
        assertEquals(0, dispatchCountC)
        sessionC.end()
    }

    @Test fun twoStageGate_binderRaces_sessionEnd_consentRevoke_cancel_simultaneousTimeout_andPolicyDenials() {
        for (binderReturnsPolicyDenial in listOf(null, "device_locked")) {
            // 1. Session end + simultaneous 15s timeout during Binder -> session revocation wins, 0 mark/dispatch
            val s1 = ControlSession().apply { assertTrue(start()) }
            val epoch1 = s1.epoch
            var now1 = 10_000L
            val b1 = SharedQaRecoveryBudget(clockMs = { now1 }).apply { assertTrue(tryClaim()) }
            var refused1: String? = null
            var marks1 = 0
            var dispatches1 = 0
            val ok1 = GestureDispatchGate.guardAndDispatch(
                session = { s1 },
                expectedEpoch = epoch1,
                active = { true },
                boundaryRefusal = { if (!b1.withinWindow()) "recovery_episode_elapsed_budget" else null },
                policyRefusal = {
                    s1.end()
                    now1 = 10_000L + 15_001L
                    binderReturnsPolicyDenial
                },
                onRefused = { refused1 = it },
                onMarkDispatch = { marks1++ },
                onDispatch = { dispatches1++ },
            )
            assertFalse(ok1)
            assertNotNull(refused1)
            assertNotEquals("recovery_episode_elapsed_budget", refused1)
            assertNotEquals("device_locked", refused1)
            assertEquals(0, marks1)
            assertEquals(0, dispatches1)

            // 2. Consent revoke + simultaneous 15s timeout during Binder -> screen_share_revoked wins, 0 mark/dispatch
            val consentEpoch = ImageRevocationAuthority.grantConsent()
            val s2 = ControlSession().apply { assertTrue(start()) }
            var now2 = 10_000L
            val b2 = SharedQaRecoveryBudget(clockMs = { now2 }).apply { assertTrue(tryClaim()) }
            var refused2: String? = null
            var marks2 = 0
            var dispatches2 = 0
            val ok2 = GestureDispatchGate.guardAndDispatch(
                session = { s2 },
                expectedEpoch = s2.epoch,
                active = { true },
                boundaryRefusal = {
                    when {
                        ImageRevocationAuthority.currentEpoch != consentEpoch -> "screen_share_revoked"
                        !b2.withinWindow() -> "recovery_episode_elapsed_budget"
                        else -> null
                    }
                },
                policyRefusal = {
                    ImageRevocationAuthority.revokeConsent()
                    now2 = 10_000L + 15_001L
                    binderReturnsPolicyDenial
                },
                onRefused = { refused2 = it },
                onMarkDispatch = { marks2++ },
                onDispatch = { dispatches2++ },
            )
            assertFalse(ok2)
            assertEquals("screen_share_revoked", refused2)
            assertEquals(0, marks2)
            assertEquals(0, dispatches2)
            s2.end()
            ImageRevocationAuthority.grantConsent()

            // 3. Cancellation + simultaneous 15s timeout during Binder -> cancelled (no resume, 0 mark/dispatch)
            val s3 = ControlSession().apply { assertTrue(start()) }
            var active3 = true
            var now3 = 10_000L
            val b3 = SharedQaRecoveryBudget(clockMs = { now3 }).apply { assertTrue(tryClaim()) }
            var refused3: String? = null
            var marks3 = 0
            var dispatches3 = 0
            val ok3 = GestureDispatchGate.guardAndDispatch(
                session = { s3 },
                expectedEpoch = s3.epoch,
                active = { active3 },
                boundaryRefusal = { if (!b3.withinWindow()) "recovery_episode_elapsed_budget" else null },
                policyRefusal = {
                    active3 = false
                    now3 = 10_000L + 15_001L
                    binderReturnsPolicyDenial
                },
                onRefused = { refused3 = it },
                onMarkDispatch = { marks3++ },
                onDispatch = { dispatches3++ },
            )
            assertFalse(ok3)
            assertNull("Cancelled continuation must NOT be resumed via onRefused", refused3)
            assertEquals(0, marks3)
            assertEquals(0, dispatches3)
            s3.end()
        }

        // 4. Preexisting Binder policy denial preserved even if 15s timeout also elapses (never downgraded to elapsed_budget!)
        for (denial in listOf("device_locked", "runtime_interaction_not_allowed", "mutation_blocked")) {
            val s4 = ControlSession().apply { assertTrue(start()) }
            var now4 = 10_000L
            val b4 = SharedQaRecoveryBudget(clockMs = { now4 }).apply { assertTrue(tryClaim()) }
            var refused4: String? = null
            var marks4 = 0
            var dispatches4 = 0
            val ok4 = GestureDispatchGate.guardAndDispatch(
                session = { s4 },
                expectedEpoch = s4.epoch,
                active = { true },
                boundaryRefusal = { if (!b4.withinWindow()) "recovery_episode_elapsed_budget" else null },
                policyRefusal = {
                    now4 = 10_000L + 15_001L
                    denial
                },
                onRefused = { refused4 = it },
                onMarkDispatch = { marks4++ },
                onDispatch = { dispatches4++ },
            )
            assertFalse(ok4)
            assertEquals("Binder security/policy denial must never be downgraded to elapsed_budget", denial, refused4)
            assertEquals(0, marks4)
            assertEquals(0, dispatches4)
            s4.end()
        }

        // 5. Non-autonomous path (boundaryRefusal == null): Stage 2 still blocks session pause/end/epoch change during Binder
        val s5 = ControlSession().apply { assertTrue(start()) }
        val epoch5 = s5.epoch
        var refused5: String? = null
        var marks5 = 0
        var dispatches5 = 0
        val ok5 = GestureDispatchGate.guardAndDispatch(
            session = { s5 },
            expectedEpoch = epoch5,
            active = { true },
            boundaryRefusal = null,
            policyRefusal = {
                s5.pause()
                null
            },
            onRefused = { refused5 = it },
            onMarkDispatch = { marks5++ },
            onDispatch = { dispatches5++ },
        )
        assertFalse(ok5)
        assertNotNull(refused5)
        assertEquals(0, marks5)
        assertEquals(0, dispatches5)
        s5.end()

        // 6. Non-autonomous normal path (boundaryRefusal == null, policy allows) -> 1 mark, 1 dispatch
        val s6 = ControlSession().apply { assertTrue(start()) }
        var marks6 = 0
        var dispatches6 = 0
        val ok6 = GestureDispatchGate.guardAndDispatch(
            session = { s6 },
            expectedEpoch = s6.epoch,
            active = { true },
            boundaryRefusal = null,
            policyRefusal = { null },
            onRefused = { fail("unexpected refusal: $it") },
            onMarkDispatch = { marks6++ },
            onDispatch = { dispatches6++ },
        )
        assertTrue(ok6)
        assertEquals(1, marks6)
        assertEquals(1, dispatches6)
        s6.end()

        // 7. Pre-Binder fail-fast on ended session / cancelled continuation -> ZERO Binder calls, 0 mark/dispatch
        val s7 = ControlSession().apply { assertTrue(start()); end() }
        var binderCalls7 = 0
        var refused7: String? = null
        var marks7 = 0
        var dispatches7 = 0
        val ok7 = GestureDispatchGate.guardAndDispatch(
            session = { s7 },
            expectedEpoch = s7.epoch,
            active = { true },
            boundaryRefusal = null,
            policyRefusal = { binderCalls7++; null },
            onRefused = { refused7 = it },
            onMarkDispatch = { marks7++ },
            onDispatch = { dispatches7++ },
        )
        assertFalse(ok7)
        assertEquals(0, binderCalls7)
        assertNotNull(refused7)
        assertEquals(0, marks7)
        assertEquals(0, dispatches7)

        // 8. Deadline transition during Binder (policy allows) -> six_key_sequence_deadline_exceeded, 0 mark/dispatch;
        //    and normal pre-existing policy denial without timeout -> exact policy denial preserved
        val s8 = ControlSession().apply { assertTrue(start()) }
        var deadlineNow = false
        var refused8a: String? = null
        var marks8a = 0
        var dispatches8a = 0
        val ok8a = GestureDispatchGate.guardAndDispatch(
            session = { s8 },
            expectedEpoch = s8.epoch,
            active = { true },
            boundaryRefusal = { if (deadlineNow) "six_key_sequence_deadline_exceeded" else null },
            policyRefusal = { deadlineNow = true; null },
            onRefused = { refused8a = it },
            onMarkDispatch = { marks8a++ },
            onDispatch = { dispatches8a++ },
        )
        assertFalse(ok8a)
        assertEquals("six_key_sequence_deadline_exceeded", refused8a)
        assertEquals(0, marks8a)
        assertEquals(0, dispatches8a)

        var refused8b: String? = null
        var marks8b = 0
        var dispatches8b = 0
        val ok8b = GestureDispatchGate.guardAndDispatch(
            session = { s8 },
            expectedEpoch = s8.epoch,
            active = { true },
            boundaryRefusal = null,
            policyRefusal = { "runtime_interaction_not_allowed" },
            onRefused = { refused8b = it },
            onMarkDispatch = { marks8b++ },
            onDispatch = { dispatches8b++ },
        )
        assertFalse(ok8b)
        assertEquals("runtime_interaction_not_allowed", refused8b)
        assertEquals(0, marks8b)
        assertEquals(0, dispatches8b)
        s8.end()
    }
}
