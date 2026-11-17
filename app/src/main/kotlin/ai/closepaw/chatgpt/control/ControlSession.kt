package ai.closepaw.chatgpt.control

import java.util.UUID

/** User priority state machine. No Context, screen content, command queue or automatic replay. */
internal class ControlSession(initial: String = "idle", private val persist: (String) -> Unit = {}) {
    enum class State { IDLE, ACTIVE, WAITING, PAUSED, REOBSERVING, ENDED }
    enum class UserAction(val id: String, val label: String) {
        ALLOW_SCREEN_SHARE("allow_screen_share", "공유를 허용하세요"),
        STOP_SCREEN_SHARE("stop_screen_share", "공유를 중지하세요"),
        REQUEST_SCREEN_SHARE("request_screen_share", "화면 공유 요청 · 눌러서 열기"),

        ;

        fun satisfiedBy(sharing: Boolean) = sharing == (this != STOP_SCREEN_SHARE)
    }
    data class UserActionHint(val action: UserAction, val expiresAtMs: Long?, val shareRequest: ShareRequest? = null)
    data class ShareRequest(val id: String = UUID.randomUUID().toString())
    enum class ShareRequestState { AWAITING_TAP, AWAITING_ANDROID, STARTING, SHARING_STARTED, CANCELLED }
    enum class HintUpdate { SHOWN, UNCHANGED, NOT_NEEDED, CLEARED, STALE, REFUSED }
    @Volatile var userActionEpoch = 0L
        private set
    @Volatile var userActionRevision = 0
        private set
    private var lastUserAction: UserAction? = null
    private var userActionHint: UserActionHint? = null
    @Volatile var shareRequestState: ShareRequestState? = null
        private set
    private var requestedProjectionId: String? = null
    enum class VisualPhase { PREPARING, OBSERVING, EXECUTING, WAITING, COMPLETED, FAILED }
    @Volatile var visualPhase = VisualPhase.PREPARING
        private set
    @Synchronized fun present(phase: VisualPhase, expectedEpoch: Long?) {
        if (state != State.ACTIVE || (expectedEpoch != null && epoch != expectedEpoch)) return
        if (visualPhase == phase) return
        visualPhase = phase
        label = when (phase) {
            VisualPhase.PREPARING -> "AI 제어 준비"
            VisualPhase.OBSERVING -> "화면 확인 중"
            VisualPhase.EXECUTING -> "AI 제어 중"
            VisualPhase.WAITING -> "응답 대기 중"
            VisualPhase.COMPLETED -> "실행 단계 확인 완료"
            VisualPhase.FAILED -> "결과 미확인 · 재관찰 필요"
        }
        changed()
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
    /** Fixed stage vocabulary only: never display prompt, response, package or raw errors. */
    @Synchronized fun geminiProgress(stage: String, expectedEpoch: Long) {
        if (epoch != expectedEpoch || state != State.ACTIVE) return
        val (action, status, phase) = when (stage) {
            "launching" -> Triple("앱을 실행하고 있습니다", "Gemini 실행 요청 중", VisualPhase.EXECUTING)
            "observing" -> Triple("화면 확인 중", "기존 입력·대상 화면 확인 중", VisualPhase.OBSERVING)
            "inputting" -> Triple("입력란에 글자를 입력하고 있습니다", "빈 입력란에 질문 입력 중", VisualPhase.EXECUTING)
            "submitting" -> Triple("선택한 항목을 누르고 있습니다", "입력 일치 확인 완료 · 전송 동작 요청 중", VisualPhase.EXECUTING)
            "waiting" -> Triple("응답 대기 중", "전송 동작 요청 후 새 답변 관찰 중 · 제출/완료 별도 확인", VisualPhase.WAITING)
            "collecting" -> Triple("화면 확인 중", "새 답변과 완료·범위 확인 중", VisualPhase.OBSERVING)
            "scrolling" -> Triple("화면을 스크롤하고 있습니다", "검증된 답변 영역의 다음 범위 확인 중", VisualPhase.EXECUTING)
            "completed" -> Triple("Gemini 응답 확인 완료", "새 답변과 전체 수집 범위 확인 완료", VisualPhase.COMPLETED)
            "interrupted" -> Triple("응답 관찰 중단", "ClosePaw 관찰 중단 · Gemini 생성 취소 미확인 · 자동 재전송 금지", VisualPhase.FAILED)
            "failed" -> Triple("결과 미확인 · 재관찰 필요", "응답 또는 제출 확인 실패 · 자동 재전송 금지", VisualPhase.FAILED)
            else -> return
        }
        // Expected next work is a plan, never proof that submission or generation completed.
        val next = when (stage) {
            "launching" -> "대상 화면과 기존 입력 확인 예정"
            "observing" -> "빈 입력란 확인 후 질문 입력 예정"
            "inputting" -> "입력 일치 확인 후 전송 요청 예정"
            "submitting" -> "새 답변 관찰 예정 · 제출 성공 미확정"
            "waiting", "collecting" -> "새 답변 완료·수집 범위 확인 예정"
            "scrolling" -> "다음 답변 영역 확인 예정"
            "completed" -> "없음"
            else -> "자동 재실행 없음 · 사용자 확인 필요"
        }
        val completion = if (stage == "completed") "새 답변과 전체 수집 범위 확인" else
            "전체 목표 완료 미확정"
        val detail = "목표: Gemini 질문 및 새 응답 확인\n대상: Gemini\n완료: $completion\n현재: $status\n다음: $next\n상태: $status"
        if (label == action && progressDetail == detail && visualPhase == phase) return
        label = action; progressDetail = detail; visualPhase = phase; changed()
    }
    var changed: () -> Unit = {}
    var interrupt: () -> Unit = {}
    var label: String = if (state == State.PAUSED) "사용자 제어 중 · 일시정지" else "AI 제어 준비"
        private set
    // The Android accessibility session can survive MCP runtime replacement.
    // Keep the no-replay latch here, never in a per-runtime executor.
    private var visualSearchReserved = false
    @Synchronized fun reserveVisualSearch(expectedId: String, expectedEpoch: Long): Boolean {
        if (!explicit || state != State.ACTIVE || id != expectedId ||
            epoch != expectedEpoch || visualSearchReserved) return false
        visualSearchReserved = true
        return true
    }
    @Synchronized fun releaseVisualSearchIfNotAttempted(expectedId: String, attempted: Boolean) {
        if (id == expectedId && !attempted) visualSearchReserved = false
    }
    @Synchronized fun start(): Boolean {
        if (state !in setOf(State.IDLE, State.ENDED)) return false
        id = UUID.randomUUID().toString(); explicit = true
        userActionRevision = 0; lastUserAction = null
        shareRequestState = null
        visualSearchReserved = false
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
    @Synchronized fun waitForUser(action: UserAction? = null, expectedSessionId: String? = null): Boolean {
        if (state != State.ACTIVE || action == UserAction.REQUEST_SCREEN_SHARE ||
            (expectedSessionId != null && id != expectedSessionId)) return false
        transition(State.WAITING, action?.label ?: "사용자 응답 대기"); interrupt(); return true
    }

    /** Clear the screen-specific instruction without resuming AI control. */
    @Synchronized fun clearUserActionHint(completed: UserAction? = null) {
        if (state != State.WAITING || label == "사용자 응답 대기" ||
            (completed != null && label != completed.label)) return
        label = "사용자 응답 대기"; changed()
    }

    /** A display-only request. Neither completion nor dismissal changes control state. */
    @Synchronized fun updateUserAction(
        sessionId: String, expectedHintEpoch: Long, revision: Int, action: UserAction?,
        sharing: Boolean, nowMs: Long,
    ): HintUpdate {
        if (state != State.ACTIVE || !explicit || id != sessionId || userActionEpoch != expectedHintEpoch)
            return HintUpdate.REFUSED
        if (revision < userActionRevision ||
            (revision == userActionRevision && lastUserAction != action)) return HintUpdate.STALE
        if (revision == userActionRevision) return HintUpdate.UNCHANGED
        userActionRevision = revision
        lastUserAction = action
        val previous = userActionHint
        userActionHint = when {
            action == null || action.satisfiedBy(sharing) -> null
            previous != null && previous.action == action && (previous.expiresAtMs ?: Long.MAX_VALUE) > nowMs -> previous
            action == UserAction.REQUEST_SCREEN_SHARE -> UserActionHint(action, null, ShareRequest())
            else -> UserActionHint(action, nowMs + USER_ACTION_TTL_MS)
        }
        if (previous !== userActionHint) {
            requestedProjectionId = null
            if (previous?.shareRequest != null) shareRequestState = ShareRequestState.CANCELLED
            if (userActionHint?.shareRequest != null) shareRequestState = ShareRequestState.AWAITING_TAP
        }
        if (previous !== userActionHint) changed()
        return when {
            action == null -> HintUpdate.CLEARED
            userActionHint == null -> HintUpdate.NOT_NEEDED
            previous === userActionHint -> HintUpdate.UNCHANGED
            else -> HintUpdate.SHOWN
        }
    }

    /** Called with the current OS sharing state. Timeout and successful action only clear UI. */
    @Synchronized fun currentUserAction(sharing: Boolean, nowMs: Long): UserActionHint? {
        val hint = userActionHint ?: return null
        if ((hint.expiresAtMs ?: Long.MAX_VALUE) <= nowMs || hint.action.satisfiedBy(sharing)) {
            if (hint.shareRequest != null) {
                // Other local sharing can satisfy the user's goal without proving this dialog succeeded.
                shareRequestState = ShareRequestState.CANCELLED
            }
            userActionHint = null
            requestedProjectionId = null
            return null
        }
        return hint
    }

    /** Compare the exact displayed request so a gesture on a replaced island cannot launch consent. */
    @Synchronized fun claimShareRequest(request: ShareRequest): Boolean {
        if (state != State.ACTIVE || userActionHint?.shareRequest != request ||
            shareRequestState != ShareRequestState.AWAITING_TAP) return false
        shareRequestState = ShareRequestState.AWAITING_ANDROID
        changed()
        return true
    }

    @Synchronized fun ownsShareRequest(request: ShareRequest, phase: ShareRequestState): Boolean =
        state == State.ACTIVE && userActionHint?.shareRequest == request && shareRequestState == phase

    @Synchronized fun beginShareRequestStartup(request: ShareRequest, projectionId: String): Boolean {
        if (!ownsShareRequest(request, ShareRequestState.AWAITING_ANDROID)) return false
        requestedProjectionId = projectionId
        shareRequestState = ShareRequestState.STARTING
        changed()
        return true
    }

    @Synchronized fun ownsShareStartup(request: ShareRequest, projectionId: String): Boolean =
        ownsShareRequest(request, ShareRequestState.STARTING) && requestedProjectionId == projectionId

    @Synchronized fun publishShareStartup(request: ShareRequest, projectionId: String, publish: () -> Unit): Boolean {
        if (!ownsShareStartup(request, projectionId)) return false
        publish()
        userActionHint = null
        requestedProjectionId = null
        shareRequestState = ShareRequestState.SHARING_STARTED
        changed()
        return true
    }

    @Synchronized fun cancelShareRequest(request: ShareRequest) {
        if (userActionHint?.shareRequest != request) return
        shareRequestState = ShareRequestState.CANCELLED
        userActionHint = null
        requestedProjectionId = null
        changed()
    }

    @Synchronized fun dropUserActionForScreenOff() {
        userActionEpoch++
        if (userActionHint == null) return
        if (userActionHint?.shareRequest != null) shareRequestState = ShareRequestState.CANCELLED
        userActionHint = null
        requestedProjectionId = null
        changed()
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
        if (label == text) return
        label = text; changed()
    }
    private fun transition(next: State, text: String) {
        state = next; epoch++; label = text
        if (userActionHint?.shareRequest != null) shareRequestState = ShareRequestState.CANCELLED
        userActionHint = null; userActionEpoch++
        requestedProjectionId = null
        progressDetail = null
        if (next == State.ACTIVE) visualPhase = VisualPhase.PREPARING
        persist(next.name.lowercase()); changed()
    }

    companion object {
        const val USER_ACTION_TTL_MS = 30_000L
    }
}
