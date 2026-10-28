package ai.closepaw.platform.virtualdisplay

import android.graphics.Bitmap
import android.os.SystemClock
import ai.closepaw.chatgpt.control.ControlDiagnostics
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** No Context or projection token is retained here. Only the service can publish a lease.
 * Raw frames stay invocation-local and must pass the accessibility ownership/masking path. */
internal object ProjectionImageTransport {
    @Volatile private var current: ProjectionImageLease? = null
    fun active(): ProjectionImageLease? = current?.takeIf { it.isActive() }
    @Synchronized fun publish(lease: ProjectionImageLease) { current = lease }
    fun remove(lease: ProjectionImageLease?) {
        synchronized(this) { if (current === lease) current = null }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ProjectionImageLease(
    private val scope: MediaProjectionFrameScope,
    private val policy: MediaProjectionFramePolicy,
    private val consent: MediaProjectionConsentGate,
) {
    private val generation = consent.generation
    fun isActive(): Boolean = consent.generation == generation && consent.state == ProjectionConsentState.Consumed &&
        policy.acquireAllowed() && scope.isOpen()

    fun permitsDelivery(capturedAt: Long): Boolean = isActive() &&
        policy.isDeliveryFresh(capturedAt, SystemClock.elapsedRealtime())

    /** Bounded acquisition, no cached pixels and no fallback. The caller owns the bitmap.
     * Timestamp timebase depends on the image source; retain the existing fail-closed freshness check. */
    suspend fun capture(): ProjectionBitmap? {
        data class Attempt(val reason: String, val frame: ProjectionBitmap? = null)
        val lock = Any()
        var stopped = false
        var lastObservation = "none"
        var emptyReads = 0
        var staleReads = 0
        var pendingFrame: ProjectionBitmap? = null
        fun releasePendingFrame() {
            val frame = synchronized(lock) { pendingFrame.also { pendingFrame = null } }
            frame?.bitmap?.recycle()
        }
        var attempt: Attempt? = null
        try {
        attempt = withTimeoutOrNull(1_500) {
            suspendCancellableCoroutine<Attempt> { continuation ->
                fun finish(reason: String, frame: ProjectionBitmap? = null) {
                    val accepted = synchronized(lock) {
                        if (stopped) false else {
                            stopped = true
                            pendingFrame = frame
                            true
                        }
                    }
                    if (!accepted) {
                        frame?.bitmap?.recycle()
                        return
                    }
                    continuation.resume(Attempt(reason, frame), onCancellation = { _: Throwable ->
                        releasePendingFrame()
                    })
                }
                val acquire = object : Runnable {
                    fun retry(observation: String) {
                        val posted = synchronized(lock) {
                            if (stopped || !continuation.isActive) return
                            lastObservation = observation
                            if (observation == "no_frame_available") emptyReads++
                            if (observation == "stale_frame") staleReads++
                            scope.handler.postDelayed(this, 25)
                        }
                        if (!posted) finish("handler_post_rejected")
                    }
                    override fun run() {
                        var bitmap: Bitmap? = null
                        try {
                            if (!continuation.isActive) return
                            if (!isActive()) {
                                finish("lease_not_active")
                                return
                            }
                            val image = scope.reader.acquireLatestImage()
                            if (image == null) {
                                retry("no_frame_available")
                                return
                            }
                            var capturedAt = 0L
                            try {
                                val timestamp = image.timestamp
                                capturedAt = timestamp / 1_000_000
                                if (timestamp > 0 && capturedAt <= SystemClock.elapsedRealtime() &&
                                    !policy.isFrameFresh(capturedAt, SystemClock.elapsedRealtime())) {
                                    retry("stale_frame")
                                    return
                                }
                                val reason = when {
                                    timestamp <= 0 -> "invalid_timestamp"
                                    !policy.isFrameFresh(capturedAt, SystemClock.elapsedRealtime()) -> "timestamp_not_fresh"
                                    !policy.acceptsMirrorGeometry(image.width, image.height) -> "geometry_refused"
                                    image.planes.size != 1 -> "plane_count_refused"
                                    else -> null
                                }
                                if (reason != null) {
                                    finish(reason)
                                    return
                                }
                                val plane = image.planes[0]
                                val width = image.width; val height = image.height
                                val packed = ProjectionFramePacking.pack(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
                                if (packed == null) {
                                    finish("packing_failed")
                                    return
                                }
                                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap!!.copyPixelsFromBuffer(packed)
                            } finally { image.close() }
                            val reason = when {
                                !isActive() -> "lease_not_active"
                                !policy.isFrameFresh(capturedAt, SystemClock.elapsedRealtime()) -> "post_acquire_not_fresh"
                                else -> null
                            }
                            if (reason != null) {
                                bitmap?.recycle(); bitmap = null
                                finish(reason)
                            } else {
                                val frame = ProjectionBitmap(bitmap!!, this@ProjectionImageLease, capturedAt)
                                bitmap = null
                                finish("captured", frame)
                            }
                        } catch (_: Exception) {
                            bitmap?.recycle()
                            finish("acquire_exception")
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    synchronized(lock) {
                        stopped = true
                        scope.handler.removeCallbacks(acquire)
                    }
                }
                val posted = synchronized(lock) {
                    if (stopped || !continuation.isActive) true else scope.handler.post(acquire)
                }
                if (!posted) finish("handler_post_rejected")
            }
        }
        } finally {
            if (attempt?.frame == null) releasePendingFrame()
        }
        val metrics = synchronized(lock) {
            buildJsonObject {
                put("last_observation", lastObservation)
                put("null_reads", emptyReads.coerceAtMost(1000))
                put("stale_reads", staleReads.coerceAtMost(1000))
                put("lease_active_after", isActive())
            }
        }
        ControlDiagnostics.record("projection_frame_acquire", attempt?.reason ?: "timeout", metrics = metrics)
        return attempt?.frame
    }
}

internal data class ProjectionBitmap(val bitmap: Bitmap, val lease: ProjectionImageLease, val capturedAt: Long)
