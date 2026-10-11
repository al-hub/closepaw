package ai.closepaw.chatgpt

import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Visible-only OCR result. No screenshot or page content is stored in diagnostics. */
internal data class BrowserOcrLine(val text: String, val centerY: Int)

internal object BrowserScreenshotOcrPolicy {
    fun pageText(lines: List<BrowserOcrLine>, screenshotHeight: Int): String {
        if (screenshotHeight <= 0) return ""
        val top = (screenshotHeight * 0.15).toInt()
        val bottom = (screenshotHeight * 0.88).toInt()
        val bodyLines = lines.asSequence()
            .filter { it.centerY in top..bottom }
            .sortedBy { it.centerY }
            .map { it.text.trim() }
            .filter { !BrowserReadQuality.isBrowserChrome(it) }
            .distinct()
            .toList()
        val body = bodyLines.joinToString("\n")
        // An isolated button, heading or toolbar fragment is not an article.
        return if (bodyLines.size >= 2 && body.length >= 35) body else ""
    }
}

internal fun interface BrowserScreenshotOcr {
    suspend fun read(jpeg: ByteArray): String
}

/**
 * Local, bounded OCR fallback for Samsung Internet WebViews that expose only
 * browser chrome via Accessibility. This never uploads images to a third party.
 */
internal class BundledKoreanScreenshotOcr : BrowserScreenshotOcr {
    override suspend fun read(jpeg: ByteArray): String = withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@withContext ""
        try {
            val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            try {
                val result = withTimeoutOrNull(MAX_OCR_MS) {
                    suspendCancellableCoroutine<Text> { continuation ->
                        client.process(InputImage.fromBitmap(bitmap, 0))
                            .addOnSuccessListener { text ->
                                if (continuation.isActive) continuation.resume(text)
                            }
                            .addOnFailureListener { failure ->
                                if (continuation.isActive) continuation.resumeWithException(failure)
                            }
                    }
                } ?: return@withContext ""
                val lines = result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        line.boundingBox?.let { BrowserOcrLine(line.text, it.centerY()) }
                    }
                }
                BrowserScreenshotOcrPolicy.pageText(lines, bitmap.height)
            } finally {
                client.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Do not log recognized user text or the screenshot bytes.
            Log.w("ClosePawBrowserOCR", "OCR fallback unavailable: ${error.javaClass.simpleName}")
            ""
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_OCR_MS = 4_000L
    }
}
