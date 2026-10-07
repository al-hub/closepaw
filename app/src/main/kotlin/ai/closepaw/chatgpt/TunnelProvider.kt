package ai.closepaw.chatgpt

import kotlinx.coroutines.flow.StateFlow

internal sealed interface TunnelStatus {
    data object Stopped : TunnelStatus
    data object Starting : TunnelStatus
    data class Connected(\n        val publicUrl: String? = null,\n        val tunnelId: String? = null,\n    ) : TunnelStatus
    data class Failed(val reason: String) : TunnelStatus
}

internal interface TunnelProvider {
    val status: StateFlow<TunnelStatus>
    fun start(localPort: Int)
    fun stop()
}
