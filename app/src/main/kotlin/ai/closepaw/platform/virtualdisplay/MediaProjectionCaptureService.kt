package ai.closepaw.platform.virtualdisplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
 * - onStop teardown is unconditional: revoke feed into the gate, stop the virtual display,
 *   release capture resources, stopForeground/stopSelf.
 * - The single bounded mirror surface supplies explicit display-image requests only.
 *   WindowVisualCapture verifies pixels against isolated accessibility screenshots and
 *   masks everything else; native policy and active control consent remain mandatory.
 *   No raw frame is cached, persisted, logged or returned directly to MCP.
 * - Revocation races fail closed: the [MediaProjectionFramePolicy] is invalidated before
 *   any resource release, so no acquire can be legal after revocation.
 */
class MediaProjectionCaptureService : Service() {

    companion object {
        private const val TAG = "MediaProjectionCapture"
        private const val CHANNEL_ID = "media_projection_capture"
        private const val NOTIFICATION_ID = 0xF165

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SESSION_ID = "extra_session_id"

        /** Shared gate for the whole app; MainActivity feeds the OS decision, this service feeds onStop. */
        val consentGate = MediaProjectionConsentGate()

        /** Synchronous barrier; resource cleanup may follow, but revocation must not be queued. */
        fun revokeImageConsent() {
            consentGate.revokeImageConsent()
        }

        /** Launch the OS consent round from a user-visible Activity; never auto-started by the agent. */
        fun startFromConsent(context: Context, resultCode: Int, resultData: Intent, sessionId: String) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            context.startForegroundService(intent)
        }
    }

    private lateinit var projectionManager: MediaProjectionManager
    private var projection: MediaProjection? = null
    @Volatile private var framePolicy: MediaProjectionFramePolicy? = null
    @Volatile private var frameScope: MediaProjectionFrameScope? = null
    private var imageLease: ProjectionImageLease? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        startForegroundWithRequiredType()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A duplicate/stale start intent must never tear down an already active,
        // independently user-approved session or reuse its one-shot OS token.
        if (projection != null) {
            Log.i(TAG, "Duplicate projection start ignored while session is active")
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)

        // Fail closed: no valid single-use session id, no grant.
        val usable = consentGate.usableSessionId()
        if (sessionId.isNullOrEmpty() || usable != sessionId || resultData == null) {
            Log.w(TAG, "onStartCommand: consent gate not usable (${consentGate.state::class.simpleName}); stopping")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val imageEpoch = ImageRevocationAuthority.currentEpoch
        try {
            val newProjection = projectionManager.getMediaProjection(resultCode, resultData)
            if (newProjection == null) {
                Log.w(TAG, "onStartCommand: OS returned no projection; revoking")
                revokeImageConsent()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            projection = newProjection
            registerProjectionCallbacks(newProjection)
            if (!consentGate.onProjectionObtained(sessionId, imageEpoch)) {
                Log.w(TAG, "Consent token already consumed or revoked; failing closed")
                framePolicy?.invalidate()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            openMirrorScope(newProjection)
            Log.i(TAG, "MediaProjection consent round consumed; one-session mirror scope opened")
        } catch (e: Exception) {
            Log.w(TAG, "getMediaProjection failed; failing closed", e)
            revokeImageConsent()
            stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    /** One-session, bounded, geometry and guarded image scope; null-surface idle mode removed. */
    private fun openMirrorScope(projection: MediaProjection) {
        val metrics = resources.displayMetrics
        val policy = MediaProjectionFramePolicy()
        framePolicy = policy
        val scope = MediaProjectionFrameCapture.open(
            policy, projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi
        )
        frameScope = scope
        if (scope == null) {
            Log.i(TAG, "Mirror scope stayed closed; diagnostic geometry unavailable, image transport unavailable")
            return
        }
        imageLease = ProjectionImageLease(scope, policy, consentGate).also(ProjectionImageTransport::publish)
    }

    private fun registerProjectionCallbacks(newProjection: MediaProjection) {
        newProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection.onStop: tearing down capture (revoked feed to gate)")
                    revokeImageConsent()
                    framePolicy?.invalidate()
                    mainHandler.post { teardownCaptureAndService() }
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
        revokeImageConsent()
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

    private fun startForegroundWithRequiredType() {
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
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ClosePaw screen capture active")
            .setContentText("Use Android screen-sharing controls to stop sharing.")
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setOngoing(true)
            .build()
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
        teardownCaptureResources()
        super.onDestroy()
    }
}
