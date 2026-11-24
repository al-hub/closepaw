package ai.closepaw.qa

import ai.closepaw.BuildConfig
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.json.*

/** Local per-run opt-in only. No exported component, remote permission or raw screen data. */
internal object DebugQaDownloadExport {
    fun publish(context: Context, batchId: String) {
        if (!BuildConfig.DEBUG || context.packageName != CaptureQaAcceptance.CANDIDATE ||
            !batchId.matches(Regex("[a-f0-9-]{36}"))) return
        val batch = DebugOneStopQa.lastBatchResult(context) ?: return
        if (batch["batch_id"]?.jsonPrimitive?.content != batchId ||
            batch["state"]?.jsonPrimitive?.content !in setOf("BLOCKED", "DIAGNOSTIC_COMPLETE", "PASS", "FAILED", "CANCELLED")) return
        val last = DebugOneStopQa.lastResult(context)?.takeIf { it["batch_id"]?.jsonPrimitive?.content == batchId }
        val bytes = buildJsonObject {
            put("format", "debug_qa_redacted_v1")
            last?.let { put("last_run", it) }
            put("last_batch", batch)
        }.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..128_000) return
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "closepaw-debug-qa-$batchId.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClosePawQA")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: return
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("qa_export_unavailable")
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1)
                error("qa_export_uncommitted")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }
}
