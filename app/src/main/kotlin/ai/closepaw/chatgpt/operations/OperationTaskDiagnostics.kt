package ai.closepaw.chatgpt.operations

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.attribute.BasicFileAttributes
import java.io.File
import java.nio.file.*
import kotlinx.serialization.json.*

/** Passive atomic-snapshot inspection. Never opens the write lock, constructs a store, or repairs data. */
internal class OperationTaskStorageException(val stage: String, val reason: String, cause: Exception) :
    IllegalArgumentException("Task storage unavailable", cause)

internal class OperationTaskDiagnosticLimitException : IllegalArgumentException("Diagnostic structure bound")

internal class OperationTaskDiagnostics(
    private val file: File,
    private val openInput: () -> InputStream = { file.inputStream() },
) {
    @Volatile private var lastFailure: Pair<String, String>? = null
    fun record(stage: String, error: Exception) { lastFailure = stage to reason(error) }
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
            val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            require(before.isRegularFile)
            put("file_bytes", before.size())
            val counts = openInput().use { stream ->
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                InputStreamReader(BoundedInput(stream), decoder).buffered(8192).use {
                    OperationTaskDiagnosticScanner(it).scan()
                }
            }
            val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            if (before.fileKey() != after.fileKey() || before.size() != after.size() ||
                before.lastModifiedTime() != after.lastModifiedTime()) {
                put("read_state", "bounded_refusal"); put("reason_code", "ledger_changed")
                return@buildJsonObject
            }
            put("read_state", "readable")
            put("task_count", counts.tasks)
            put("active_count", counts.active); put("terminal_count", counts.terminal); put("unknown_count", counts.unknown)
            put("reason_code", if (counts.tasks == OperationTaskStore.MAX_TASKS) "capacity_reached" else "snapshot_readable")
            // A readable snapshot is not proof that the next claim/fsync/replace will succeed.
        } catch (error: Exception) {
            put("read_state", if (error is OperationTaskDiagnosticLimitException || error is OperationTaskDiagnosticCountException || error is OperationTaskDiagnosticByteException) "bounded_refusal" else "failed")
            put("reason_code", when (error) {
                is OperationTaskDiagnosticLimitException -> "diagnostic_structure_limit"
                is OperationTaskDiagnosticCountException -> "record_count_exceeded"
                is OperationTaskDiagnosticByteException -> "ledger_too_large"
                is java.nio.charset.CharacterCodingException -> "invalid_ledger"
                is IllegalArgumentException -> "invalid_ledger"
                else -> reason(error)
            })
            put("exception_category", category(error))
        }
    }

    /** Reads at most MAX_BYTES plus one sentinel byte, including decoder read-ahead. */
    private class BoundedInput(input: InputStream) : FilterInputStream(input) {
        private var count = 0
        override fun read(): Int {
            val value = `in`.read()
            if (value >= 0 && ++count > MAX_BYTES) throw OperationTaskDiagnosticByteException()
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val read = `in`.read(buffer, offset, minOf(length, MAX_BYTES - count + 1))
            if (read > 0) { count += read; if (count > MAX_BYTES) throw OperationTaskDiagnosticByteException() }
            return read
        }
    }

    companion object {
        // Measured 12.9MB snapshot fits; streaming discards payloads with independent
        // depth/node/task/identity bounds. This is not an admission quota or repair.
        const val MAX_BYTES = 16 * 1024 * 1024
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
