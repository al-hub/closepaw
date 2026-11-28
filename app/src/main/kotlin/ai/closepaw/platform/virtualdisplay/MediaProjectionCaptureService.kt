package ai.closepaw.platform.virtualdisplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import ai.closepaw.security.ImageRevocationAuthority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service (`mediaProjection` FGS type) holding the OS-approved MediaProjection
 * consent round from issue #165.
 *
 * Safety rules (mirror [MediaProjectionConsentGate]):
 * - Consent is requested only via the real MediaProjectionManager system dialog launched by
 *   the user-visible caller (MainActivity). This service never automates or acknowledges the
 *   dialog and never auto-approves.
 * - startForeground with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION] happens
 *   before getMediaProjection, satisfying the Android 14+ single-use token requirement.
 * - The projection is never reused across rounds: [MediaProjectionConsentGate.onConsumed]
 *   marks the single-use grant consumed immediately after registration.
 * - Current-session onStop revokes the gate and stops the virtual display; stale callbacks
 *   cannot revoke or tear down a newer consent round. For the owning session,
 *   release capture resources, stopForeground/stopSelf.
 * - The single bounded mirror surface supplies explicit display-image requests only.
 *   WindowVisualCapture verifies strict images against isolated screenshots. A separately
 *   locally approved full-display observation may read the requested regions without
 *   certifying ownership or authorizing gestures. Native policy and active control consent
 *   remain mandatory for both modes.
 *   No raw frame is cached, persisted, logged or returned directly to MCP.
 * - Revocation races fail closed: the [MediaProjectionFramePolicy] is invalidated before
 *   any resource release, so no acquire can be legal after revocation.
 */
class MediaProjectionCaptureService : Service() {

