# ChatGPT connection lifecycle (v0.1.46)

The v0.1.45 phone reboot test recovered the Termux bridge, but the ChatGPT
connection remained Stopped until Connect was pressed. The foreground service
was started only by that button and returned START_NOT_STICKY; there was no
saved connection intent or boot receiver.

After a secure tunnel is configured, the default is now connected. Activity
resume, BOOT_COMPLETED (after unlock), and MY_PACKAGE_REPLACED restore that
intent. Existing encrypted tunnel credentials, private endpoint, execution
permissions, Termux pairing and KUM data remain unchanged. An unconfigured
installation does not automatically publish a Quick Tunnel. Quick Tunnel can
still be connected manually, but is not automatically published on boot.

Stop in the app or connection notification persists a paused state before
stopping the service. App resume, sticky restart and boot respect that state.
Connect enables it again. A normal process/service destruction does not record
a user Stop. Concurrent restore and Stop requests are serialized, and the
service checks the saved intent again before opening the connection.

One service-owned retry controller restarts failed tunnel processes with delays
2, 4, 8, 16, 32, then 60 seconds. A connection must remain healthy for at least
60 seconds before the backoff resets. Missing runtime/configuration or permission
errors do not retry indefinitely. Cancellation stops the tunnel and a previous
cancelled loop finishes before a new loop starts. The native secure tunnel
runtime continues to handle its own live transport reconnects. This controller
recovers process exit, I/O failure, startup/readiness failures; it never replays
user commands or creates a new pairing.

The previous dataSync service type was inappropriate for an indefinite remote
command endpoint and cannot start from BOOT_COMPLETED on Android 15+.
The service now declares specialUse with its persistent authenticated command
endpoint use case, notification and Stop control. This is not a camera,
microphone, screen-capture or VPN service and adds no such access.

References:
- https://developer.android.com/develop/background-work/services/fgs/service-types#special-use
- https://developer.android.com/about/versions/15/changes/foreground-service-types
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

Android/OEM background restrictions, force-stop and locked credential storage
can prevent startup. A refused automatic start is shown as
`auto_connect_blocked:<exception class>` without secrets; opening the app retries
when visible. This release does not control Tailscale or guarantee network
availability. Actual device install, Stop persistence, network transitions and
post-reboot tunnel connection must be checked after the signed APK is installed;
host and CI tests alone do not prove those phone behaviors.
