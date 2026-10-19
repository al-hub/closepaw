# Runtime generic Android control — v0.1.65/code66 delivery and acceptance

## v0.1.65/code66 delivered and installed — device UI acceptance blocked by lock (2026-10-19)

- [PR #110](https://github.com/al-hub/closepaw/pull/110) merged as `78395dcdb0637a81224cf14836695fc67de37f19`. Runtime declarative app access/configuration, diagnostic app discovery, bounded complete security traversal and separate filtered presentation are implemented. No app-specific UI branches or coordinates.
- Final PR CI39266632595: 2,582 Android tests, zero failures/errors/skips; lint zero errors/109 warnings/4 info; Python55+8 and debug/native checks passed. Main CI39267769756 and Signed Release39268830338 succeeded. RED CI39264765498 reproduced two expected failures.
- [Signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.65/closepaw-aa-bridge.apk): 116,454,881 bytes; SHA-256 `5af7920fed5c26a708b40744dd5b8a4c66e161b99553ec19fd74ef65ce131bd6`. Runner independently downloaded the public asset and verified manifest0.1.65/code66, original certificate `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`, both native runtimes, checksum and immutable tag/source. Verification artifact12195785243.
- Exactly one update task `bdc61eb6-a286-496f-ad11-c42a1bd2a3fe`: durable status installed/current0.1.65/code66 after reconnection; connection/update permissions preserved, no re-registration. Latest update check up_to_date.
- Same installed APK runtime acceptance PASSED: initial revision0/default4096/50; apply output_limit40 -> revision1 (task427f747e-675e-48f5-9bf4-9e5db4b684a0), independent readback, rollback -> revision2/default4096/50 (task6019e060-309f-475a-a557-f995e3489349), independent restored readback. App grants remain empty. No rebuild for these changes. Termux id task0f5de8c1-5d9e-4d55-b040-5dc68ab62043 succeeded exit0.
- First new-version screen observation returned `device_locked`, action_attempted=false/outcome_unknown=false. No new-version app launch, UI write or camera capture was attempted. This confirms refusal while locked, not unlocked functionality.
- Calculator/discount, Kakao first screen, enabled alarms, synthetic notes draft, public maps search, one camera capture/new-photo save verification, cross-app followups and current-version Gemini/Voice acceptance remain PENDING. Camera is authorized without scene-preparation requests; actual lock must first be released. Do not replay effects with unknown outcomes. P2/Voice and matched end-to-end latency remain OPEN.
- This checkpoint supersedes earlier candidate/delivery notes; historical evidence remains below. Documentation-only followups do not change the immutable released APK source.


User authorized implementation, public source/PR merge, signed release and practical device acceptance on 2026-10-19 KST. Camera capture is authorized without a separate scene-preparation request. No real screen contents, personal app list, photos or messages belong in public evidence.

## Confirmed baseline defects

- On v0.1.64 Kakao/Calculator lookup returned no matches. Home observation hit the adapter's 512-node bound. No calculator input or Kakao read was completed.
- AndroidAppCatalog applies packageAllowed before label matching. Unknown packages default CAUTIOUS, while native control permits NORMAL/launcher. Actual queried/filtered counts were absent; runtime cause for each package is not proven.
- visibility_limited=true was constant metadata, not measured OS visibility evidence.
- Internal security traversal and presentation truncation previously shared a refusal flag.
- RED CI39264765498: 2,573 tests, 2 expected assertion failures (false visibility evidence, output-only refusal). Production changes were not included in that commit.

## Architecture

Keep existing three generic primitives and MCP envelope. android.screen.observe optionally filters text/description/resource ID AFTER full security traversal. Only disclosed node IDs can be used. Presentation omission does not claim missing nodes are safe, and never weakens full fingerprint/secure-window/password/sensitive-node checks. Traversal is bounded at runtime (512..8192, default4096; depth64; 2500ms scan budget). Incomplete security traversal still refuses nodes and Back.

Discovery returns matched app identities with separate launch/read/interaction decisions. It does not label a policy-filtered result as an OS failure. Built-in BLOCKED and protected system/permission/installer/own-app boundaries remain. Unknown apps are launch-only until an explicitly authorized runtime read/interact grant. Grants are exact package identities; no app UI branches or coordinates.

android.control.settings reads configuration/revision. android.control.configure accepts full declarative config and an expected revision, or rolls back one previous config. Single-record synchronous SharedPreferences commit is atomic for revision/config/previous; parsing validates bounds/keys/packages/access. No downloaded code. Changes invalidate current observation and selected apps. Frame revision is rechecked even for Home/launch, along with all current native guards. Generic switch remains local, separate and OFF by default.

API33+ package launching uses getLaunchIntentSenderForPackage; selected target and policy are still required. Transient post-action root/window races allow at most2 read-only re-observations; no effect replay. Existing single-use observations, task ledger, shared mutex, and dedicated unknown-send guards remain.

## Practical acceptance after verified signed delivery/install

All are PENDING until actual results are recorded. Available installed apps/permissions are checked, not assumed.

1. Calculator: explicit simple1+6=7 and practical39000*(1-0.2)=31200; confirm calculator's actual expression/result.
2. Kakao: launch and read only first screen; do not enter chats/send messages.
3. Clock: read enabled alarm times; do not add/change alarms.
4. Notes: create a clearly synthetic shopping-list draft; preserve existing nonempty drafts.
5. Maps: search a public place (Suwon Station), confirm selected result; no navigation/location permission changes.
6. Camera: launch ordinary photo mode, trigger exactly one capture, verify newly saved photo through current app evidence. No preparation request; unknown capture outcome is observed, never replayed. Do not publish image.
7. Cross-app follow-up: calculator -> clock -> camera using generic primitives without app-specific code or rebuild. Actual Voice return requires matching user-visible conversation evidence, not merely task success.
8. Runtime apply/rollback on same installed APK; old observation and old app selections cannot execute after revision changes.
9. Existing Gemini read/model/question and Termux transport regression; no blind send replay.

Do not equate CI/release/install with device acceptance. Do not claim universal app support, locked camera/Gemini operation, latency improvement or new-photo storage based only on a native acknowledgement.

## Primary references

- https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentSenderForPackage(java.lang.String)
- https://developer.android.com/training/package-visibility/declaring
- https://developer.android.com/training/testing/other-components/ui-automator
- https://android.googlesource.com/platform/frameworks/support/+/dd97834aa54671ee1f56d65fa46668b4ffeb57e8/test/uiautomator/uiautomator/src/main/java/androidx/test/uiautomator/UiObject2.java
- https://github.com/droidrun/mobilerun-portal/blob/main/docs/local-api.md

Mobilerun is architecture reference only; no dependency, code copy, ADB runtime or secondary LLM added.
