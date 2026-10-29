package ai.closepaw.chatgpt

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager

/** Non-exported, device-only approval. Remote operations cannot approve this dialog. */
class SelfMonitoringConsentActivity : Activity() {
    internal var consentDialog: AlertDialog? = null
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = true
        val id = intent.getStringExtra("monitor_id")
        val grant = SelfMonitoringAuthority.snapshot()
        if (id == null || grant?.id != id || grant.phase != "awaiting_consent") { finish(); return }
        val session = ai.closepaw.app.AgentService.instance?.remoteControlSession
            ?: run { SelfMonitoringAuthority.stop(id, "interrupted"); finish(); return }
        if (session.id != grant.sessionId || session.epoch != grant.epoch ||
            session.state != ai.closepaw.chatgpt.control.ControlSession.State.ACTIVE) {
            SelfMonitoringAuthority.stop(id, "interrupted"); finish(); return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("브리지 감시 및 단회 복구 승인")
            .setMessage("대상: ClosePaw와 연결된 Termux 브리지\n기간: 승인 후 ${grant.durationMs / 1000}초\n허용: 상태 확인 및 오프라인 브리지 시작 1회\n\n명령 재실행, 앱 설치, 화면 공유 승인, 권한 변경은 포함되지 않습니다. X 종료·잠금·권한 철회 시 중단됩니다.")
            .setPositiveButton("승인") { _, _ ->
                val keyguard = getSystemService(android.app.KeyguardManager::class.java)
                val power = getSystemService(android.os.PowerManager::class.java)
                if (!keyguard.isKeyguardLocked && power.isInteractive &&
                    session.id == grant.sessionId && session.epoch == grant.epoch &&
                    McpExecutionSettings(this).let { it.enabled && it.genericControlEnabled }) {
                    SelfMonitoringAuthority.approve(id, SystemClock.elapsedRealtime())
                } else SelfMonitoringAuthority.stop(id, "interrupted")
                finish()
            }
            .setNegativeButton("취소") { _, _ -> SelfMonitoringAuthority.stop(id); finish() }
            .setOnCancelListener { SelfMonitoringAuthority.stop(id); finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
        }
        consentDialog = dialog
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }
}
