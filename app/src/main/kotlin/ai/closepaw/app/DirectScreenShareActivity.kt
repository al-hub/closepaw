package ai.closepaw.app

import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.platform.virtualdisplay.MediaProjectionCaptureService
import ai.closepaw.platform.virtualdisplay.ProjectionConsentState
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID

/** Private host for the same full-display MediaProjection consent as the Access screen. */
internal class DirectScreenShareActivity : ComponentActivity() {
    private val gate get() = MediaProjectionCaptureService.consentGate
    private var request: ControlSession.ShareRequest? = null
    private var generation: Long? = null
    private val approval = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val token = request
        val matchingRound = generation != null && generation == gate.generation &&
            gate.state == ProjectionConsentState.Requesting
        val service = AgentService.instance
        val projectionId = UUID.randomUUID().toString()
        val data = result.data
        if (matchingRound && token != null && service != null && service.deviceReadyForShareRequest() &&
            result.resultCode == RESULT_OK && data != null &&
            service.remoteControlSession.beginShareRequestStartup(token, projectionId) &&
            gate.onDecision(true, projectionId) is ProjectionConsentState.Granted) {
            try {
                MediaProjectionCaptureService.startFromConsent(this, result.resultCode, data,
                    projectionId, directRequestId = token.id)
                service.watchDirectScreenShareStartup(token)
            } catch (_: RuntimeException) {
                service.remoteControlSession.cancelShareRequest(token)
                if (gate.usableSessionId() == projectionId) MediaProjectionCaptureService.revokeImageConsent()
            }
        } else {
            if (matchingRound) gate.onCancelled()
            token?.let { service?.remoteControlSession?.cancelShareRequest(it) }
        }
        generation = null
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(REQUEST_ID)?.let { ControlSession.ShareRequest(it) }
        request = token
        val service = AgentService.instance
        if (savedInstanceState?.containsKey(GENERATION) == true) generation = savedInstanceState.getLong(GENERATION)
        if (Build.VERSION.SDK_INT < 34 || token == null || service == null ||
            !service.deviceReadyForShareRequest() ||
            !service.remoteControlSession.ownsShareRequest(token, ControlSession.ShareRequestState.AWAITING_ANDROID)) {
            cancel()
            return
        }
        // The ActivityResultRegistry reconnects to the same result across recreation. Never re-open it.
        if (savedInstanceState != null) {
            if (generation != gate.generation || gate.state != ProjectionConsentState.Requesting) cancel()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null || MediaProjectionCaptureService.sharingRound.value != null ||
            gate.state == ProjectionConsentState.Consumed || !gate.onRequestStarted(screenObservation = true)) {
            cancel()
            return
        }
        generation = gate.generation
        try {
            approval.launch(manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()))
        } catch (_: RuntimeException) {
            cancel()
        }
    }

    private fun cancel() {
        clearPendingRound()
        finish()
    }

    private fun clearPendingRound() {
        if (generation != null && generation == gate.generation) gate.onCancelled()
        generation = null
        request?.let { token ->
            val session = AgentService.instance?.remoteControlSession
            if (session?.ownsShareRequest(token, ControlSession.ShareRequestState.AWAITING_ANDROID) == true)
                session.cancelShareRequest(token)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        generation?.let { outState.putLong(GENERATION, it) }
    }

    override fun onDestroy() {
        if (isFinishing) clearPendingRound()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_ID = "closepaw.direct.projection.request"
        private const val GENERATION = "closepaw.direct.projection.generation"

        internal fun intent(context: Context, request: ControlSession.ShareRequest) =
            Intent(context, DirectScreenShareActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(REQUEST_ID, request.id)
    }
}
