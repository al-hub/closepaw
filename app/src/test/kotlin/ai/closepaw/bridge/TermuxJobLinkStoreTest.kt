package ai.closepaw.bridge

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertThrows
import org.junit.Test

class TermuxJobLinkStoreTest {
    @Test fun `restart restores job link without resubmission`() = withLedger { file ->
        TermuxJobLinkStore(file).put(TASK, JOB)
        assertThat(TermuxJobLinkStore(file).get(TASK)).isEqualTo(JOB)
    }

    @Test fun `unknown task has no job`() = withLedger { file ->
        val store = TermuxJobLinkStore(file)
        assertThat(store.get(TASK)).isNull()
        assertThat(store.canAccept(TASK)).isTrue()
    }

    @Test fun `invalid identifiers and conflicting relinks are rejected`() = withLedger { file ->
        val store = TermuxJobLinkStore(file)
        assertThrows(IllegalArgumentException::class.java) { store.put("../task", JOB) }
        store.put(TASK, JOB)
        assertThrows(IllegalArgumentException::class.java) { store.put(TASK, UUID.randomUUID().toString()) }
        assertThat(store.get(TASK)).isEqualTo(JOB)
    }

    @Test fun `full ledger rejects new link without evicting existing job`() = withLedger { file ->
        file.writeText((0 until 1000).joinToString("\n", postfix = "\n") {
            "${UUID(0, it.toLong())}\t${UUID(1, it.toLong())}"
        })
        val store = TermuxJobLinkStore(file)
        assertThat(store.canAccept(TASK)).isFalse()
        assertThat(store.canAccept(UUID(0, 0).toString())).isTrue()
        assertThrows(IllegalArgumentException::class.java) { store.put(TASK, JOB) }
        assertThat(store.get(UUID(0, 0).toString())).isEqualTo(UUID(1, 0).toString())
    }

    @Test fun `corrupt ledger fails closed`() = withLedger { file ->
        file.writeText("bad\tlink\n")
        assertThrows(IllegalArgumentException::class.java) { TermuxJobLinkStore(file) }
    }

    private fun withLedger(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("termux-job-links").toFile()
        try { block(File(directory, "links.tsv")) } finally { directory.deleteRecursively() }
    }
    private companion object {
        const val TASK = "df0a412b-e9e6-407e-aa51-6ef60ff7034b"
        const val JOB = "a11e1f4a31d54409b51eab8318dbf356"
    }
}
