package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.Rect
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** Explicit window OR display capture. A failed window call never falls back.
 * Display pixels outside certified application/IME regions are blacked out before
 * hashing/compression; no raw bitmap or clipboard/screen text is logged. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal object WindowVisualCapture {
    suspend fun capture(service: AccessibilityService, windowId: Int, window: TapBounds, context: String,
        display: Boolean = false, visibleRegion: Region? = null): VisualCaptureResult {
        fun failure(code: String) = VisualCaptureResult(reason=code)
        if (Build.VERSION.SDK_INT < 34 || !window.valid || (display && visibleRegion == null)) return failure("screenshot_geometry_unverified")
        var nativeReason = "screenshot_timeout"
        val captured = withTimeoutOrNull(5_000) { withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<AccessibilityService.ScreenshotResult?> { continuation ->
                val callback = object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        if (continuation.isActive) continuation.resume(screenshot, onCancellation = { _: Throwable -> screenshot.hardwareBuffer.close() }) else screenshot.hardwareBuffer.close()
                    }
                    override fun onFailure(errorCode: Int) {
                        nativeReason = VisualCaptureFailure.native(errorCode)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                try {
                    if (display) service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY,service.mainExecutor,callback)
                    else service.takeScreenshotOfWindow(windowId,service.mainExecutor,callback)
                } catch (e: CancellationException) { throw e }
                catch (_: SecurityException) { nativeReason="screenshot_permission_denied"; if (continuation.isActive) continuation.resume(null) }
                catch (_: Exception) { nativeReason="screenshot_dispatch_failed"; if (continuation.isActive) continuation.resume(null) }
            }
        } } ?: return failure(nativeReason)
        return try { withContext(Dispatchers.Default) {
            var bitmap: Bitmap? = null; var scaled: Bitmap? = null
            try {
                val buffer = captured.hardwareBuffer
                if (buffer.width != window.right-window.left || buffer.height != window.bottom-window.top) return@withContext failure("screenshot_buffer_geometry_mismatch")
                if (buffer.width.toLong()*buffer.height > 8_000_000) return@withContext failure("screenshot_pixel_limit")
                val hardware = Bitmap.wrapHardwareBuffer(buffer, captured.colorSpace) ?: return@withContext failure("screenshot_buffer_wrap_failed")
                bitmap = try { hardware.copy(Bitmap.Config.ARGB_8888, display) } finally { hardware.recycle() }
                val source = bitmap ?: return@withContext failure("screenshot_bitmap_copy_failed")
                if (display) {
                    val hidden = Region(0,0,source.width,source.height).apply { op(visibleRegion!!,Region.Op.DIFFERENCE) }
                    val iterator=RegionIterator(hidden); val rect=Rect(); val canvas=Canvas(source); val paint=Paint().apply { color=Color.BLACK }
                    while(iterator.next(rect)) canvas.drawRect(rect,paint)
                }
                val pixels = ByteBuffer.allocate(source.byteCount); source.copyPixelsToBuffer(pixels)
                val hash = MessageDigest.getInstance("SHA-256").digest(pixels.array()).joinToString("") { "%02x".format(it) }
                val ratio = minOf(1.0, 960.0 / maxOf(source.width, source.height))
                scaled = Bitmap.createScaledBitmap(source, maxOf(1,(source.width*ratio).toInt()), maxOf(1,(source.height*ratio).toInt()), true)
                val image = scaled!!; val out = ByteArrayOutputStream()
                if (!image.compress(Bitmap.CompressFormat.JPEG,80,out)) return@withContext failure("screenshot_compression_failed")
                VisualCaptureResult(VisualTargetProof(ScreenImage(image.width,image.height,"image/jpeg",out.toByteArray(),ScreenImageSource.ACCESSIBILITY_SCREENSHOT),hash,window,context,System.currentTimeMillis()))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure("screenshot_conversion_failed") }
            finally { if (scaled !== bitmap) scaled?.recycle(); bitmap?.recycle() }
        } } finally { captured.hardwareBuffer.close() }
    }
}