    companion object {
        private const val TAG = "MediaProjectionCapture"
        private const val CHANNEL_ID = "media_projection_capture"
        private const val NOTIFICATION_ID = 0xF165
        private const val ACTION_STOP_SHARING = "ai.closepaw.action.STOP_SCREEN_SHARING"
        private val activeSharing = MutableStateFlow<String?>(null)
        internal val sharingRound = activeSharing.asStateFlow()

        private fun stopIntent(context: Context, round: String) =
            Intent(context, MediaProjectionCaptureService::class.java)
                .setAction(ACTION_STOP_SHARING)
                .setIdentifier(round) // Distinct immutable notification action per OS consent round.

        /** Local UI only; the service admits the request against its current OS round. */
        internal fun requestStopSharing(context: Context, round: String) {
            if (sharingRound.value == round) context.startService(stopIntent(context, round))
        }

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SESSION_ID = "extra_session_id"
        private const val EXTRA_DIRECT_REQUEST_ID = "extra_direct_request_id"

        /** Shared gate for the whole app; MainActivity feeds the OS decision, this service feeds onStop. */
        val consentGate = MediaProjectionConsentGate()

        /** Synchronous barrier; resource cleanup may follow, but revocation must not be queued. */
        fun revokeImageConsent() {
            consentGate.revokeImageConsent()
            ai.closepaw.chatgpt.control.VisualImageCache.clear()
        }

        /** Launch the OS consent round from a user-visible Activity; never auto-started by the agent. */
        fun startFromConsent(
            context: Context, resultCode: Int, resultData: Intent, sessionId: String,
            directRequestId: String? = null,
        ) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_SESSION_ID, sessionId)
                directRequestId?.let { putExtra(EXTRA_DIRECT_REQUEST_ID, it) }
            }
            context.startForegroundService(intent)
        }
    }

    private lateinit var projectionManager: MediaProjectionManager
    private var projection: MediaProjection? = null
    private var sessionOwner: MediaProjectionSessionOwner? = null
    private var activeRound: String? = null
    @Volatile private var framePolicy: MediaProjectionFramePolicy? = null
    @Volatile private var frameScope: MediaProjectionFrameScope? = null
    private var imageLease: ProjectionImageLease? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ai.closepaw.chatgpt.control.ControlDiagnostics.record("projection_lifecycle", "service_created")
        projectionManager = getSystemService(MediaProjectionManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SHARING) {
            ai.closepaw.chatgpt.control.ControlDiagnostics.record("projection_lifecycle", "notification_stop_requested")
            val owner = sessionOwner
            if (owner != null) {
                owner.stopSharingIfCurrent(sessionOwner, activeRound, intent.identifier) {
                    teardownCaptureAndService()
                }
            } else if (projection == null) {
                // A stale notification started an otherwise idle service. Do not
                // touch consent, including an approval whose start is still queued.
                if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
            }
            return START_NOT_STICKY
        }
        // A duplicate/stale start intent must never tear down an already active,
        // independently user-approved session or reuse its one-shot OS token.
        if (projection != null) {
            Log.i(TAG, "Duplicate projection start ignored while session is active")
            ai.closepaw.chatgpt.control.ControlDiagnostics.record("projection_lifecycle", "duplicate_start_ignored")
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val directRequest = intent?.getStringExtra(EXTRA_DIRECT_REQUEST_ID)?.let {
            ai.closepaw.chatgpt.control.ControlSession.ShareRequest(it)
        }
        val agent = ai.closepaw.app.AgentService.instance
        if (directRequest != null && sessionId != null && consentGate.usableSessionId() == sessionId &&
            (agent == null || !agent.deviceReadyForShareRequest() ||
                !agent.remoteControlSession.ownsShareStartup(directRequest, sessionId))) {
            revokeImageConsent()
            agent?.remoteControlSession?.cancelShareRequest(directRequest)
            stopSelfSafely()
            return START_NOT_STICKY
        }

        // Fail closed: no valid single-use session id, no grant.
        val owner = synchronized(consentGate) {
            if (!sessionId.isNullOrEmpty() && consentGate.usableSessionId() == sessionId && resultData != null) {
                MediaProjectionSessionOwner(consentGate)
            } else null
        }
        if (owner == null) {
            Log.w(TAG, "onStartCommand: consent gate not usable (${consentGate.state::class.simpleName}); stopping")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        sessionOwner = owner
        val imageEpoch = ImageRevocationAuthority.currentEpoch
        try {
            startForegroundWithRequiredType()
            val newProjection = projectionManager.getMediaProjection(resultCode, requireNotNull(resultData))
            if (newProjection == null) {
                Log.w(TAG, "onStartCommand: OS returned no projection; revoking")
                owner.runIfCurrent(sessionOwner) { revokeImageConsent() }
                stopSelfSafely()
                return START_NOT_STICKY
            }
            projection = newProjection
            registerProjectionCallbacks(newProjection, owner)
            if (!consentGate.onProjectionObtained(requireNotNull(sessionId), imageEpoch)) {
                Log.w(TAG, "Consent token already consumed or revoked; failing closed")
                framePolicy?.invalidate()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            openMirrorScope(newProjection)
            if (directRequest != null) {
                if (frameScope == null || agent?.remoteControlSession?.publishShareStartup(directRequest, sessionId) {
                        activeRound = sessionId
                        activeSharing.value = sessionId
                    } != true) {
                    agent?.remoteControlSession?.cancelShareRequest(directRequest)
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
            } else {
                activeRound = sessionId
                activeSharing.value = sessionId
            }
            startForegroundWithRequiredType(sessionId)
            Log.i(TAG, "MediaProjection consent round consumed; one-session mirror scope opened")
            ai.closepaw.chatgpt.control.ControlDiagnostics.record("projection_lifecycle", "share_active")
        } catch (e: Exception) {
            Log.w(TAG, "getMediaProjection failed; failing closed", e)
            owner.runIfCurrent(sessionOwner) { revokeImageConsent() }
            directRequest?.let { agent?.remoteControlSession?.cancelShareRequest(it) }
            stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    /** One-session, bounded, geometry and guarded image scope; null-surface idle mode removed. */
    private fun openMirrorScope(projection: MediaProjection) {
        val geometry = VirtualDisplayConfig.fromPhysicalDisplay(this)
        val policy = MediaProjectionFramePolicy()
        framePolicy = policy
        val scope = MediaProjectionFrameCapture.open(
            policy, projection, geometry.width, geometry.height, geometry.densityDpi
        )
        frameScope = scope
        if (scope == null) {
            Log.i(TAG, "Mirror scope stayed closed; diagnostic geometry unavailable, image transport unavailable")
            return
        }
        imageLease = ProjectionImageLease(scope, policy, consentGate).also(ProjectionImageTransport::publish)
    }

    private fun registerProjectionCallbacks(newProjection: MediaProjection, owner: MediaProjectionSessionOwner) {
        newProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onCapturedContentResize(width: Int, height: Int) {
                    owner.runIfCurrent(sessionOwner) {
                        if (projection !== newProjection) return@runIfCurrent
                        val scope = frameScope ?: return@runIfCurrent
                        if (scope.reader.width != width || scope.reader.height != height) {
                            ai.closepaw.chatgpt.control.ControlDiagnostics.record(
                                "projection_lifecycle", "content_resize_requires_reapproval")
                            // A resize invalidates coordinates and every in-flight bitmap.
                            // Require a new OS round instead of silently reusing geometry.
                            framePolicy?.invalidate()
                            ProjectionImageTransport.remove(imageLease)
                            teardownCaptureAndService()
                            ai.closepaw.chatgpt.control.ControlDiagnostics.record(
                                "projection_frame_acquire", "geometry_changed_reapproval_required")
                        }
                    }
                }
                override fun onStop() {
                    owner.runIfCurrent(sessionOwner) {
                        if (projection !== newProjection) return@runIfCurrent
                        Log.i(TAG, "MediaProjection.onStop: tearing down owning capture session")
                        ai.closepaw.chatgpt.control.ControlDiagnostics.record(
                            "projection_lifecycle", "os_or_user_projection_stop")
                        revokeImageConsent()
                        framePolicy?.invalidate()
                        mainHandler.post {
                            // Consent or projection ownership may change while this is queued.
                            owner.runIfCurrent(sessionOwner) {
                                if (projection === newProjection) teardownCaptureAndService()
                            }
                        }
                    }
                }
            },
            mainHandler
        )
    }

    private fun stopSelfSafely() = teardownCaptureAndService()
    private fun teardownCaptureAndService() {
        teardownCaptureResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardownCaptureResources() {
        sessionOwner?.runIfCurrent(sessionOwner) { revokeImageConsent() }
        // Detach before stop(): its callback can be delivered after a newer session starts.
        sessionOwner = null
        activeRound?.let { activeSharing.compareAndSet(it, null) }
        activeRound = null
        // Invalidate first so any in-flight acquire loses the race and fails closed.
        framePolicy?.invalidate()
        ProjectionImageTransport.remove(imageLease)
        imageLease = null
        framePolicy = null
        runCatching { frameScope?.close() }
        frameScope = null
        val previousProjection = projection
        projection = null
        runCatching { previousProjection?.stop() }
    }

    private fun startForegroundWithRequiredType(round: String? = null) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (round == null) "화면 공유 준비 중" else "화면 공유 중")
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setOngoing(true)
        if (round != null) {
            val stop = PendingIntent.getService(this, 0, stopIntent(this, round), PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "화면 공유 중지", stop
            ).build())
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: final capture teardown")
        ai.closepaw.chatgpt.control.ControlDiagnostics.record("projection_lifecycle",
            if (projection == null) "service_destroyed_idle" else "service_destroyed_active")
        teardownCaptureResources()
        super.onDestroy()
    }
}
