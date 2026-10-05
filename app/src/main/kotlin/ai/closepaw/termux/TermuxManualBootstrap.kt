package ai.closepaw.termux

import android.content.Context
import android.util.Base64

internal object TermuxManualBootstrap {
    private const val BOOT_SCRIPT_PATH = "~/.termux/boot/10-closepaw-bridge"

    fun bootScript(): String =
        """#!/data/data/com.termux/files/usr/bin/sh
umask 077
TOKEN_FILE="$HOME/.closepaw/token"
BRIDGE="$HOME/.closepaw/bridge.py"
LOG_DIR="$HOME/closepaw/logs"
[ -s "$TOKEN_FILE" ] || exit 0
[ -f "$BRIDGE" ] || exit 0
mkdir -p "$LOG_DIR"
if command -v curl >/dev/null 2>&1 && curl -fsS --max-time 1 http://127.0.0.1:18422/v1/health 2>/dev/null | grep -q '"identity":"closepaw-bridge"'; then
  exit 0
fi
CLOSEPAW_BRIDGE_TOKEN="$(cat "$TOKEN_FILE")" nohup python3 "$BRIDGE" >/dev/null 2>"$LOG_DIR/bridge.err" </dev/null &
""".trimIndent()

    fun command(): String {
        val bootScriptBase64 =
            Base64.encodeToString(bootScript().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "umask 077; mkdir -p ~/.closepaw ~/closepaw/workspace ~/closepaw/logs ~/.termux/boot; " +
            "read -r CLOSEPAW_TOKEN; printf '%s' "$CLOSEPAW_TOKEN" > ~/.closepaw/token; " +
            "chmod 600 ~/.closepaw/token; unset CLOSEPAW_TOKEN; " +
            "printf '%s' '$bootScriptBase64' | base64 -d > $BOOT_SCRIPT_PATH; " +
            "chmod 700 $BOOT_SCRIPT_PATH; " +
            "CLOSEPAW_BRIDGE_TOKEN="$(cat ~/.closepaw/token)" " +
            "nohup python3 ~/.closepaw/bridge.py >/dev/null 2>~/closepaw/logs/bridge.err </dev/null &"
    }

    fun token(context: Context): String = TermuxBridgeAuth.token(context)

    fun bridgePayload(context: Context): String =
        context.resources.openRawResource(ai.closepaw.R.raw.closepaw_bridge_py).use {
            Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
        }
}
