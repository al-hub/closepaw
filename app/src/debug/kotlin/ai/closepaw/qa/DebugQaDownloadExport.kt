package ai.closepaw.qa

import ai.closepaw.BuildConfig
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.util.UUID
import kotlinx.serialization.json.*

internal enum class DebugQaExportOutcome {
    NOT_ENABLED, REVOKED, SAVED, FAILED,
}

internal fun exportDebugQaResult(
    grant: String?,
    currentGrant: () -> String?,
    publish: () -> Boolean,
): DebugQaExportOutcome = runCatching {
    when {
        grant == null -> DebugQaExportOutcome.NOT_ENABLED
        grant != currentGrant() -> DebugQaExportOutcome.REVOKED
        publish() -> DebugQaExportOutcome.SAVED
        else -> DebugQaExportOutcome.REVOKED
    }
}.getOrDefault(DebugQaExportOutcome.FAILED)

/** Only a local opt-in can copy a terminal, same-source redacted batch to shared Downloads. */
internal object DebugQaDownloadExport {
    private const val GRANT = "download_grant"

    private fun prefs(context: Context) = context.getSharedPreferences("debug_qa_export", Context.MODE_PRIVATE)

    fun grant(context: Context): String? = prefs(context).getString(GRANT, null)

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        check(BuildConfig.DEBUG && context.packageName == CaptureQaAcceptance.CANDIDATE)
        prefs(context).edit().apply {
            if (enabled) putString(GRANT, UUID.randomUUID().toString()) else remove(GRANT)
        }.apply()
    }

    fun complete(context: Context, run: DebugQaApprovedRun, batchId: String): DebugQaExportOutcome =
        exportDebugQaResult(run.request.exportGrant, { grant(context) }) {
            publish(context, batchId, checkNotNull(run.request.exportGrant))
        }

    private fun publish(context: Context, batchId: String, grant: String): Boolean {
        check(BuildConfig.DEBUG && context.packageName == CaptureQaAcceptance.CANDIDATE)
        check(batchId.matches(Regex("[a-f0-9-]{36}")))
        val batch = checkNotNull(DebugOneStopQa.lastBatchResult(context))
        check(batch["batch_id"]?.jsonPrimitive?.content == batchId)
        check(batch["state"]?.jsonPrimitive?.content in setOf(
            "BLOCKED", "DIAGNOSTIC_COMPLETE", "PASS", "FAILED", "CANCELLED", "UNKNOWN"))
        val last = DebugOneStopQa.lastResult(context)?.takeIf { it["batch_id"]?.jsonPrimitive?.content == batchId }
        val bytes = buildJsonObject {
            put("format", "debug_qa_redacted_v1")
            last?.let { put("last_run", it) }
            put("last_batch", batch)
        }.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size in 1..128_000)
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "closepaw-debug-qa-$batchId.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClosePawQA")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var committed = false
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes) }
            // Revocation and the moment the staged file becomes public are serialized.
            synchronized(this) {
                if (grant != grant(context)) return false
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) == 1)
                committed = true
            }
            return true
        } finally {
            if (!committed) runCatching { resolver.delete(uri, null, null) }
        }
    }
}
