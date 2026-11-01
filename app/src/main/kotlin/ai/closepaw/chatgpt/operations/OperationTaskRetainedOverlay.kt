package ai.closepaw.chatgpt.operations

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.json.*

/**
 * Lossless overflow storage. Legacy tasks.json remains untouched once active.
 * One atomic file per changed task; the original store's OS lock serializes
 * all reads and writes across processes. No task is executed before its claim
 * has been durably committed.
 *
 * The marker deliberately activates the overlay BEFORE the first task commit:
 * interruption at that point leaves an empty, recoverable overlay.
 */
internal class OperationTaskRetainedOverlay(private val legacy: File) {
    private val root = File(legacy.path + ".history")
    private val marker = File(root, "enabled")
    fun isActive(): Boolean {
        if (marker.exists()) return true
        if (!root.exists()) return false
        require(root.isDirectory) { "invalid_history_directory" }
        val files = root.listFiles() ?: throw IllegalStateException("history_directory_unavailable")
        // A missing marker cannot silently hide already committed claims.
        require(files.none { it.extension == "json" }) { "history_marker_missing" }
        return false
    }

    private fun filename(id: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) } + ".json"
    }
    private fun syncDirectory() {
        FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    private fun atomicWrite(target: File, data: ByteArray) {
        val temp = File.createTempFile("task-stage-", ".tmp", root)
        try {
            FileOutputStream(temp).use { output ->
                output.write(data)
                output.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory()
        } finally {
            temp.delete()
        }
    }
    private fun activate() {
        if (isActive()) return
        require(root.isDirectory || root.mkdirs()) { "history_directory_unavailable" }
        // The lock in OperationTaskStore already excludes concurrent activation.
        atomicWrite(marker, "closepaw-history-v1\n".toByteArray(Charsets.UTF_8))
    }
    fun mergeInto(records: LinkedHashMap<String, JsonObject>) {
        if (!isActive()) {
            // A staged but uncommitted directory is not authoritative.
            return
        }
        require(marker.readText() == "closepaw-history-v1\n") { "invalid_history_marker" }
        require(root.isDirectory) { "history_directory_unavailable" }
        val seen = HashSet<String>()
        root.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }?.forEach { item ->
            require(item.isFile) { "invalid_history_entry" }
            val task = Json.parseToJsonElement(item.readText()).jsonObject
            val id = (task["task_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("missing_history_task_id")
            require(item.name == filename(id) && seen.add(id)) { "history_identity_mismatch" }
            records[id] = task
        } ?: throw IllegalStateException("history_directory_unavailable")
        OperationTaskHistoryPreflight.inspect(records.values)
    }
    fun persistChanges(
        desired: Map<String, JsonObject>,
        readLegacy: () -> LinkedHashMap<String, JsonObject>,
    ) {
        // No old record is deleted/archived, and no partial replacement of the
        // legacy ledger is ever attempted.
        if (!isActive()) activate()
        val existing = readLegacy()
        mergeInto(existing)
        require(desired.keys.containsAll(existing.keys)) { "history_loss_detected" }
        OperationTaskHistoryPreflight.inspect(desired.values)
        desired.forEach { (id, record) ->
            if (existing[id] != record) {
                val target = File(root, filename(id))
                atomicWrite(target, record.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }
}
