package ai.closepaw.chatgpt.operations

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.*
import kotlinx.serialization.json.*

/** Passive atomic-snapshot inspection. Never opens the write lock, constructs a store, or repairs data. */
internal class OperationTaskStorageException(val stage: String, val reason: String, cause: Exception) :
    IllegalArgumentException("Task storage unavailable", cause)

internal class OperationTaskDiagnostics(private val file: File) {
    @Volatile private var lastFailure: Pair<String, String>? = null
    fun record(stage: String, error: Exception) { lastFailure = stage to reason(error) }
    private fun checkStructure(raw: ByteArray) {
        var depth = 0
        var nodes = 0
        var inString = false
        var escaped = false
        raw.forEach { byte ->
            val ch = byte.toInt().toChar()
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> { inString = true; nodes++ }
                '{', '[' -> { depth++; nodes++ }
                '}', ']' -> depth--
                ',', ':' -> nodes++
            }
            require(depth in 0..32 && nodes <= 65536) { "Structure exceeds diagnostic bounds" }
        }
    }
    fun snapshot(): JsonObject = buildJsonObject {
        lastFailure?.let { failure ->
            put("last_failure_stage", failure.first); put("last_failure_reason", failure.second)
        }
        put("diagnostic_version", 1)
        put("max_tasks", OperationTaskStore.MAX_TASKS)
        put("max_read_bytes", MAX_BYTES)
        put("write_test_performed", false)
        put("recovery_attempted", false)
        put("snapshot_only", true)
        put("write_health", "not_tested")
        try {
            var volume: File? = file.parentFile
            while (volume != null && !volume.exists()) volume = volume.parentFile
            volume?.let { put("usable_bytes", it.usableSpace) }
            put("file_exists", file.exists())
            if (!file.exists()) {
                put("read_state", "absent"); put("reason_code", "ledger_absent")
                return@buildJsonObject
            }
            put("file_bytes", file.length())
            if (file.length() > MAX_BYTES) {
                put("read_state", "bounded_refusal"); put("reason_code", "ledger_too_large")
                return@buildJsonObject
            }
            val bytes = ByteArrayOutputStream()
            file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (bytes.size().toLong() + count > MAX_BYTES) {
                        put("read_state", "bounded_refusal"); put("reason_code", "ledger_too_large")
                        return@buildJsonObject
                    }
                    bytes.write(buffer, 0, count)
                }
            }
            val raw = bytes.toByteArray()
            checkStructure(raw)
            val tasks = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonArray
                ?: throw IllegalArgumentException("array required")
            // The store cannot admit more than MAX_TASKS. Refuse unexpected larger snapshots.
            if (tasks.size > OperationTaskStore.MAX_TASKS) {
                put("read_state", "bounded_refusal"); put("reason_code", "record_count_exceeded")
                return@buildJsonObject
            }
            val ids = HashSet<String>()
            val requests = HashSet<String>()
            var active = 0
            var terminal = 0
            var unknown = 0
            tasks.forEach { item ->
                val task = item as? JsonObject ?: throw IllegalArgumentException("object required")
                val id = (task["task_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("id required")
                require(ids.add(id))
                val request = (task["request_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (request != null) require(requests.add(request))
                when ((task["state"] as? JsonPrimitive)?.content) {
                    "queued", "running", "cancel_requested" -> active++
                    "succeeded", "failed", "cancelled", "timed_out", "interrupted" -> terminal++
                    else -> unknown++
                }
            }
            put("read_state", "readable")
            put("task_count", tasks.size)
            put("active_count", active); put("terminal_count", terminal); put("unknown_count", unknown)
            put("reason_code", if (tasks.size == OperationTaskStore.MAX_TASKS) "capacity_reached" else "snapshot_readable")
            // A readable snapshot is not proof that the next claim/fsync/replace will succeed.
        } catch (error: Exception) {
            put("read_state", "failed")
            put("reason_code", if (error is IllegalArgumentException) "invalid_ledger" else reason(error))
            put("exception_category", category(error))
        }
    }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        fun category(error: Exception): String = when (error) {
            is AtomicMoveNotSupportedException -> "atomic_move_unsupported"
            is AccessDeniedException, is SecurityException -> "access_denied"
            is FileAlreadyExistsException -> "file_already_exists"
            is NoSuchFileException, is java.io.FileNotFoundException -> "file_unavailable"
            is java.nio.channels.OverlappingFileLockException -> "lock_overlap"
            is java.nio.channels.FileLockInterruptionException -> "lock_interrupted"
            is java.io.IOException -> "io_exception"
            is IllegalArgumentException -> "invalid_data_or_limit"
            else -> "other_exception"
        }
        fun reason(error: Exception): String {
            if (error is OperationTaskStorageException) return error.reason
            // Inspect OS text only to select constants; never return messages, paths or payloads.
            val message = error.message.orEmpty().take(4096).lowercase(java.util.Locale.ROOT)
            return when {
                "enospc" in message || "no space left" in message -> "no_space"
                "eacces" in message || "permission denied" in message -> "access_denied"
                else -> category(error)
            }
        }
    }
}
