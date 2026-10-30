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
    private companion object { val attemptSequence = java.util.concurrent.atomic.AtomicLong() }
    private val generation = consent.generation
    private val capturing = java.util.concurrent.atomic.AtomicBoolean(false)
    fun isActive(): Boolean = consent.generation == generation && consent.state == ProjectionConsentState.Consumed &&
        policy.acquireAllowed() && scope.isOpen()

    fun permitsDelivery(capturedAt: Long): Boolean = isActive() &&
        policy.isDeliveryFresh(capturedAt, SystemClock.elapsedRealtime())

    /** Bounded acquisition, no cached pixels and no fallback. The caller owns the bitmap.
     * capturedAt is a conservative elapsed lower bound BEFORE creating this
     * request's new BufferQueue, never Image.timestamp or an image receipt time. */
    suspend fun capture(requestId: String? = null): ProjectionBitmap? {
        if (!capturing.compareAndSet(false, true)) return null
        try { return captureExclusive(requestId) } finally { capturing.set(false) }
    }

    private suspend fun captureExclusive(requestId: String?): ProjectionBitmap? {
        val attemptId = attemptSequence.incrementAndGet()
        val tuning = ProjectionAcquisitionTuning.snapshot()
        data class Attempt(val reason: String, val frame: ProjectionBitmap? = null)
        val lock = Any()
        var stopped = false
        var lastObservation = "none"
        var emptyReads = 0
        var staleReads = 0
        var uptimeFreshElapsedStale = 0
        // Keep bounded categories/counts and the two clock samples below; never log pixels.
        var previousTimestamp: Long? = null
        var sameTimestamps = 0
        var increasingTimestamps = 0
        var decreasingTimestamps = 0
        var firstAgeBucket = "none"
        var lastAgeBucket = "none"
        var clockGapBucket = "none"
        var lastUptimeAgeBucket = "none"
        // Two metadata samples per attempt, never pixels or wall-clock/user data.
        // Categories alone cannot establish drift/offset against a screen change.
        var firstClockSample: kotlinx.serialization.json.JsonObject? = null
        var lastClockSample: kotlinx.serialization.json.JsonObject? = null
        fun bucket(ms: Long): String = when {
            ms < 0 -> "future"
            ms <= 1_000 -> "0_to_1s"
            ms <= 10_000 -> "1_to_10s"
            ms <= 60_000 -> "10_to_60s"
            else -> "over_60s"
        }
        var requestQueue: ProjectionRequestQueue? = null
        var pendingFrame: ProjectionBitmap? = null
        fun releasePendingFrame() {
            val frame = synchronized(lock) { pendingFrame.also { pendingFrame = null } }
            frame?.bitmap?.recycle()
        }
        var attempt: Attempt? = null
        try {
        attempt = withTimeoutOrNull(tuning.timeoutMs) {
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
                            scope.handler.postDelayed(this, tuning.pollMs)
                        }
                        if (!posted) finish("handler_post_rejected")
                    }
                    override fun run() {
                        var bitmap: Bitmap? = null
                        var operation = "acquire"
                        try {
                            if (!continuation.isActive) return
                            if (!isActive()) {
                                finish("lease_not_active")
                                return
                            }
                            // Creation and ownership publication serialize through the attempt lock.
                            // Cancellation cannot leave a newly attached queue orphaned.
                            val queue = synchronized(lock) {
                                if (stopped || !continuation.isActive) return
                                requestQueue ?: scope.beginRequest()?.also { requestQueue = it }
                            }
                            if (queue == null) { finish("request_surface_unavailable"); return }
                            var capturedAt = 0L
                            var reason: String? = null
                            var stale = false
                            var empty = false
                            synchronized(queue) {
                                if (!continuation.isActive) return
                                if (queue.closed) {
                                    reason = "lease_not_active"
                                } else {
                                    val acquireStartedElapsed = SystemClock.elapsedRealtime()
                                    val image = queue.reader.acquireLatestImage()
                                    if (image == null) {
                                        empty = true
                                    } else {
                                        try {
                                            operation = "image_metadata"
                                            val timestamp = image.timestamp
                                            val sourceMs = timestamp / 1_000_000
                                            capturedAt = queue.lowerBoundElapsedMs
                                            val elapsed = SystemClock.elapsedRealtime()
                                            val uptime = SystemClock.uptimeMillis()
                                            synchronized(lock) {
                                                val sample = buildJsonObject {
                                                    put("image_timestamp_ns", timestamp)
                                                    put("acquire_started_elapsed_ms", acquireStartedElapsed)
                                                    put("received_elapsed_ms", elapsed)
                                                    put("received_uptime_ms", uptime)
                                                    put("sample_finished_elapsed_ms", SystemClock.elapsedRealtime())
                                                }
                                                if (firstClockSample == null) firstClockSample = sample
                                                lastClockSample = sample
                                                previousTimestamp?.let { previous ->
                                                    when {
                                                        timestamp == previous -> sameTimestamps++
                                                        timestamp > previous -> increasingTimestamps++
                                                        else -> decreasingTimestamps++
                                                    }
                                                }
                                                previousTimestamp = timestamp
                                                val age = if (timestamp <= 0) "invalid" else bucket(elapsed - sourceMs)
                                                if (firstAgeBucket == "none") firstAgeBucket = age
                                                lastAgeBucket = age
                                                clockGapBucket = bucket(elapsed - uptime)
                                                lastUptimeAgeBucket = if (timestamp <= 0) "invalid" else bucket(uptime - sourceMs)
                                                // Evidence only: agreement with uptime cannot prove the source clock.
                                                if (timestamp > 0 && policy.isFrameFresh(sourceMs, uptime) &&
                                                    sourceMs <= elapsed && !policy.isFrameFresh(sourceMs, elapsed)) {
                                                    uptimeFreshElapsedStale++
                                                }
                                            }
                                            // The new queue is the production-time proof. Unknown,
                                            // duplicated or regressing producer timestamps are metadata
                                            // only and cannot rejuvenate the immutable queue lower bound.
                                            stale = !policy.isFrameFresh(capturedAt, elapsed)
                                            if (!stale) {
                                                reason = when {
                                                    !policy.acceptsMirrorGeometry(image.width, image.height) -> "geometry_refused"
                                                    image.planes.size != 1 -> "plane_count_refused"
                                                    else -> null
                                                }
                                                if (reason == null) {
                                                    val plane = image.planes[0]
                                                    val width = image.width; val height = image.height
                                                    operation = "packing"
                                                    val packed = ProjectionFramePacking.pack(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
                                                    if (packed == null) {
                                                        reason = "packing_failed"
                                                    } else {
                                                        operation = "bitmap"
                                                        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                                        bitmap!!.copyPixelsFromBuffer(packed)
                                                    }
                                                }
                                            }
                                        } finally {
                                            try { image.close() } catch (e: Exception) {
                                                operation = "image_close"
                                                throw e
                                            }
                                        }
                                    }
                                }
                            }
                            if (empty) { retry("no_frame_available"); return }
                            if (stale) {
                                retry("stale_frame")
                                return
                            }
                            if (reason != null) {
                                finish(reason!!)
                                return
                            }
                            operation = "post_acquire"
                            reason = when {
                                !isActive() -> "lease_not_active"
                                !policy.isFrameFresh(capturedAt, SystemClock.elapsedRealtime()) -> "post_acquire_not_fresh"
                                else -> null
                            }
                            if (reason != null) {
                                bitmap?.recycle(); bitmap = null
                                finish(reason)
                            } else {
                                val frame = ProjectionBitmap(bitmap!!, this@ProjectionImageLease, capturedAt, attemptId)
                                bitmap = null
                                finish("captured", frame)
                            }
                        } catch (_: Exception) {
                            bitmap?.recycle()
                            finish("${operation}_exception")
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    val abandoned = synchronized(lock) {
                        stopped = true
                        scope.handler.removeCallbacks(acquire)
                        requestQueue.also { requestQueue = null }
                    }
                    // Never hold the attempt monitor while waiting for pixel-copy
                    // cleanup: the reader owner may still record metadata/finish.
                    abandoned?.let(scope::endRequest)
                }
                val posted = synchronized(lock) {
                    if (stopped || !continuation.isActive) true else scope.handler.post(acquire)
                }
                if (!posted) finish("handler_post_rejected")
            }
        }
        } finally {
            val completedQueue = synchronized(lock) {
                stopped = true
                requestQueue.also { requestQueue = null }
            }
            completedQueue?.let(scope::endRequest)
            if (attempt?.frame == null) releasePendingFrame()
        }
        val metrics = synchronized(lock) {
            buildJsonObject {
                put("attempt_id", attemptId)
                requestId?.takeIf { it.matches(Regex("[a-z0-9_]{1,40}")) }?.let { put("request_id", it) }
                put("last_observation", lastObservation)
                put("null_reads", emptyReads.coerceAtMost(1000))
                put("stale_reads", staleReads.coerceAtMost(1000))
                put("uptime_fresh_elapsed_stale", uptimeFreshElapsedStale.coerceAtMost(1000))
                put("first_age_bucket", firstAgeBucket)
                put("last_age_bucket", lastAgeBucket)
                put("clock_gap_bucket", clockGapBucket)
                put("last_uptime_age_bucket", lastUptimeAgeBucket)
                put("timestamp_same", sameTimestamps.coerceAtMost(1000))
                put("timestamp_increasing", increasingTimestamps.coerceAtMost(1000))
                put("timestamp_decreasing", decreasingTimestamps.coerceAtMost(1000))
                put("lease_active_after", isActive())
                put("consent_generation", generation)
                put("source_clock_contract", "unverified")
                put("freshness_proof", "new_request_buffer_queue")
                put("timeout_ms", tuning.timeoutMs)
                put("poll_ms", tuning.pollMs)
                put("tuning_revision", tuning.revision)
                firstClockSample?.let { put("first_clock_sample", it) }
                lastClockSample?.let { put("last_clock_sample", it) }
            }
        }
        ControlDiagnostics.record("projection_frame_acquire", attempt?.reason ?: "timeout",
            taskId = requestId?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,64}")) }, metrics = metrics)
        return attempt?.frame
    }
}

/** capturedAt: elapsed lower bound before the request queue existed, NOT receipt/source time. */
internal data class ProjectionBitmap(val bitmap: Bitmap, val lease: ProjectionImageLease, val capturedAt: Long, val attemptId: Long = 0L)

