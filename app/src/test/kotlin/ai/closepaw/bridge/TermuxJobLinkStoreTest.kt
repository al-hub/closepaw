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

    @Test fun `full ledger reclaims only links backed by durable results`() = withLedger { file ->
        file.writeText((0 until 1000).joinToString("\n", postfix = "\n") {
            "${UUID(0, it.toLong())}\t${UUID(1, it.toLong())}"
        })
        val retired = UUID(0, 0).toString()
        val store = TermuxJobLinkStore(file) { mapOf(retired to UUID(1, 0).toString()) }
        assertThat(store.canAccept(TASK)).isTrue()
        store.put(TASK, JOB)
        assertThat(store.get(retired)).isNull()
        assertThat(store.get(UUID(0, 1).toString())).isEqualTo(UUID(1, 1).toString())
        assertThat(TermuxJobLinkStore(file).get(TASK)).isEqualTo(JOB)
    }

    @Test fun `reservation survives restart and cannot be submitted twice`() = withLedger { file ->
        val first = TermuxJobLinkStore(file)
        assertThat(first.reserve(TASK)).isEqualTo(TermuxJobLinkStore.Admission.RESERVED)
        val restarted = TermuxJobLinkStore(file)
        assertThat(restarted.reserve(TASK)).isEqualTo(TermuxJobLinkStore.Admission.UNCERTAIN)
        restarted.put(TASK, JOB)
        assertThat(first.reserve(TASK)).isEqualTo(TermuxJobLinkStore.Admission.LINKED)
        assertThat(first.get(TASK)).isEqualTo(JOB)
    }

    @Test fun `concurrent instances reserve the final recovered slot exactly once`() = withLedger { file ->
        file.writeText((0 until 1000).joinToString("\n", postfix = "\n") {
            "${UUID(0, it.toLong())}\t${UUID(1, it.toLong())}"
        })
        val stores = List(2) { TermuxJobLinkStore(file) { mapOf(UUID(0, 0).toString() to UUID(1, 0).toString()) } }
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val requests = (0 until 20).map { index -> pool.submit<TermuxJobLinkStore.Admission> {
                start.await()
                stores[index % 2].reserve(UUID(2, index.toLong()).toString())
            } }
            start.countDown()
            val admissions = requests.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(admissions.count { it == TermuxJobLinkStore.Admission.RESERVED }).isEqualTo(1)
            assertThat(admissions.count { it == TermuxJobLinkStore.Admission.FULL }).isEqualTo(19)
            assertThat(file.readLines()).hasSize(1000)
            assertThat(stores[0].get(UUID(0, 1).toString())).isEqualTo(UUID(1, 1).toString())
        } finally { pool.shutdownNow() }
    }

    @Test fun `failed reclaim proof preserves the full ledger`() = withLedger { file ->
        file.writeText((0 until 1000).joinToString("\n", postfix = "\n") {
            "${UUID(0, it.toLong())}\t${UUID(1, it.toLong())}"
        })
        val original = file.readText()
        val store = TermuxJobLinkStore(file) { error("result storage unavailable") }
        assertThrows(IllegalStateException::class.java) { store.reserve(TASK) }
        assertThat(file.readText()).isEqualTo(original)
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
