package ai.closepaw.chatgpt.control

import java.util.UUID

/** User priority state machine. No Context, screen content, command queue or automatic replay. */
internal class ControlSession(initial: String = "idle", private val persist: (String) -> Unit = {}) {
    enum class State { IDLE, ACTIVE, WAITING, PAUSED, REOBSERVING, ENDED }
    enum class VisualPhase { PREPARING, OBSERVING, EXECUTING, WAITING, COMPLETED, FAILED }
    @Volatile var visualPhase = VisualPhase.PREPARING
        private set
    @Synchronized fun present(phase: VisualPhase, expectedEpoch: Long?) {
        if (state != State.ACTIVE || (expectedEpoch != null && epoch != expectedEpoch)) return
        visualPhase = phase; changed()
    }
    @Volatile var state = when (initial) { "paused" -> State.PAUSED; "ended", "active", "waiting", "reobserving" -> State.ENDED; else -> State.IDLE }
        private set
    @Volatile var epoch = 0L
        private set
    @Volatile var id: String = UUID.randomUUID().toString()
        private set
    @Volatile var explicit = initial != "idle"
        private set
    @Volatile var progressDetail: String? = null
        private set
    @Synchronized fun sequenceProgress(total: Int, completed: Int, stage: String, expectedEpoch: Long) {
        if (epoch != expectedEpoch || state != State.ACTIVE) return
        val current = if (completed < total) "${completed + 1}/$total" else "없음"
        val next = if (completed + 1 < total) "${completed + 2}/$total" else "없음"
        progressDetail = "목표: 숫자 $total 단계 입력\n대상: 허용된 계산기\n완료: $completed/$total\n현재: $current\n다음: $next\n상태: $stage"
        changed()
    }
    var changed: () -> Unit = {}
    var interrupt: () -> Unit = {}
    var label: String = if (state == State.PAUSED) "사용자 제어 중 · 일시정지" else "AI 제어 준비"
        private set
    @Synchronized fun start(): Boolean {
        if (state !in setOf(State.IDLE, State.ENDED)) return false
        id = UUID.randomUUID().toString(); explicit = true
        transition(State.ACTIVE, "AI 제어 중")
        return true
    }
    @Synchronized fun activateLegacy() {
        if (state == State.IDLE) transition(State.ACTIVE, "AI 제어 중")
    }
    @Synchronized fun pause() {
        if (state !in setOf(State.ACTIVE, State.WAITING, State.REOBSERVING)) return
        transition(State.PAUSED, "사용자 제어 중 · 일시정지"); interrupt()
    }
    @Synchronized fun manualTakeover() {
        if (state !in setOf(State.ACTIVE, State.WAITING, State.REOBSERVING, State.PAUSED)) return
        transition(State.PAUSED, "직접 조작 중 · AI 일시정지"); interrupt()
    }
    @Synchronized fun end() {
        transition(State.ENDED, "AI 제어 종료"); interrupt()
    }
    // Called only by the physical overlay button, never exposed as an AI operation.
    @Synchronized fun beginResume(): Boolean {
        if (state != State.PAUSED) return false
        transition(State.REOBSERVING, "재개 전 화면 확인 중"); return true
    }
    @Synchronized fun finishResume(expectedEpoch: Long, safe: Boolean) {
        if (state != State.REOBSERVING || epoch != expectedEpoch) return
        transition(if (safe) State.ACTIVE else State.PAUSED, if (safe) "AI 제어 중 · 새 명령 대기" else "화면 확인 필요 · 일시정지")
    }
    @Synchronized fun waitForUser(): Boolean {
        if (state != State.ACTIVE) return false
        transition(State.WAITING, "사용자 응답 대기"); interrupt(); return true
    }
    @Synchronized fun userResponded(): Boolean {
        if (state != State.WAITING) return false
        transition(State.PAUSED, "응답 확인 · 재개 전 화면 확인"); return true
    }
    fun refusal(sessionId: String? = null, mutation: Boolean = false): String? = when {
        state == State.ENDED -> "control_session_ended"
        state == State.PAUSED -> "control_session_paused"
        state == State.WAITING -> "control_waiting_for_user"
        state == State.REOBSERVING && mutation -> "control_reobservation_required"
        explicit && sessionId != id -> "control_session_id_required"
        else -> null
    }
    @Synchronized fun feedback(text: String, expectedEpoch: Long? = null) {
        if (state != State.ACTIVE || (expectedEpoch != null && epoch != expectedEpoch)) return
        label = text; changed()
    }
    private fun transition(next: State, text: String) {
        state = next; epoch++; label = text
        progressDetail = null
        if (next == State.ACTIVE) visualPhase = VisualPhase.PREPARING
        persist(next.name.lowercase()); changed()
    }
}
