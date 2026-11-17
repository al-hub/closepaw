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
internal class KeyboardImageFallbackOcr {
    private var owner: KeyboardTouchGeometry.Owner? = null
    private val confirmed = LinkedHashSet<String>()
    @Synchronized fun clear() { owner = null; confirmed.clear() }
    @Synchronized private fun cacheHit(current: KeyboardTouchGeometry.Owner, digest: String): Boolean {
        if (owner != current) { clear(); owner = current }
        return digest in confirmed
    }
    @Synchronized private fun remember(current: KeyboardTouchGeometry.Owner, digest: String) {
        if (owner != current) return
        confirmed.add(digest)
        while (confirmed.size > 64) confirmed.remove(confirmed.first())
    }
    suspend fun confirms(current: KeyboardTouchGeometry.Owner, proof: VisualTargetProof, crop: TapBounds,
        expected: String): Boolean = withContext(Dispatchers.Default) {
        if (!proof.sourceDisclosureAllowed() || proof.image.bytes.size !in 1..1_500_000) return@withContext false
        val decoded = BitmapFactory.decodeByteArray(proof.image.bytes, 0, proof.image.bytes.size) ?: return@withContext false
        val bitmap = try {
            if (decoded.width != proof.image.width || decoded.height != proof.image.height ||
                !TapBounds(0, 0, decoded.width, decoded.height).contains(crop)) return@withContext false
            Bitmap.createBitmap(decoded, crop.left, crop.top, crop.right - crop.left, crop.bottom - crop.top)
        } finally { decoded.recycle() }
        val digest = try {
            val pixels = java.nio.ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(pixels)
            java.security.MessageDigest.getInstance("SHA-256").apply {
                update(expected.toByteArray(Charsets.UTF_8))
                update("${bitmap.width}x${bitmap.height}:".toByteArray(Charsets.UTF_8))
            }.digest(pixels.array()).let { java.util.Base64.getEncoder().encodeToString(it) }
        } catch (_: RuntimeException) { bitmap.recycle(); return@withContext false }
        if (cacheHit(current, digest)) {
            bitmap.recycle()
            ControlDiagnostics.record("keyboard_role_image", "reused")
            return@withContext proof.sourceDisclosureAllowed()
        }
        if (expected == "Space") {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (i in pixels.indices) {
                val p = pixels[i]
                pixels[i] = (77 * (p shr 16 and 255) + 150 * (p shr 8 and 255) + 29 * (p and 255)) shr 8
            }
            if (SpaceBarGlyphPolicy.matches(bitmap.width, bitmap.height, pixels)) {
                bitmap.recycle()
                if (!proof.sourceDisclosureAllowed()) return@withContext false
                remember(current, digest)
                ControlDiagnostics.record("keyboard_role_image", "space_glyph")
                return@withContext true
            }
        }
        val client = try { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
            catch (_: RuntimeException) { bitmap.recycle(); return@withContext false }
        // ML Kit owns the bitmap until completion, including cancellation/timeout.
        val recognized = withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine { continuation ->
                try {
                    client.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                        val result = try {
                            val line = if (task.isSuccessful)
                                task.result?.textBlocks?.flatMap { it.lines }?.singleOrNull()
                            else null
                            val confidence = line?.elements?.minOfOrNull { it.confidence }
                            line != null && confidence != null &&
                                KeyboardImageFallbackPolicy.matchesRead(expected, line.text, confidence)
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
        if (recognized) remember(current, digest)
        ControlDiagnostics.record("keyboard_role_image", if (recognized) "computed" else "unverified")
        recognized
    }
}
