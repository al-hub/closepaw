package ai.closepaw.chatgpt.operations

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Crash-consistent immutable overlay generations.
 * Legacy JSON is never rewritten after overflow starts. An atomic manifest
 * selects a complete, hash-verified snapshot of every overlay record.
 * Uncommitted generation files are ignored; missing/corrupt committed files
 * fail closed, retaining all older generations for forensic rollback.
 *
 * The caller holds the original per-process + OS ledger lock throughout.
 */
internal class OperationTaskRetainedOverlay(private val legacy: File) {
    private val root = File(legacy.path + ".history")
    private val marker = File(root, "enabled")
    private val empty = "closepaw-history-v2 empty"
    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun syncDirectory() {
        FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    private fun atomicWrite(target: File, data: ByteArray) {
        val temp = File.createTempFile("task-stage-", ".tmp", root)
        try {
            FileOutputStream(temp).use { output -> output.write(data); output.fd.sync() }
            Files.move(temp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory()
        } finally { temp.delete() }
    }
    fun isActive(): Boolean {
        if (marker.exists()) return true
        if (!root.exists()) return false
        require(root.isDirectory) { "invalid_history_directory" }
        val files = root.listFiles() ?: throw IllegalStateException("history_directory_unavailable")
        require(files.none { it.extension == "json" }) { "history_marker_missing" }
        return false
    }
    private fun manifest(): String {
        require(marker.isFile) { "invalid_history_marker" }
        val value = marker.readText(Charsets.UTF_8).trim()
        require(value == empty || Regex("closepaw-history-v2 [a-f0-9-]{36} [a-f0-9]{64}").matches(value)) {
            "invalid_history_marker"
        }
        return value
    }
    private fun readOverlay(): LinkedHashMap<String, JsonObject> {
        val result = linkedMapOf<String, JsonObject>()
        if (!isActive()) return result
        val manifest = manifest()
        if (manifest == empty) {
            // An initial empty generation is valid only before any snapshot
            // exists; otherwise an accidental manifest rollback would forget IDs.
            require(root.listFiles()?.none { it.extension == "json" } == true) { "history_manifest_rollback" }
            return result
        }
        val (_, generation, digest) = manifest.split(' ')
        val file = File(root, "$generation.json")
        require(file.isFile) { "committed_history_missing" }
        val bytes = file.readBytes()
        require(sha(bytes) == digest) { "committed_history_corrupt" }
        val records = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray
        records.forEach { element ->
            val task = element.jsonObject
            val id = (task["task_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("missing_history_task_id")
            require(result.putIfAbsent(id, task) == null) { "duplicate_history_identity" }
        }
        // Prior immutable snapshots may not contain identities absent from
        // the manifest-selected generation. This detects a stale manifest
        // hiding a newer committed claim and fails closed, never replaying it.
        root.listFiles()?.filter { it.extension == "json" && it != file }?.forEach { earlier ->
            val prior = Json.parseToJsonElement(earlier.readText()).jsonArray
            prior.forEach { item ->
                val identity = item.jsonObject["task_id"]?.jsonPrimitive?.content
                require(identity != null && identity in result) { "history_manifest_identity_rollback" }
            }
        } ?: throw IllegalStateException("history_directory_unavailable")
        OperationTaskHistoryPreflight.inspect(result.values)
        return result
    }
    fun mergeInto(records: LinkedHashMap<String, JsonObject>) {
        val overlay = readOverlay()
        overlay.forEach { (id, task) -> records[id] = task }
        if (isActive()) OperationTaskHistoryPreflight.inspect(records.values)
    }
    fun persistChanges(
        desired: Map<String, JsonObject>,
        readLegacy: () -> LinkedHashMap<String, JsonObject>,
    ) {
        require(root.isDirectory || root.mkdirs()) { "history_directory_unavailable" }
        val existing = readLegacy()
        mergeInto(existing)
        require(desired.keys.containsAll(existing.keys)) { "history_loss_detected" }
        OperationTaskHistoryPreflight.inspect(desired.values)
        val legacyIds = readLegacy().keys
        val overlay = readOverlay()
        desired.forEach { (id, record) ->
            if (id !in legacyIds || existing[id] != record) overlay[id] = record
        }
        val data = JsonArray(overlay.values.toList()).toString().toByteArray(Charsets.UTF_8)
        val generation = UUID.randomUUID().toString()
        val candidate = File(root, "$generation.json")
        // Stage generation BEFORE pointer publication. No existing generations
        // are deleted, and the original ledger remains authoritative for old IDs.
        atomicWrite(candidate, data)
        atomicWrite(marker, "closepaw-history-v2 $generation ${sha(data)}\n".toByteArray(Charsets.UTF_8))
    }
}
