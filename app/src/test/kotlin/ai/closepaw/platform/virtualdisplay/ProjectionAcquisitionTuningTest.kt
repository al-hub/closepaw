package ai.closepaw.platform.virtualdisplay

import ai.closepaw.chatgpt.control.ControlSession
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ProjectionAcquisitionTuningTest {
    private class Disk : ProjectionTuningPersistence {
        var saved: ProjectionTuningValues? = null
        var fail = false
        var writes = 0
        override fun load() = saved
        override fun commit(values: ProjectionTuningValues): ProjectionTuningOutcome {
            writes++
            if (fail) return ProjectionTuningOutcome.REFUSED
            saved = values; return ProjectionTuningOutcome.APPLIED
        }
    }
    private fun active() = ControlSession().apply { check(start()) }
    @Test fun `bounded CAS persists across restart and rollback is new revision`() {
        val disk = Disk(); val state = ProjectionTuningState(disk); val session = active()
        assertEquals(ProjectionTuningValues(), state.snapshot())
        assertEquals(ProjectionTuningOutcome.APPLIED, state.update(session, session.id, true, 0, 3000, 100))
        val restarted = ProjectionTuningState(disk)
        assertEquals(ProjectionTuningValues(1, 3000, 100), restarted.snapshot())
        assertEquals(ProjectionTuningOutcome.REFUSED, restarted.update(session, session.id, true, 0, 1500, 25))
        assertEquals(ProjectionTuningOutcome.APPLIED, restarted.update(session, session.id, true, 1, 1500, 25))
        assertEquals(ProjectionTuningValues(2, 1500, 25), ProjectionTuningState(disk).snapshot())
        for ((timeout, poll) in listOf(1499L to 25L, 3001L to 25L, 1500L to 24L, 1500L to 101L)) {
            assertEquals(ProjectionTuningOutcome.REFUSED, restarted.update(session, session.id, true, 2, timeout, poll))
        }
        assertEquals(2, disk.writes)
    }
    @Test fun `caller authorization flag cannot manufacture active explicit session`() {
        val disk = Disk(); val state = ProjectionTuningState(disk)
        for (session in listOf(null, ControlSession(), ControlSession().apply { activateLegacy() },
                active().apply { pause() }, active().apply { end() }, active().apply { waitForUser() })) {
            assertEquals(ProjectionTuningOutcome.REFUSED, state.update(session, session?.id, true, 0, 2000, 50))
        }
        val good = active()
        assertEquals(ProjectionTuningOutcome.REFUSED, state.update(good, "forged", true, 0, 2000, 50))
        assertEquals(ProjectionTuningOutcome.REFUSED, state.update(good, good.id, false, 0, 2000, 50))
        assertEquals(0, disk.writes)
    }
    @Test fun `failed durable write preserves snapshot and restart and allows recovery`() {
        val disk = Disk(); val state = ProjectionTuningState(disk); val session = active()
        assertEquals(ProjectionTuningOutcome.APPLIED, state.update(session, session.id, true, 0, 2000, 50))
        disk.fail = true
        assertEquals(ProjectionTuningOutcome.REFUSED, state.update(session, session.id, true, 1, 3000, 100))
        assertEquals(ProjectionTuningValues(1, 2000, 50), state.snapshot())
        assertEquals(state.snapshot(), ProjectionTuningState(disk).snapshot())
        disk.fail = false
        assertEquals(ProjectionTuningOutcome.APPLIED, state.update(session, session.id, true, 1, 1500, 25))
    }
    @Test fun `invalid persisted document resets safely and revision overflow refuses`() {
        val disk = Disk()
        disk.saved = ProjectionTuningValues(-1, Long.MAX_VALUE, -1)
        assertEquals(ProjectionTuningValues(), ProjectionTuningState(disk).snapshot())
        disk.saved = ProjectionTuningValues(Int.MAX_VALUE, 1500, 25)
        val session = active()
        assertEquals(ProjectionTuningOutcome.REFUSED, ProjectionTuningState(disk).update(session, session.id, true, Int.MAX_VALUE, 2000, 50))
        for (raw in listOf("", "0,1500,25,1000", "-1,1500,25", "0,3001,25", "0,1500,24", "999999999999,1500,25")) {
            assertNull(ProjectionTuningValues.decode(raw))
        }
    }
    @Test fun `session stop and durable configuration commit serialize and ended cannot write`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val session = active()
        val disk = object : ProjectionTuningPersistence {
            override fun load(): ProjectionTuningValues? = null
            override fun commit(values: ProjectionTuningValues): ProjectionTuningOutcome {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return ProjectionTuningOutcome.APPLIED
            }
        }
        val state = ProjectionTuningState(disk)
        var changed = ProjectionTuningOutcome.REFUSED
        val writer = thread { changed = state.update(session, session.id, true, 0, 2000, 50) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val ended = CountDownLatch(1)
        val stopper = thread { session.end(); ended.countDown() }
        assertFalse(ended.await(50, TimeUnit.MILLISECONDS))
        release.countDown(); writer.join(5000); stopper.join(5000)
        assertEquals(ProjectionTuningOutcome.APPLIED, changed); assertEquals(ControlSession.State.ENDED, session.state)
        assertEquals(ProjectionTuningOutcome.REFUSED, state.update(session, session.id, true, 1, 3000, 100))
    }
}
