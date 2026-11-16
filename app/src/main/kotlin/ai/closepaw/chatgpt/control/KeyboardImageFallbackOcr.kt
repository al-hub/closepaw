package ai.closepaw.chatgpt.control

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Read only the certified in-memory key crop with the existing bundled model.
 * The caller still refreshes the exact keyboard pixels before dispatch. */
internal object KeyboardImageFallbackOcr {
    suspend fun confirms(proof: VisualTargetProof, crop: TapBounds, expected: String): Boolean = withContext(Dispatchers.Default) {
        if (!proof.sourceDisclosureAllowed() || proof.image.bytes.size !in 1..1_500_000) return@withContext false
        val decoded = BitmapFactory.decodeByteArray(proof.image.bytes, 0, proof.image.bytes.size) ?: return@withContext false
        val bitmap = try {
            if (decoded.width != proof.image.width || decoded.height != proof.image.height ||
                !TapBounds(0, 0, decoded.width, decoded.height).contains(crop)) return@withContext false
            Bitmap.createBitmap(decoded, crop.left, crop.top, crop.right - crop.left, crop.bottom - crop.top)
        } finally { decoded.recycle() }
        val client = try { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
            catch (_: RuntimeException) { bitmap.recycle(); return@withContext false }
        // ML Kit owns the bitmap until completion, including cancellation/timeout.
        withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine { continuation ->
                try {
                    client.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                        val result = try {
                            val element = if (task.isSuccessful)
                                task.result?.textBlocks?.flatMap { it.lines }?.flatMap { it.elements }?.singleOrNull()
                            else null
                            element != null && KeyboardImageFallbackPolicy.matchesRead(expected, element.text, element.confidence)
                        } catch (_: RuntimeException) { false }
                        finally { client.close(); bitmap.recycle() }
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (_: RuntimeException) {
                    client.close(); bitmap.recycle()
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        } == true && proof.sourceDisclosureAllowed()
    }
}
