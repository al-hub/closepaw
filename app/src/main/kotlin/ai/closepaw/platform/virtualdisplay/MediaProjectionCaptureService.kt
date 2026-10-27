package ai.closepaw.platform.virtualdisplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
 * - No screenshot, frame read, or media encoding happens here; the virtual display surface
 *   stays empty until a future, separately-audited capture consumer is attached.
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
    private var virtualDisplay: VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        startForegroundWithRequiredType()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            Log.i(TAG, "MediaProjection consent round consumed; holding projection in FGS (no capture surface)")
        } catch (e: Exception) {
            Log.w(TAG, "getMediaProjection failed; failing closed", e)
            consentGate.onRevoked()
            stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    /** Create the main-screen virtual display with a null surface that renders/encodes nothing. */
    private fun createIdleVirtualDisplay(projection: MediaProjection): VirtualDisplay? {
        val metrics = resources.displayMetrics
        val display = projection.createVirtualDisplay(
            "ClosePaw#165-idle",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null, // null surface: capture pipeline is created but writes no frames.
            null,
            mainHandler
        )
        virtualDisplay = display
        return display
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
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.stop()
        projection = null
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
