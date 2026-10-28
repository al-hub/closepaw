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

/**
 * Lifecycle/race/freshness policy for the diagnostic-only MediaProjection mirror scope
 * of issue #165. Pure Kotlin (no Android types), so the ordering guarantees can be
 * proven by JVM unit tests.
 *
 * Rules:
 * - Fail closed by construction: a surface can be opened once per session, and
 *   [invalidate] (revocation/teardown) permanently arms it; after that no path —
 *   including a racing concurrent [openSurface] — can make acquisition legal again.
 * - Only geometry (width/height/timestamp) is observable. Pixel bytes are never
 *   copied, hashed, logged or returned by any API in this module.
 */
class MediaProjectionFramePolicy(
    private val maxFrameAgeMs: Long = 1_000L,
    private val maxMirrorPixels: Long = 8_000_000L,
) {
    private val lock = Any()
    private var opened = false
    private var invalid = false

    /** Open the single bounded capture surface; false if closed,already open or invalidated. */
    fun openSurface(): Boolean = synchronized(lock) {
        if (invalid || opened) false else { opened = true; true }
    }

    /** Recheck immediately before every acquire; revocation wins the race. */
    fun acquireAllowed(): Boolean = synchronized(lock) { opened && !invalid }

    /**
     * Mark the session revoked/ending (projection onStop, service teardown). Never
     * resurrected by [closeSurface] or a later [openSurface]; the only route back is a
     * fresh OS consent round producing a new policy.
     */
    fun invalidate(): Boolean = synchronized(lock) {
        val firstTime = !invalid
        invalid = true
        opened = false
        firstTime
    }

    /** Release surface state after teardown. Idempotent; cannot undo [invalidate]. */
    fun closeSurface(): Boolean = synchronized(lock) {
        val openWas = opened
        opened = false
        !invalid && openWas
    }

    /** Mirrored frames above this pixel budget are refused (same cap as window screenshots). */
    fun acceptsMirrorGeometry(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height <= maxMirrorPixels

    /** A frame older than the bound, or with an unknown clock value, is never fresh. */
    fun isFrameFresh(capturedAtMs: Long, nowMs: Long): Boolean =
        capturedAtMs >= 0 && nowMs >= capturedAtMs && nowMs - capturedAtMs <= maxFrameAgeMs
}

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
 * Buffers acquired are closed immediately after geometry is read; no bitmap, hash or
 * byte array ever escapes. Entire scope is torn down on revocation and can never be
 * reopened after [MediaProjectionFrameScope.close] — a scope is strictly one-session.
 */
internal class MediaProjectionFrameScope internal constructor(
    internal val reader: ImageReader,
    display: VirtualDisplay,
    thread: HandlerThread,
) {
    private val closed = AtomicBoolean(false)
    private val displayRef = display
    private val threadRef = thread
    internal val handler = Handler(thread.looper)

    fun isOpen(): Boolean = !closed.get() && threadRef.isAlive

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { displayRef.release() }
        runCatching { reader.close() }
        runCatching { threadRef.quitSafely() }
    }
}

/**
 * Static entry points for the diagnostic-only pixel channel of issue #165. Ownership
 * and region masking are exclusively the accessibility screenshot path's job
 * (WindowVisualCapture); the mirror scope exists only so capture geometry can be
 * verified against the display, never as an image source.
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
                "ClosePaw#165-diagnostic-mirror",
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
