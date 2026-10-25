package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** Window-only screenshot: never falls back to a display capture after denial.
 * Raw dimensions must equal OS window bounds; ambiguous insets/rotation refuse. */
internal object WindowVisualCapture {
    suspend fun capture(service: AccessibilityService, windowId: Int, window: TapBounds, context: String): VisualTargetProof? {
        if (Build.VERSION.SDK_INT < 34 || !window.valid) return null
        val captured = withTimeoutOrNull(5_000) { withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<AccessibilityService.ScreenshotResult?> { continuation ->
                try { service.takeScreenshotOfWindow(windowId, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        if (continuation.isActive) continuation.resume(screenshot) else screenshot.hardwareBuffer.close()
                    }
                    override fun onFailure(errorCode: Int) { if (continuation.isActive) continuation.resume(null) }
                }) } catch (_: Exception) { if (continuation.isActive) continuation.resume(null) }
            }
        } } ?: return null
        return try { withContext(Dispatchers.Default) {
            var bitmap: Bitmap? = null; var scaled: Bitmap? = null
            try {
                val buffer = captured.hardwareBuffer
                if (buffer.width != window.right-window.left || buffer.height != window.bottom-window.top || buffer.width.toLong()*buffer.height > 8_000_000) return@withContext null
                val hardware = Bitmap.wrapHardwareBuffer(buffer, captured.colorSpace) ?: return@withContext null
                bitmap = try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                val source = bitmap ?: return@withContext null
                val pixels = ByteBuffer.allocate(source.byteCount); source.copyPixelsToBuffer(pixels)
                val hash = MessageDigest.getInstance("SHA-256").digest(pixels.array()).joinToString("") { "%02x".format(it) }
                val ratio = minOf(1.0, 960.0 / maxOf(source.width, source.height))
                scaled = Bitmap.createScaledBitmap(source, maxOf(1,(source.width*ratio).toInt()), maxOf(1,(source.height*ratio).toInt()), true)
                val image = scaled!!; val out = ByteArrayOutputStream()
                if (!image.compress(Bitmap.CompressFormat.JPEG, 80, out)) return@withContext null
                VisualTargetProof(ScreenImage(image.width,image.height,"image/jpeg",out.toByteArray(), ScreenImageSource.ACCESSIBILITY_SCREENSHOT), hash, window, context, System.currentTimeMillis())
            } finally {
                if (scaled !== bitmap) scaled?.recycle()
                bitmap?.recycle()
            }
        } } finally { captured.hardwareBuffer.close() }
    }
}
