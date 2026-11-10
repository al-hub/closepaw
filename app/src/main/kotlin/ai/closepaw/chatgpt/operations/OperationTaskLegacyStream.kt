package ai.closepaw.chatgpt.operations

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject

/** Immutable legacy input, strict UTF-8, bounded bytes and per-record structure. */
internal object OperationTaskLegacyStream {
    data class Snapshot(val counts: OperationTaskDiagnosticScanner.Counts, val sha256: String)

    fun read(file: File, openInput: () -> InputStream = { file.inputStream() },
             onRecord: ((JsonObject) -> Unit)? = null): Snapshot {
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        require(before.isRegularFile) { "legacy_not_regular" }
        if (before.size() > OperationTaskDiagnostics.MAX_BYTES) throw OperationTaskDiagnosticByteException()
        val digest = MessageDigest.getInstance("SHA-256")
        val counts = openInput().use { input ->
            val bounded = object : FilterInputStream(input) {
                var count = 0
                override fun read(): Int {
                    val value = `in`.read()
                    if (value >= 0 && ++count > OperationTaskDiagnostics.MAX_BYTES) throw OperationTaskDiagnosticByteException()
                    return value
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    val n = `in`.read(buffer, offset, minOf(length, OperationTaskDiagnostics.MAX_BYTES - count + 1))
                    if (n > 0) { count += n; if (count > OperationTaskDiagnostics.MAX_BYTES) throw OperationTaskDiagnosticByteException() }
                    return n
                }
            }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            InputStreamReader(DigestInputStream(bounded, digest), decoder).buffered(8192).use {
                OperationTaskDiagnosticScanner(it, historical = true, onRecord = onRecord).scan()
            }
        }
        val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        require(before.fileKey() == after.fileKey() && before.size() == after.size() &&
            before.lastModifiedTime() == after.lastModifiedTime()) { "ledger_changed" }
        return Snapshot(counts, digest.digest().joinToString("") { "%02x".format(it) })
    }

    fun absentDigest(): String = MessageDigest.getInstance("SHA-256")
        .digest("absent".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
