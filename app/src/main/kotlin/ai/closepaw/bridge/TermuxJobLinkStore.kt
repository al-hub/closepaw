package ai.closepaw.bridge

import java.io.File
import java.io.FileOutputStream

/** Persistent task-to-daemon-job identity only. A missing link never authorizes resubmission. */
class TermuxJobLinkStore(private val file: File) {
    private val links = linkedMapOf<String, String>()

    init {
        if (file.exists()) {
            require(file.length() <= MAX_LEDGER_BYTES) { "Job link ledger exceeds its bound" }
            file.forEachLine(Charsets.UTF_8) { line ->
                val fields = line.split('\t')
                require(fields.size == 2) { "Malformed job link ledger" }
                validateId(fields[0]); validateId(fields[1])
                require(!links.containsKey(fields[0])) { "Duplicate task in job link ledger" }
                require(links.size < MAX_LINKS) { "Job link ledger exceeds its bound" }
                links[fields[0]] = fields[1]
            }
        }
    }

    @Synchronized fun get(taskId: String): String? {
        validateId(taskId)
        return links[taskId]
    }

    @Synchronized fun put(taskId: String, jobId: String) {
        validateId(taskId); validateId(jobId)
        val existing = links[taskId]
        require(existing == null || existing == jobId) { "Task already belongs to a different daemon job" }
        if (existing == jobId) return
        require(links.size < MAX_LINKS) { "Job link ledger full; archive explicitly before accepting new links" }
        links[taskId] = jobId
        try {
            persist()
        } catch (error: Exception) {
            links.remove(taskId)
            throw error
        }
    }

    private fun persist() {
        val parent = file.absoluteFile.parentFile
        check(parent == null || parent.isDirectory || parent.mkdirs()) { "Unable to create job link directory" }
        val temporary = File(file.absolutePath + ".tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                val contents = links.entries.joinToString("\n", postfix = "\n") { "${it.key}\t${it.value}" }
                stream.write(contents.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(temporary.renameTo(file)) { "Unable to atomically persist job links" }
        } finally {
            temporary.delete()
        }
    }

    private fun validateId(id: String) {
        require(UUID_ID.matches(id)) { "Job link identifiers must be UUIDs" }
    }

    private companion object {
        const val MAX_LINKS = 1000
        const val MAX_LEDGER_BYTES = 80_000L
        val UUID_ID = Regex("(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
    }
}
