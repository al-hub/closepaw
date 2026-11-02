package ai.closepaw.chatgpt.operations

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Offline, opt-in staging primitive for #219 migration.
 *
 * Deliberately not wired to live Task Store. A staged generation never deletes,
 * replaces, or archives the legacy ledger. Activation must verify record-level
 * identity, history reads and cross-tier dedup separately.
 */
internal class OperationTaskHistoryStage(private val root: File) {
    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val bytes = ByteArray(8192)
            while (true) {
                val size = stream.read(bytes)
                if (size < 0) break
                md.update(bytes, 0, size)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Creates an immutable, content-addressed copy. On any failure the source is
     * unchanged and no manifest is published. Unexpected destination content
     * is always rejected rather than overwritten.
     */
    fun stageLegacy(source: File, onBoundary: (String) -> Unit = {}): File {
        require(source.isFile && source.canonicalFile != root.canonicalFile) { "invalid_legacy_source" }
        val initialBytes = source.length()
        val initialDigest = digest(source)
        require(root.isDirectory || root.mkdirs()) { "stage_directory_unavailable" }
        val target = File(root, "legacy-$initialDigest.json")
        if (target.exists()) {
            require(target.isFile && target.length() == initialBytes && digest(target) == initialDigest) {
                "existing_staged_copy_mismatch"
            }
            return target
        }
        val staging = File.createTempFile("legacy-stage-", ".tmp", root)
        try {
            onBoundary("stage_copy")
            source.inputStream().use { input ->
                FileOutputStream(staging).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                    onBoundary("stage_fsync")
                    output.fd.sync()
                }
            }
            onBoundary("stage_verify")
            require(source.length() == initialBytes && digest(source) == initialDigest) { "legacy_source_changed" }
            require(staging.length() == initialBytes && digest(staging) == initialDigest) { "staged_copy_mismatch" }
            onBoundary("stage_publish")
            // Atomic no-overwrite publication: a hard link refuses an existing name.
            // If the filesystem does not support links, fail closed rather than overwrite.
            try {
                Files.createLink(target.toPath(), staging.toPath())
            } catch (alreadyExists: java.nio.file.FileAlreadyExistsException) {
                require(target.isFile && target.length() == initialBytes && digest(target) == initialDigest) {
                    "existing_staged_copy_mismatch"
                }
            }
            onBoundary("directory_fsync")
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
            require(source.length() == initialBytes && digest(source) == initialDigest) { "legacy_source_changed" }
            require(target.length() == initialBytes && digest(target) == initialDigest) { "published_copy_mismatch" }
            // No manifest or live-store switch occurs here.
            return target
        } finally {
            staging.delete()
        }
    }
}
