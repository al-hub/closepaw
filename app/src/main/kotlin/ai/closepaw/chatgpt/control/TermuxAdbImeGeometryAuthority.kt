package ai.closepaw.chatgpt.control

import ai.closepaw.bridge.*
import android.content.Context
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*

/** No connect, pairing, recovery, settings writes or caller-provided commands.
 * The nonce proves that the already-connected adb device is this app's device.
 * Its contents never enter the shell command or durable job output. */
internal class TermuxAdbImeGeometryAuthority(
    private val context: Context, private val client: TermuxJobClient, private val allowed: () -> Boolean,
) {
    internal inner class Lease(
        private val nonce: File, private val hash: String, private val windowId: Int,
        private val touch: TapBounds, private val method: String, private val guard: () -> Boolean,
    ) : AutoCloseable {
        var sampledAtUptimeMs: Long = -1; private set
        suspend fun sample(): ImeSurfaceGeometry? {
            if (!allowed() || !guard()) return null
            val script = withContext(Dispatchers.IO) {
                context.assets.open("control/ime_geometry.py").use { Base64.getEncoder().encodeToString(it.readBytes()) }
            }
            // Both strings are native-owned and restricted before command construction.
            if (!Regex("""/storage/emulated/[0-9]+/Android/data/ai\.closepaw/files/ime-geometry/[a-f0-9-]+\.nonce""").matches(nonce.path) ||
                !Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(method)) return null
            val command = "python -c 'import base64;exec(base64.b64decode(\"$script\"))' " +
                "$windowId ${touch.left} ${touch.top} ${touch.right} ${touch.bottom} '${nonce.path}' '$method'"
            val requestId = "ime-geometry-${UUID.randomUUID()}"
            val raw = withTimeoutOrNull(2_500) {
                val started = client.start(requestId, ExecutionRequest(ExecutionCapability.LINUX_SHELL, command, timeoutMs = 2_000))
                    as? TermuxJobReply.Success ?: return@withTimeoutOrNull null
                var s = started.snapshot
                while (!s.terminal) {
                    ensureActive()
                    if (!allowed() || !guard()) return@withTimeoutOrNull null
                    delay(25)
                    s = (client.poll(s.jobId, limit = 4096) as? TermuxJobReply.Success)?.snapshot
                        ?: return@withTimeoutOrNull null
                }
                if (s.state != "succeeded" || s.exitCode != 0 || s.outcomeUnknown || s.truncated ||
                    s.stderr.isNotEmpty() || s.stdout.toByteArray().size > 2048) null else s.stdout
            } ?: return null
            if (!allowed() || !guard()) return null
            val result = ImeSurfaceGeometry.parse(raw, windowId, touch, hash) ?: return null
            sampledAtUptimeMs = SystemClock.uptimeMillis()
            return result
        }
        override fun close() { nonce.delete() }
    }

    suspend fun lease(windowId: Int, touch: TapBounds, method: String, guard: () -> Boolean): Lease? = withContext(Dispatchers.IO) {
        if (!allowed() || !guard() || windowId < 0 || !touch.valid) return@withContext null
        var file: File? = null
        try {
            val root = context.getExternalFilesDir(null) ?: return@withContext null
            val dir = File(root, "ime-geometry")
            if (!dir.isDirectory && !dir.mkdirs()) return@withContext null
            val nonce = File(dir, "${UUID.randomUUID()}.nonce"); file = nonce
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            if (!nonce.createNewFile()) return@withContext null
            nonce.outputStream().use { it.write(bytes); it.flush() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!allowed() || !guard()) { nonce.delete(); return@withContext null }
            Lease(nonce, hash, windowId, touch, method, guard)
        } catch (e: CancellationException) { file?.delete(); throw e }
        catch (_: Exception) { file?.delete(); null }
    }
}
