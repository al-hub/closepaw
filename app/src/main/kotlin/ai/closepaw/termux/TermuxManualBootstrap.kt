package ai.closepaw.termux

import android.content.Context
import android.util.Base64

internal object TermuxManualBootstrap {
    fun command(): String =
        "umask 077; mkdir -p ~/.closepaw ~/closepaw/workspace ~/closepaw/logs; " +
            "read -r CLOSEPAW_TOKEN; printf '%s' \"\$CLOSEPAW_TOKEN\" > ~/.closepaw/token; " +
            "chmod 600 ~/.closepaw/token; unset CLOSEPAW_TOKEN; " +
            "CLOSEPAW_BRIDGE_TOKEN=\"\$(cat ~/.closepaw/token)\" " +
            "nohup python3 ~/.closepaw/bridge.py >/dev/null 2>~/closepaw/logs/bridge.err </dev/null &"

    fun token(context: Context): String = TermuxBridgeAuth.token(context)

    fun bridgePayload(context: Context): String =
        context.resources.openRawResource(ai.closepaw.R.raw.closepaw_bridge_py).use {
            Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
        }
}
