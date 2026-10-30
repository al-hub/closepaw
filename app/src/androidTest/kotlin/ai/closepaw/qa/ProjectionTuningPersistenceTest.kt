package ai.closepaw.qa

import ai.closepaw.platform.virtualdisplay.*
import ai.closepaw.chatgpt.control.ControlSession
import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android AtomicFile semantics; no device permissions or screen control. */
@RunWith(AndroidJUnit4::class)
class ProjectionTuningPersistenceTest {
    @Test fun durablePollingCasRestartRollbackAndInterruptedWriteRecovery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "polling-qa-${System.nanoTime()}").apply { mkdirs() }
        try {
            val file = AtomicFile(File(dir, "settings"))
            val store = AtomicProjectionTuningPersistence(file)
            val session = ControlSession().apply { check(start()) }
            val state = ProjectionTuningState(store)
            assertEquals(ProjectionTuningOutcome.APPLIED, state.update(session, session.id, true, 0, 3000, 100))
            assertEquals(ProjectionTuningValues(1, 3000, 100), ProjectionTuningState(store).snapshot())
            // A torn pending transaction must retain the prior complete document.
            val partial = file.startWrite()
            partial.write("2,2000,".toByteArray())
            file.failWrite(partial)
            assertEquals(ProjectionTuningValues(1, 3000, 100), store.load())
            val restarted = ProjectionTuningState(store)
            assertEquals(ProjectionTuningOutcome.REFUSED, restarted.update(session, session.id, true, 0, 1500, 25))
            assertEquals(ProjectionTuningOutcome.APPLIED, restarted.update(session, session.id, true, 1, 1500, 25))
            assertEquals(ProjectionTuningValues(2, 1500, 25), store.load())
            val blocker = File(dir, "blocker").apply { writeText("x") }
            val refused = AtomicProjectionTuningPersistence(AtomicFile(File(blocker, "settings")))
            assertEquals(ProjectionTuningOutcome.REFUSED, refused.commit(ProjectionTuningValues(1, 2000, 50)))
        } finally { dir.deleteRecursively() }
    }
}
