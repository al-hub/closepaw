package ai.closepaw.termux

/**
 * Process-lifetime policy shared by every Local Bridge launch path.
 *
 * Google Play Termux has no RUN_COMMAND recovery transport. A finite idle timeout would
 * eventually make the paired bridge unreachable until the user opened Termux again, so the
 * paired daemon is intentionally persistent and relies on authenticated loopback access.
 */
internal object TermuxBridgeLaunchPolicy {
    const val PERSISTENT_DAEMON_ARGS = "--idle-timeout-sec 0"
}
