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
     * ImageReader timestamps use the monotonic timebase; unknown/future/old values fail closed. */
    suspend fun capture(): ProjectionBitmap? {
        var stage = "deadline_before_frame"
        var emptyReads = 0
        var staleReads = 0
        val result = withTimeoutOrNull(1_500) {
        suspendCancellableCoroutine { continuation ->
            val acquire = object : Runnable {
                override fun run() {
                    var bitmap: Bitmap? = null
                    try {
                        if (!continuation.isActive || !isActive()) {
                            stage = "lease_not_active"
                            if (continuation.isActive) continuation.resume(null)
                            return
                        }
                        val image = scope.reader.acquireLatestImage()
                        if (image == null) {
                            stage = "no_frame_available"
                            emptyReads++
                            scope.handler.postDelayed(this, 25)
                            return
                        }
                        val capturedAt = image.timestamp / 1_000_000
                        try {
                            if (image.timestamp > 0 && capturedAt <= SystemClock.elapsedRealtime() &&
                                !policy.isFrameFresh(capturedAt, SystemClock.elapsedRealtime())) {
                                // Drain stalled pre-request buffers, then wait within the same deadline.
                                stage = "stale_frame"
                                staleReads++
                                scope.handler.postDelayed(this, 25)
                                return
                            }
                            if (image.timestamp <= 0 || !policy.isFrameFresh(capturedAt, SystemClock.elapsedRealtime()) ||
                                !policy.acceptsMirrorGeometry(image.width, image.height) || image.planes.size != 1) {
                                stage = when {
                                    image.timestamp <= 0 -> "invalid_timestamp"
                                    !policy.isFrameFresh(capturedAt, SystemClock.elapsedRealtime()) -> "timestamp_not_fresh"
                                    !policy.acceptsMirrorGeometry(image.width, image.height) -> "geometry_refused"
                                    else -> "plane_count_refused"
                                }
                                continuation.resume(null)
                                return
                            }
                            val plane = image.planes[0]
                            val width = image.width; val height = image.height
                            val packed = ProjectionFramePacking.pack(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
                            if (packed == null) {
                                stage = "packing_failed"
                                continuation.resume(null)
                                return
                            }
                            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap!!.copyPixelsFromBuffer(packed)
                        } finally { image.close() }
                        if (!isActive() || !policy.isFrameFresh(capturedAt, SystemClock.elapsedRealtime())) {
                            stage = "post_acquire_lease_or_freshness"
                            bitmap?.recycle(); bitmap = null
                            continuation.resume(null)
                        } else {
                            stage = "captured"
                            val result = ProjectionBitmap(bitmap!!, this@ProjectionImageLease, capturedAt)
                            bitmap = null
                            continuation.resume(result, onCancellation = { _: Throwable -> result.bitmap.recycle() })
                        }
                    } catch (_: Exception) {
                        stage = "acquire_exception"
                        bitmap?.recycle()
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
            continuation.invokeOnCancellation { scope.handler.removeCallbacks(acquire) }
            val posted = scope.handler.post(acquire)
            if (!posted && continuation.isActive) {
                stage = "handler_post_rejected"
                continuation.resume(null)
            }
        }
        }
        ControlDiagnostics.record("projection_frame_acquire", if (result != null) "captured" else stage, metrics = buildJsonObject {
            put("null_reads", emptyReads.coerceAtMost(1000))
            put("stale_reads", staleReads.coerceAtMost(1000))
            put("lease_active_after", isActive())
        })
        return result
    }
}

internal data class ProjectionBitmap(val bitmap: Bitmap, val lease: ProjectionImageLease, val capturedAt: Long)
