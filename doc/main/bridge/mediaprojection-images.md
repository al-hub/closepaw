# MediaProjection images over MCP (#165)

Use `android.screen.observe` with `include_image=true` and `image_scope=display`.
The operation remains read-only and never opens or approves an Android consent dialog.

The device user first selects **Settings → Share screen images with ChatGPT** and
approves Android's screen-capture dialog. A separately approved, explicitly active
ClosePaw control session and native app read policy are also required. Granting
projection consent alone does not expose any images.

When the projection service has an active image lease, explicit display observations
use it as the bitmap source. Without a projection lease, the existing accessibility
display source remains available under its existing policy. If a selected projection
fails, that invocation refuses the image; it never falls back to another source.
App and keyboard image scopes continue to use isolated accessibility screenshots.

The projection is not an ownership proof. AndroidControlPort still performs its
complete accessibility security scan, package/IME/editor checks, window ownership,
geometry and intervening-event checks. WindowVisualCapture compares pixels with
independent isolated-window screenshots and masks every uncertified pixel before
hashing, scaling or JPEG encoding. Single-app projection geometry that does not
match the verified display is refused. Native secure-window and authentication
restrictions remain in force.

Capture permits at most 8 million pixels, waits at most 1.5 seconds for a fresh frame,
and rejects unknown, future or more than 1 second old monotonic frame timestamps.
Stalled buffers are drained within that same deadline. Output is a JPEG with a maximum
960-pixel edge and 1.5 MB encoded bytes. The two-entry in-memory visual cache contains
only verified/masked images, never raw projection frames. Raw bitmaps are recycled.
No image bytes or base64 are stored in the operation ledger or logs.

McpJsonRpcHandler attaches the image as an MCP `content` image block and reports
`image_included` for the actual response. Query and get_task delivery both recheck
control consent/session epoch, projection consent generation, revocation and expiry,
including after base64 conversion. Projection images expire within 10 seconds of
the source frame, independently of the 60-second accessibility observation lifetime.
Revoked/expired cache entries are evicted on access and cannot revive when a session
resumes. Visual gesture dispatch also refuses revoked/expired source proofs.

Device acceptance still requires an Android 14+ device: approve and deny OS consent,
observe a permitted app, revoke during capture/delivery, pause/end control, lock the
device, change geometry, and inspect query/get_task responses for withheld images.
