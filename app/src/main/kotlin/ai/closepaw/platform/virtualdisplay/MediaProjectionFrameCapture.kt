package ai.closepaw.platform.virtualdisplay

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Geometry-only description of the newest mirrored frame. No pixels are carried or logged. */
data class MirroredFrameGeometry(
    val width: Int?,
    val height: Int?,
    val capturedAtMs: Long?,
    val reason: String,
    /** Always false: a whole-display mirror can never prove certified-region ownership. */
    val ownershipVerifiable: Boolean = false,
)

/**
 * Owns the bounded [ImageReader] / [VirtualDisplay] pair for one OS consent round.
 * Every acquired buffer is closed by its consumer. Only ProjectionImageLease may copy
 * fresh pixels for guarded display verification. The scope is torn down on revocation and cannot be
 * reopened after [MediaProjectionFrameScope.close] — a scope is strictly one-session.
 */
internal class MediaProjectionFrameScope internal constructor(
    internal val reader: ImageReader,
    display: VirtualDisplay,
    thread: HandlerThread,
) {
    private val closed = AtomicBoolean(false)
    private val surfaceLock = Any()
    private var request: ProjectionRequestQueue? = null

    /** A fresh BufferQueue, never a drain/relabel of the session diagnostic reader.
     * SF renders into this new sink after its creation. The elapsed lower bound
     * predates creation, so even an unknown Image.timestamp cannot extend its age.
     * One request owns the sink until its identity-checked cleanup completes.
     */
    fun beginRequest(): ProjectionRequestQueue? = synchronized(surfaceLock) {
        if (!isOpen() || request != null) return null
        val lowerBound = SystemClock.elapsedRealtime()
        var fresh: ImageReader? = null
        try {
            fresh = ImageReader.newInstance(reader.width, reader.height, PixelFormat.RGBA_8888, 2)
            displayRef.surface = fresh.surface
            ProjectionRequestQueue(fresh, lowerBound).also { request = it }
        } catch (_: Exception) {
            // setSurface may have taken effect before throwing. Never resume an
            // uncertain sink; permanently close this scope on partial attachment.
            runCatching { fresh?.close() }
            close()
            null
        }
    }

    fun endRequest(expected: ProjectionRequestQueue) = synchronized(surfaceLock) {
        if (request !== expected) return@synchronized
        request = null
        // Leave the display detached between pixel requests. The original reader
        // remains geometry-only; it is never a fallback source of screenshot pixels.
        try { if (!closed.get()) displayRef.surface = null }
        catch (_: Exception) { close() }
        finally { runCatching { expected.reader.close() } }
    }
    private val displayRef = display
    private val threadRef = thread
    internal val handler = Handler(thread.looper)

    fun isOpen(): Boolean = !closed.get() && threadRef.isAlive

    fun close() = synchronized(surfaceLock) {
        if (!closed.compareAndSet(false, true)) return@synchronized
        runCatching { displayRef.release() }
        request?.let { runCatching { it.reader.close() } }
        request = null
        runCatching { reader.close() }
        runCatching { threadRef.quitSafely() }
        Unit
    }
}

/** Request identity is issued only by a new sink attachment, never by frame receipt. */
internal class ProjectionRequestQueue(val reader: ImageReader, val lowerBoundElapsedMs: Long)

/**
 * Surface creation and geometry diagnostics for issue #165. ProjectionImageLease is
 * the bounded pixel consumer; ownership and region masking remain exclusively in
 * WindowVisualCapture. A raw mirror is never sufficient for image disclosure.
 */
internal object MediaProjectionFrameCapture {
    private const val TAG = "MediaProjectionCapture"
    private const val MAX_IMAGES = 2

    /**
     * Attach the bounded mirror surface. Returns null and leaves the policy fully
     * invalidated on any failure; nothing is half-open.
     */
    fun open(
        policy: MediaProjectionFramePolicy,
        projection: MediaProjection,
        width: Int,
        height: Int,
        dpi: Int,
    ): MediaProjectionFrameScope? {
        if (!policy.openSurface() ||
            !policy.acceptsMirrorGeometry(width, height)
        ) {
            policy.invalidate()
            return null
        }
        var thread: HandlerThread? = null
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        try {
            thread = HandlerThread("ClosePawMirrorFrame").apply { start() }
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
            display = projection.createVirtualDisplay(
                "ClosePaw#165-mirror",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(thread.looper)
            )
            if (display == null) throw IllegalStateException("virtual display refused")
            return MediaProjectionFrameScope(reader, display, thread)
        } catch (e: Exception) {
            Log.w(TAG, "mirror scope open failed; staying closed", e)
            policy.invalidate()
            runCatching { display?.release() }
            runCatching { reader?.close() }
            runCatching { thread?.quitSafely() }
            return null
        }
    }

    /**
     * Read geometry of the newest mirrored frame and close that buffer immediately.
     * Content ownership for this surface is inherently unverifiable (the mirror shows
     * everything on display, including secure/unknown overlays), so the result is only
     * ever used as diagnostic geometry, never as an MCP image.
     */
    fun latestDiagnosticGeometry(
        scope: MediaProjectionFrameScope,
        policy: MediaProjectionFramePolicy,
        timeoutMs: Long = 2_000,
    ): MirroredFrameGeometry {
        val closed = MirroredFrameGeometry(null, null, null, "mirror_scope_closed")
        if (!policy.acquireAllowed() || !scope.isOpen()) return closed
        val latch = CountDownLatch(1)
        var result = MirroredFrameGeometry(null, null, null, "mirror_frame_unavailable")
        val posted = scope.handler.post {
            try {
                if (!policy.acquireAllowed() || !scope.isOpen()) {
                    result = closed
                } else {
                    val frame = scope.reader.acquireLatestImage()
                    if (frame != null) {
                        try {
                            val valid = policy.acceptsMirrorGeometry(frame.width, frame.height)
                            val stillOpen = policy.acquireAllowed() && scope.isOpen()
                            result = if (!stillOpen) closed else MirroredFrameGeometry(
                                frame.width, frame.height, SystemClock.elapsedRealtime(),
                                if (valid) "mirror_geometry_verified" else "mirror_geometry_refused"
                            )
                        } finally {
                            frame.close()
                        }
                    }
                }
            } catch (e: Exception) {
                result = MirroredFrameGeometry(null, null, null, "mirror_acquire_failed")
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return closed
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            // The queued callback still closes any acquired image after this timeout.
            return MirroredFrameGeometry(null, null, null, "mirror_geometry_timeout")
        }
        if (!policy.acquireAllowed() || !scope.isOpen()) return closed
        return result
    }

}
