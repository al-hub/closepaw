package ai.closepaw.chatgpt

import kotlinx.coroutines.flow.StateFlow

internal sealed interface TunnelStatus {
    data object Stopped : TunnelStatus
    data object Starting : TunnelStatus
    data class Connected(val publicUrl: String) : TunnelStatus
    data class Failed(val reason: String) : TunnelStatus
}

internal interface TunnelProvider {
    val status: StateFlow<TunnelStatus>
    fun start(localPort: Int)
    fun stop()
}
