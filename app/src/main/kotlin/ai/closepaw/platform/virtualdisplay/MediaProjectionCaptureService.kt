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
 * - The only capture surface is the one-session, bounded diagnostic mirror scope
 *   ([MediaProjectionFrameCapture]): it reads frame GEOMETRY (width/height/timestamp)
 *   from the newest mirrored frame and closes every buffer immediately. A whole-display
 *   mirror can never prove certified-window ownership or mask unknown overlays, so its
 *   pixels are never exposed as images or coordinates; image delivery stays exclusively
 *   with the accessibility screenshot path (WindowVisualCapture).
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

        try {
            val newProjection = projectionManager.getMediaProjection(resultCode, resultData)
            if (newProjection == null) {
                Log.w(TAG, "onStartCommand: OS returned no projection; revoking")
                consentGate.onRevoked()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            projection = newProjection
            registerProjectionCallbacks(newProjection)
            consentGate.onConsumed(sessionId)
            openDiagnosticMirrorScope(newProjection)
            Log.i(TAG, "MediaProjection consent round consumed; one-session diagnostic mirror scope handled")
        } catch (e: Exception) {
            Log.w(TAG, "getMediaProjection failed; failing closed", e)
            consentGate.onRevoked()
            stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    /** One-session, bounded, diagnostic-only geometry scope; null-surface idle mode removed. */
    private fun openDiagnosticMirrorScope(projection: MediaProjection) {
        val metrics = resources.displayMetrics
        val policy = MediaProjectionFramePolicy()
        framePolicy = policy
        val scope = MediaProjectionFrameCapture.open(
            policy, projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi
        )
        frameScope = scope
        if (scope == null) {
            Log.i(TAG, "Mirror scope stayed closed; diagnostic geometry unavailable, capture remains disabled")
            return
        }
        // Image acquisition may wait up to two seconds: never block Android's main thread.
        Thread({
            val current = frameScope
            val currentPolicy = framePolicy
            if (current === scope && currentPolicy != null) {
                val geometry = MediaProjectionFrameCapture.latestDiagnosticGeometry(current, currentPolicy)
                val dims = if (geometry.width != null && geometry.height != null) {
                    "${geometry.width}x${geometry.height}"
                } else "unknown"
                Log.i(TAG, "Mirror frame geometry ${dims} (${geometry.reason}); " +
                    "pixels never exposed, ownership proof stays with accessibility screenshots")
            }
        }, "ClosePawMirrorDiagnostic").start()
    }

    private fun registerProjectionCallbacks(newProjection: MediaProjection) {
        newProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection.onStop: tearing down capture (revoked feed to gate)")
                    consentGate.onRevoked()
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
        // Invalidate first so any in-flight acquire loses the race and fails closed.
        framePolicy?.invalidate()
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
        consentGate.onRevoked()
        teardownCaptureResources()
        super.onDestroy()
    }
}
