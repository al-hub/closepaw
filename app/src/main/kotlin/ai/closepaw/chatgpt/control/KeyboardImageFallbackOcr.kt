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
    internal data class RecognitionOutcome(
        val confirmed: Boolean,
        val method: String = "none",
        val failureStage: String? = null,
        val cropWidth: Int = 0,
        val cropHeight: Int = 0,
        val ocrAttempted: Boolean = false,
        val ocrLabelMatched: Boolean? = null,
        val ocrConfidenceBucket: String = "not_run",
    )

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
        expected: String): Boolean = confirmDetailed(current, proof, crop, expected).confirmed

    suspend fun confirmDetailed(current: KeyboardTouchGeometry.Owner, proof: VisualTargetProof, crop: TapBounds,
        expected: String): RecognitionOutcome = withContext(Dispatchers.Default) {
        if (!proof.sourceDisclosureAllowed())
            return@withContext RecognitionOutcome(false, failureStage = "proof_disclosure_revoked")
        if (proof.image.bytes.size !in 1..1_500_000)
            return@withContext RecognitionOutcome(false, failureStage = "image_bytes_invalid")
        val decoded = BitmapFactory.decodeByteArray(proof.image.bytes, 0, proof.image.bytes.size)
            ?: return@withContext RecognitionOutcome(false, failureStage = "image_decode_failed")
        val bitmap = try {
            if (decoded.width != proof.image.width || decoded.height != proof.image.height ||
                !TapBounds(0, 0, decoded.width, decoded.height).contains(crop))
                return@withContext RecognitionOutcome(false, failureStage = "crop_outside_decoded_image")
            Bitmap.createBitmap(decoded, crop.left, crop.top, crop.right - crop.left, crop.bottom - crop.top)
        } finally { decoded.recycle() }
        val cropW = bitmap.width
        val cropH = bitmap.height
        val digest = try {
            val pixels = java.nio.ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(pixels)
            java.security.MessageDigest.getInstance("SHA-256").apply {
                update(expected.toByteArray(Charsets.UTF_8))
                update("${cropW}x${cropH}:".toByteArray(Charsets.UTF_8))
            }.digest(pixels.array()).let { java.util.Base64.getEncoder().encodeToString(it) }
        } catch (_: RuntimeException) {
            bitmap.recycle()
            return@withContext RecognitionOutcome(false, failureStage = "crop_digest_failed", cropWidth = cropW, cropHeight = cropH)
        }
        if (cacheHit(current, digest)) {
            bitmap.recycle()
            ControlDiagnostics.record("keyboard_role_image", "reused")
            val stillAllowed = proof.sourceDisclosureAllowed()
            return@withContext RecognitionOutcome(
                confirmed = stillAllowed,
                method = if (stillAllowed) "cache_reused" else "none",
                failureStage = if (stillAllowed) null else "post_disclosure_revoked",
                cropWidth = cropW, cropHeight = cropH,
            )
        }
        val usesSpaceGlyphPolicy = expected == "Space"
        if (usesSpaceGlyphPolicy) {
            val pixels = IntArray(cropW * cropH)
            bitmap.getPixels(pixels, 0, cropW, 0, 0, cropW, cropH)
            for (i in pixels.indices) {
                val p = pixels[i]
                pixels[i] = (77 * (p shr 16 and 255) + 150 * (p shr 8 and 255) + 29 * (p and 255)) shr 8
            }
            if (SpaceBarGlyphPolicy.matches(cropW, cropH, pixels)) {
                bitmap.recycle()
                if (!proof.sourceDisclosureAllowed()) {
                    return@withContext RecognitionOutcome(
                        false, failureStage = "post_disclosure_revoked", cropWidth = cropW, cropHeight = cropH,
                    )
                }
                remember(current, digest)
                ControlDiagnostics.record("keyboard_role_image", "space_glyph")
                return@withContext RecognitionOutcome(true, method = "space_glyph", cropWidth = cropW, cropHeight = cropH)
            }
        }
        // ML Kit MobileVisionBase enforces width >= 32 && height >= 32 before inference.
        if (cropW < 32 || cropH < 32) {
            bitmap.recycle()
            ControlDiagnostics.record("keyboard_role_image", "crop_below_mlkit_min_32px")
            return@withContext RecognitionOutcome(
                false,
                failureStage = if (usesSpaceGlyphPolicy) "space_glyph_mismatch_and_crop_below_mlkit_min_32px" else "crop_below_mlkit_min_32px",
                cropWidth = cropW, cropHeight = cropH,
            )
        }
        val client = try { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
            catch (_: RuntimeException) {
                bitmap.recycle()
                return@withContext RecognitionOutcome(
                    false, failureStage = "ocr_client_error", cropWidth = cropW, cropHeight = cropH, ocrAttempted = true,
                )
            }
        // ML Kit owns the bitmap until completion, including cancellation/timeout.
        val ocrOutcome = withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine<RecognitionOutcome> { continuation ->
                try {
                    client.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                        val outcome = try {
                            if (!task.isSuccessful) {
                                RecognitionOutcome(
                                    false, failureStage = "ocr_task_failed", cropWidth = cropW, cropHeight = cropH,
                                    ocrAttempted = true, ocrConfidenceBucket = "missing",
                                )
                            } else {
                                val line = task.result?.textBlocks?.flatMap { it.lines }?.singleOrNull()
                                if (line == null) {
                                    RecognitionOutcome(
                                        false, failureStage = "ocr_no_single_line", cropWidth = cropW, cropHeight = cropH,
                                        ocrAttempted = true, ocrLabelMatched = false, ocrConfidenceBucket = "missing",
                                    )
                                } else {
                                    val confidence = line.elements.minOfOrNull { it.confidence }
                                    val bucket = PixelCertificationOutcome.confidenceBucket(confidence)
                                    val labelMatched = KeyboardImageFallbackPolicy.matchesRead(expected, line.text, 0.99f)
                                    val fullMatch = confidence != null &&
                                        KeyboardImageFallbackPolicy.matchesRead(expected, line.text, confidence)
                                    val stage = when {
                                        fullMatch -> null
                                        !labelMatched -> "ocr_label_mismatch"
                                        else -> "ocr_below_threshold"
                                    }
                                    RecognitionOutcome(
                                        confirmed = fullMatch,
                                        method = if (fullMatch) "mlkit_ocr" else "none",
                                        failureStage = stage,
                                        cropWidth = cropW, cropHeight = cropH,
                                        ocrAttempted = true,
                                        ocrLabelMatched = labelMatched,
                                        ocrConfidenceBucket = bucket,
                                    )
                                }
                            }
                        } catch (_: RuntimeException) {
                            RecognitionOutcome(
                                false, failureStage = "ocr_task_failed", cropWidth = cropW, cropHeight = cropH,
                                ocrAttempted = true, ocrConfidenceBucket = "missing",
                            )
                        } finally { client.close(); bitmap.recycle() }
                        if (continuation.isActive) continuation.resume(outcome)
                    }
                } catch (_: RuntimeException) {
                    client.close(); bitmap.recycle()
                    if (continuation.isActive) {
                        continuation.resume(
                            RecognitionOutcome(
                                false, failureStage = "ocr_task_failed", cropWidth = cropW, cropHeight = cropH,
                                ocrAttempted = true, ocrConfidenceBucket = "missing",
                            ),
                        )
                    }
                }
            }
        } ?: RecognitionOutcome(
            false, failureStage = "ocr_timeout", cropWidth = cropW, cropHeight = cropH,
            ocrAttempted = true, ocrConfidenceBucket = "missing",
        )
        val finalOutcome = if (ocrOutcome.confirmed && !proof.sourceDisclosureAllowed()) {
            ocrOutcome.copy(confirmed = false, method = "none", failureStage = "post_disclosure_revoked")
        } else ocrOutcome
        if (finalOutcome.confirmed) remember(current, digest)
        ControlDiagnostics.record("keyboard_role_image", if (finalOutcome.confirmed) "computed" else "unverified")
        finalOutcome
    }

    /**
     * Crop-scoped live surface liveness check for structurally verified native keys:
     * verifies decoded image dimensions, owned/disclosed mapping, and non-blank luminance
     * contrast strictly within the cropped key bitmap (never whole-screenshot contrast).
     * Does NOT classify or claim semantic Hangul glyph identity.
     */
    suspend fun verifyKeyCropLiveSurface(
        proof: VisualTargetProof,
        crop: TapBounds,
        nodeBounds: TapBounds,
    ): String? = withContext(Dispatchers.Default) {
        if (!proof.sourceDisclosureAllowed()) return@withContext "proof_disclosure_revoked"
        if (proof.image.bytes.size !in 1..1_500_000) return@withContext "image_bytes_invalid"
        val mapped = proof.map(crop) ?: return@withContext "mapped_crop_not_disclosed"
        if (!nodeBounds.contains(mapped) || !OwnedPixelPolicy.contains(proof.disclosedRegions, mapped)) {
            return@withContext "mapped_crop_outside_owned_node"
        }
        val decoded = BitmapFactory.decodeByteArray(proof.image.bytes, 0, proof.image.bytes.size)
            ?: return@withContext "image_decode_failed"
        var cropBitmap: Bitmap? = null
        try {
            if (decoded.width != proof.image.width || decoded.height != proof.image.height ||
                !TapBounds(0, 0, decoded.width, decoded.height).contains(crop)) {
                return@withContext "crop_outside_decoded_image"
            }
            cropBitmap = Bitmap.createBitmap(
                decoded, crop.left, crop.top, crop.right - crop.left, crop.bottom - crop.top,
            )
            val cw = cropBitmap.width
            val ch = cropBitmap.height
            val pixels = IntArray(cw * ch)
            cropBitmap.getPixels(pixels, 0, cw, 0, 0, cw, ch)
            for (i in pixels.indices) {
                val p = pixels[i]
                pixels[i] = (77 * (p shr 16 and 255) + 150 * (p shr 8 and 255) + 29 * (p and 255)) shr 8
            }
            if (!NativeKeyboardStructuralCertification.hasLiveSurfaceContrast(pixels)) {
                return@withContext "crop_surface_uniform_or_blank"
            }
            if (!proof.sourceDisclosureAllowed()) return@withContext "post_disclosure_revoked"
            null
        } catch (_: RuntimeException) {
            "crop_surface_check_failed"
        } finally {
            cropBitmap?.recycle()
            decoded.recycle()
        }
    }
}
