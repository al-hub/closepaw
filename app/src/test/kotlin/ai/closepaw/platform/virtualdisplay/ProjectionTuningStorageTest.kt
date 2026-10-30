package ai.closepaw.platform.virtualdisplay

import ai.closepaw.chatgpt.control.ControlSession
import android.util.AtomicFile
import io.mockk.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class ProjectionTuningStorageTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `readback failure after rename is UNKNOWN and cannot replay until canonical reload`() {
        val path = temp.newFile()
        path.writeText("0,1500,25")
        val file = mockk<AtomicFile>()
        every { file.openRead() } answers { FileInputStream(path) }
        every { file.startWrite() } answers { FileOutputStream(path) }
        every { file.finishWrite(any()) } answers { firstArg<FileOutputStream>().close() }
        every { file.failWrite(any()) } just Runs
        val persistence = AtomicProjectionTuningPersistence(file)
        val state = ProjectionTuningState(persistence)
        every { file.openRead() } throws IOException("readback")
        val session = ControlSession().apply { start() }
        assertEquals(ProjectionTuningOutcome.STORAGE_UNKNOWN, state.update(session, session.id, true, 0, 2000, 50))
        assertEquals("unknown_restart_required", state.storageState())
        assertEquals(ProjectionTuningOutcome.STORAGE_UNKNOWN, state.update(session, session.id, true, 0, 3000, 100))
        verify(exactly = 1) { file.startWrite() }
        // A process restart canonically reads the actual durable revision. It
        // must never call the uncertain outcome a refused/rolled-back write.
        every { file.openRead() } answers { FileInputStream(path) }
        val restarted = ProjectionTuningState(persistence)
        assertEquals(ProjectionTuningValues(1, 2000, 50), restarted.snapshot())
        assertEquals(ProjectionTuningOutcome.REFUSED, restarted.update(session, session.id, true, 0, 1500, 25))
        assertEquals(ProjectionTuningOutcome.APPLIED, restarted.update(session, session.id, true, 1, 1500, 25))
        assertEquals(ProjectionTuningValues(2, 1500, 25), persistence.load())
    }
    @Test fun `failure before start preserves previous document and oversize input is refused`() {
        val path = temp.newFile(); path.writeText("1,2000,50")
        val file = mockk<AtomicFile>()
        every { file.openRead() } answers { FileInputStream(path) }
        every { file.startWrite() } throws IOException()
        every { file.failWrite(any()) } just Runs
        val persistence = AtomicProjectionTuningPersistence(file)
        assertEquals(ProjectionTuningOutcome.REFUSED, persistence.commit(ProjectionTuningValues(2, 3000, 100)))
        assertEquals(ProjectionTuningValues(1, 2000, 50), persistence.load())
        path.writeText("1,2000,50" + " ".repeat(1000))
        assertNull(persistence.load())
    }
}
