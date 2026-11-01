package ai.closepaw.bridge

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Identity/admission ledger only. Results and request dedup remain in the operation task store. */
class TermuxJobLinkStore(
    file: File,
    private val retainedResults: () -> Map<String, String> = { emptyMap() },
) {
    private val file = file.canonicalFile
    private val pathLock = pathLocks.computeIfAbsent(this.file.path) { Any() }

    init { transaction { } }

    enum class Admission { RESERVED, LINKED, UNCERTAIN, FULL }

    fun get(taskId: String): String? = transaction { links ->
        validateId(taskId)
        links[taskId]
    }

    /** Atomically reserve durable capacity before HTTP submission. Ambiguous reservations never replay. */
    fun reserve(taskId: String): Admission = transaction { links ->
        validateId(taskId)
        when {
            links.containsKey(taskId) -> if (links[taskId] == null) Admission.UNCERTAIN else Admission.LINKED
            else -> {
                reclaim(links)
                if (links.size >= MAX_LINKS) Admission.FULL
                else {
                    links[taskId] = null
                    persist(links)
                    Admission.RESERVED
                }
            }
        }
    }

    fun canAccept(taskId: String): Boolean = transaction { links ->
        validateId(taskId)
        if (links.containsKey(taskId)) true
        else { reclaim(links); links.size < MAX_LINKS }
    }

    /** Only the submitting owner calls this after a definite pre-execution refusal. */
    fun releaseUnsubmitted(taskId: String): Unit = transaction { links ->
        validateId(taskId)
        if (links.containsKey(taskId) && links[taskId] == null) {
            links.remove(taskId)
            persist(links)
        }
    }

    fun put(taskId: String, jobId: String): Unit = transaction { links ->
        validateId(taskId); validateId(jobId)
        val existing = links[taskId]
        require(existing == null || existing == jobId) { "Task already belongs to a different daemon job" }
        if (existing != jobId) {
            require(links.containsKey(taskId) || links.size < MAX_LINKS) { "Job link ledger full; no result-backed links to reclaim" }
            links[taskId] = jobId
            persist(links)
        }
    }

    /** No age-only eviction: reclaim only identities whose confirmed result is durably retained elsewhere. */
    private fun reclaim(links: LinkedHashMap<String, String?>) {
        if (links.size < MAX_LINKS) return
        val retained = retainedResults()
        val retired = links.entries.filter { (task, job) -> job != null && retained[task] == job }.map { it.key }
        if (retired.isNotEmpty()) {
            retired.forEach(links::remove)
            persist(links)
        }
    }

    private fun load(): LinkedHashMap<String, String?> {
        val links = linkedMapOf<String, String?>()
        if (!file.exists()) return links
        require(file.length() <= MAX_LEDGER_BYTES) { "Job link ledger exceeds its bound" }
        file.forEachLine(Charsets.UTF_8) { line ->
            val fields = line.split('\t')
            require(fields.size == 2) { "Malformed job link ledger" }
            validateId(fields[0])
            val job = fields[1].takeUnless { it == "-" }
            job?.let(::validateId)
            require(!links.containsKey(fields[0])) { "Duplicate task in job link ledger" }
            require(links.size < MAX_LINKS) { "Job link ledger exceeds its bound" }
            links[fields[0]] = job
        }
        return links
    }

    private fun <T> transaction(block: (LinkedHashMap<String, String?>) -> T): T = synchronized(pathLock) {
        val parent = file.parentFile
        check(parent == null || parent.isDirectory || parent.mkdirs()) { "Unable to create job link directory" }
        RandomAccessFile(File(file.path + ".lock"), "rw").use { lockFile ->
            val lock = lockFile.channel.lock()
            try { block(load()) } finally { lock.release() }
        }
    }

    private fun persist(links: Map<String, String?>) {
        val temporary = File.createTempFile("job-links-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                val contents = links.entries.joinToString("\n", postfix = if (links.isEmpty()) "" else "\n") {
                    "${it.key}\t${it.value ?: "-"}"
                }
                stream.write(contents.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun validateId(id: String) {
        require(UUID_ID.matches(id)) { "Job link identifiers must be UUIDs" }
    }

    private companion object {
        const val MAX_LINKS = 1000
        const val MAX_LEDGER_BYTES = 80_000L
        val pathLocks = ConcurrentHashMap<String, Any>()
        val UUID_ID = Regex("(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
    }
}
