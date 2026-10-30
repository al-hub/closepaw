package ai.closepaw.chatgpt.control

import ai.closepaw.platform.virtualdisplay.ProjectionImageLease
import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import android.accessibilityservice.AccessibilityService
import android.hardware.display.DisplayManager
import android.view.Display
import android.graphics.Point
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/** Explicit window OR display capture. A failed window call never falls back.
 * Display pixels outside certified application/IME regions are blacked out before
 * hashing/compression; no raw bitmap or clipboard/screen text is logged. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal data class IsolatedVisualSurface(val windowId: Int, val bounds: TapBounds, val visible: List<TapBounds>)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal object WindowVisualCapture {
    suspend fun capture(service: AccessibilityService, windowId: Int, window: TapBounds, context: String,
        display: Boolean = false, visibleRegion: Region? = null,
        surfaces: List<IsolatedVisualSurface> = emptyList(), projection: ProjectionImageLease? = null,
        requestId: String? = null, awaitSlot: suspend () -> Unit = {}): VisualCaptureResult {
        fun failure(code: String) = VisualCaptureResult(reason=code)
        if (Build.VERSION.SDK_INT < 34 || !window.valid || (display && visibleRegion == null)) return failure("screenshot_geometry_unverified")
        var nativeReason = "screenshot_timeout"
        // Projection is an explicit display-only source, never an ownership proof.
        if (projection != null && !display) return failure("projection_display_scope_required")
        val projected = if (projection != null) projection.capture(requestId)
            ?: return failure("projection_frame_unavailable") else null
        val captured = if (projected != null) null else withTimeoutOrNull(5_000) { withContext(Dispatchers.Main) {
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
        } }
        if (projected == null && captured == null) return failure(nativeReason)
        return try { withContext(Dispatchers.Default) {
            var bitmap: Bitmap? = null; var scaled: Bitmap? = null
            var disclosed: List<TapBounds> = emptyList()
            try {
                if (projected != null) {
                    bitmap = projected.bitmap
                } else {
                    val buffer = captured!!.hardwareBuffer
                    if (buffer.width.toLong()*buffer.height > 8_000_000) return@withContext failure("screenshot_pixel_limit")
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, captured.colorSpace) ?: return@withContext failure("screenshot_buffer_wrap_failed")
                    bitmap = try { hardware.copy(Bitmap.Config.ARGB_8888, display) } finally { hardware.recycle() }
                }
                val source = bitmap ?: return@withContext failure("screenshot_bitmap_copy_failed")
                if (source.width != window.right-window.left || source.height != window.bottom-window.top) {
                    recordGeometryMismatch(service, if (display) "display" else "window", source.width, source.height, window)
                    return@withContext failure("screenshot_buffer_geometry_mismatch")
                }
                if (source.width.toLong()*source.height > 8_000_000) return@withContext failure("screenshot_pixel_limit")
                if (display) {
                    if(surfaces.isEmpty()) return@withContext failure("display_isolated_sources_required")
                    val actual=IntArray(source.width*source.height)
                    source.getPixels(actual,0,source.width,0,0,source.width,source.height)
                    val verified=Region()
                    for(surface in surfaces) {
                        awaitSlot()
                        // Window-isolated verification never uses a display fallback.
                        val isolated=capturePixels(service,surface.windowId,surface.bounds)
                        val expected=isolated.pixels ?: return@withContext failure("display_isolated_"+isolated.reason)
                        val runs=OwnedPixelPolicy.verifiedRuns(actual,source.width,expected,surface.bounds,surface.visible)
                            ?: return@withContext failure("display_pixel_verification_fragmented")
                        runs.forEach { verified.op(Rect(it.left,it.top,it.right,it.bottom),Region.Op.UNION) }
                    }
                    if(verified.isEmpty) return@withContext failure("display_owned_pixels_unavailable")
                    val parts=mutableListOf<TapBounds>();val partsIterator=RegionIterator(verified);val part=Rect()
                    while(partsIterator.next(part)) {
                        if(parts.size>=4096) return@withContext failure("display_pixel_verification_fragmented")
                        parts.add(TapBounds(part.left,part.top,part.right,part.bottom))
                    }
                    disclosed=parts
                    val hidden = Region(0,0,source.width,source.height).apply { op(verified,Region.Op.DIFFERENCE) }
                    val iterator=RegionIterator(hidden); val rect=Rect(); val canvas=Canvas(source); val paint=Paint().apply { color=Color.BLACK }
                    while(iterator.next(rect)) canvas.drawRect(rect,paint)
                }
                val pixels = ByteBuffer.allocate(source.byteCount); source.copyPixelsToBuffer(pixels)
                val hash = MessageDigest.getInstance("SHA-256").digest(pixels.array()).joinToString("") { "%02x".format(it) }
                val ratio = minOf(1.0, 960.0 / maxOf(source.width, source.height))
                scaled = Bitmap.createScaledBitmap(source, maxOf(1,(source.width*ratio).toInt()), maxOf(1,(source.height*ratio).toInt()), true)
                val image = scaled!!; val out = ByteArrayOutputStream()
                if (!image.compress(Bitmap.CompressFormat.JPEG,80,out)) return@withContext failure("screenshot_compression_failed")
                val bytes = out.toByteArray()
                if (bytes.size > 1_500_000) return@withContext failure("screenshot_byte_limit")
                val sourceAllowed: () -> Boolean = if (projected == null) ({ true }) else {
                    val lease = projected.lease; val at = projected.capturedAt
                    val check: () -> Boolean = { lease.permitsDelivery(at) }
                    check
                }
                if (!sourceAllowed()) return@withContext failure("projection_session_expired")
                VisualCaptureResult(VisualTargetProof(ScreenImage(image.width,image.height,"image/jpeg",bytes,
                    if (projected == null) ScreenImageSource.ACCESSIBILITY_SCREENSHOT else ScreenImageSource.MEDIA_PROJECTION),
                    hash,window,context,System.currentTimeMillis(),disclosedRegions=disclosed,sourceDisclosureAllowed=sourceAllowed))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure("screenshot_conversion_failed") }
            finally { if (scaled !== bitmap) scaled?.recycle(); bitmap?.recycle() }
        } } finally { captured?.hardwareBuffer?.close(); projected?.bitmap?.takeUnless { it.isRecycled }?.recycle() }
    }
    private fun recordGeometryMismatch(
        service: AccessibilityService, scope: String, bufferWidth: Int, bufferHeight: Int, bounds: TapBounds
    ) {
        // Numeric geometry only. Never log screenshot data, package names or window text.
        val displayInfo = runCatching {
            val display = service.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            val size = Point()
            display?.getRealSize(size)
            Triple(size.x, size.y, display?.rotation)
        }.getOrNull()
        ControlDiagnostics.record("visual_geometry", "screenshot_buffer_geometry_mismatch", metrics = buildJsonObject {
            put("scope", scope)
            put("buffer_width", bufferWidth); put("buffer_height", bufferHeight)
            put("bounds_width", bounds.right - bounds.left); put("bounds_height", bounds.bottom - bounds.top)
            put("bounds_left", bounds.left); put("bounds_top", bounds.top)
            put("bounds_right", bounds.right); put("bounds_bottom", bounds.bottom)
            displayInfo?.let { (width,height,rotation) ->
                if (width > 0 && height > 0) { put("display_width", width); put("display_height", height) }
                if (rotation != null) put("display_rotation", rotation)
            }
        })
    }
    private data class PixelResult(val pixels: IntArray? = null, val reason: String? = null)
    private suspend fun capturePixels(service: AccessibilityService,id: Int,bounds: TapBounds): PixelResult {
        if(Build.VERSION.SDK_INT<34 || !bounds.valid) return PixelResult(reason="screenshot_geometry_unverified")
        var failure="screenshot_timeout"
        val captured=withTimeoutOrNull(5_000) { withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<AccessibilityService.ScreenshotResult?> { continuation ->
                try { service.takeScreenshotOfWindow(id,service.mainExecutor,object:AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(value: AccessibilityService.ScreenshotResult) {
                        if(continuation.isActive) continuation.resume(value,onCancellation={ _:Throwable->value.hardwareBuffer.close() }) else value.hardwareBuffer.close()
                    }
                    override fun onFailure(code:Int) { failure=VisualCaptureFailure.native(code);if(continuation.isActive) continuation.resume(null) }
                }) } catch(e:CancellationException) { throw e }
                catch(_:SecurityException) { failure="screenshot_permission_denied";if(continuation.isActive) continuation.resume(null) }
                catch(_:Exception) { failure="screenshot_dispatch_failed";if(continuation.isActive) continuation.resume(null) }
            }
        } } ?: return PixelResult(reason=failure)
        try {
            val buffer=captured.hardwareBuffer
            val width=bounds.right-bounds.left;val height=bounds.bottom-bounds.top
            if(buffer.width!=width || buffer.height!=height) {
                recordGeometryMismatch(service, "isolated_window", buffer.width, buffer.height, bounds)
                return PixelResult(reason="screenshot_buffer_geometry_mismatch")
            }
            if(width.toLong()*height>8_000_000) return PixelResult(reason="screenshot_pixel_limit")
            val hardware=Bitmap.wrapHardwareBuffer(buffer,captured.colorSpace) ?: return PixelResult(reason="screenshot_buffer_wrap_failed")
            val software=try { hardware.copy(Bitmap.Config.ARGB_8888,false) } finally {hardware.recycle()}
                ?: return PixelResult(reason="screenshot_bitmap_copy_failed")
            try {
                val pixels=IntArray(width*height);software.getPixels(pixels,0,width,0,0,width,height)
                return PixelResult(pixels)
            } finally {software.recycle()}
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {return PixelResult(reason="screenshot_conversion_failed")}
        finally {captured.hardwareBuffer.close()}
    }

}
