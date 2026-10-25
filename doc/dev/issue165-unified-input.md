# #165 guarded input foundation — implementation checkpoint

Baseline source: d2c6c449fce684e2db4f8763167a46c1a1127d33 (0.1.87/code88).
This branch is a partial implementation, **not acceptance or a release authorization**.
No version bump, main merge, APK publication/installation or phone mutation at this checkpoint.

## Existing behavior reused

GenericControl single-use expiring observations, ControlSession epoch/X interruption,
UI mutex, fresh full security probes (400 ms spacing unchanged), app permissions,
secure/authentication guards, current native semantic target verification,
post-observation and UNKNOWN/no-replay. Existing native click, long-click, node tap,
up/down scrolling and empty-field `type` remain unchanged in scope.

## New code

- `input`: explicit ACTIVE session and exact session ID at admission and native boundary.
  Official ACTION_SET_TEXT on an advertised, focused editable field. Empty mode refuses
  existing drafts. Insert mode replaces only the verified current selection and preserves
  both surrounding strings. Known caret, complete <=2000 UTF-16 text and unchanged fresh
  fingerprint/selection are required. No Enter, newline or implicit send.
- Pure method selection chooses advertised direct input before advertised Paste, before
  returning `keyboard_observation_required`. Unsupported explicit direct method never falls
  back. Native refusal/negative acknowledgment/partial result/timeout never causes replay.
- `focus`: advertised ACTION_FOCUS, followed by unique semantic-field focus verification.
  IME-driven field movement is allowed only for unique nonempty resource ID/class identity.
- Horizontal native scrolling is restricted to actually advertised directional actions;
  existing up/down forward/backward compatibility remains. Exact node ID identifies nested
  scrolling regions; any subsequent action needs the returned fresh observation.
- Observation advertises focus, selection, SET_TEXT/PASTE capability and native scroll axes.
- Known token/key/credential/identifier patterns and line/control characters are refused.
  Pattern matching cannot prove arbitrary free-form text non-sensitive. Tests use only
  explicit harmless strings; no sensitive clipboard path is enabled.

## Independent review and security disposition

An independent reviewer identified an unsafe false-empty clipboard assumption, potential
external replacement before Paste, stale security proof after clipboard listeners, legacy
session admission, line separator acceptance, payload sensitivity and resized-field focus
matching. The implementation was revised before publication:

1. **All native clipboard reads/writes/Paste were removed.** `clipboard_consent` is only a
   necessary planning consent, never proof of OS access. The native adapter returns
   `clipboard_read_access_unproven` without touching clipboard or field. This is not PASS
   for clipboard functionality. No conditional restoration across an unsupported CAS race.
2. Explicit-session checks were added at both boundaries for the new actions.
3. U+2028/U+2029 and known sensitive-pattern denial and regressions were added.
4. Focus verification uses unambiguous semantic identity across resize.

Primary Android contract:
https://developer.android.com/reference/android/content/ClipboardManager#hasPrimaryClip()
`hasPrimaryClip()==false` can mean read denial when the caller lacks input focus/default
IME status, rather than an empty clipboard. A background accessibility service must not
infer emptiness, overwrite hidden existing data, use a focus-stealing workaround, or claim
restoration. A supported explicitly foreground/local-consent broker design remains required.

## Evidence status and remaining work

- Tests were authored before the pure policy implementation (local commit 12947c24).
- Local Gradle could not download Gradle8.11.1: Network is unreachable. No local RED/GREEN
  assertion is claimed. GitHub exact-source JVM/lint/debug/UI results must be recorded.
- Native clipboard safety refusal is deliberate; clipboard successful input is BLOCKED.
- IME extension candidate: unique OS TYPE_INPUT_METHOD/default-display window bound to
  DEFAULT_INPUT_METHOD and enabledInputMethodList package/service, fresh security probes
  for both app and IME, complete bounded IME fingerprint, OS isTextEntryKey-only exposure,
  dynamic current key bounds and existing occlusion/region/magnification policy. One key
  per consumed observation, explicit session, separate submit authorization. Unknown
  function glyphs are refused. Exact literal text may verify only in a known editable
  field; layout/composition/custom terminal/Enter effects remain UNKNOWN and are never
  automatically replayed. IME switch/rotation/layout invalidates previous key observations.
  No Samsung coordinates or arbitrary point injection. Actual IME native support and
  Korean composition require installed-device acceptance; they are not asserted PASS.
- Screenshot visual targets, validated image/display transforms, gesture scroll fallback
  and fallback long-touch for non-advertised targets remain NOT_IMPLEMENTED.
- New installed-APK direct input/focus/scroll, two general apps, Termux, same-APK A/B/C
  timing, physical X on changed input path and alternate IME remain NOT_TESTED.
- #152 bridge/token/pairing, #161 timing/experimental branches, PR164 and main are unchanged.
  #153 keyboard requirements remain owned by #165; no duplicate engine/release.

**#165 stays OPEN. This candidate must remain draft and must not merge/release as if the
full issue were implemented.** The existing production APK remains 0.1.87/code88.

## IME follow-up independent review

The reviewer found that OS isTextEntryKey also marks functional keys. The initial
single-codepoint fallback could treat unknown return/send icons as literal characters.
The candidate now permits only one ASCII printable character or Hangul/jamo ranges as
literal; known return/check labels are submit actions, unknown arrow/play/send/emoji
glyphs are refused. This is intentionally a limited supported key vocabulary, not
universal recognition. Functional keys with no trustworthy exposed label are unsupported.

Primary Android key metadata contract:
https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#isTextEntryKey()
The flag proves a keyboard/keypad entry node, not whether its effect is submission.
KeyboardControlTest covers fresh owner/focus, IME/rotation/layout changes, exact literal
postconditions, cancelled gesture no-replay and terminal/composition/Enter UNKNOWN.
Native identity/probe/gesture behavior still needs instrumented/physical evidence.

IME review also found that scanning the app then IME does not freeze input focus. A final
FOCUS_INPUT reacquisition now checks actual app owner/window, editable/focused/visible/
enabled/password/data-sensitive state, resource/class/bounds/text/selection after IME
scan and before gesture dispatch without suspension. KeyboardTargetPolicy failure-injection
covers focus/caret/text/auth/geometry races. Native OEM boundary execution remains untested.
Opaque/non-editable terminal canvases are refused: a missing isPassword flag is not proof
of a non-authentication shell prompt. Thus Termux keys remain BLOCKED until trustworthy
bounded visual/prompt evidence exists; UNKNOWN after an effect is not a safety exemption.

A final review found cancellation may arrive during Binder guards even without a coroutine
suspension. Gesture dispatch now checks continuation activity, the epoch captured before
those guards, session stop/pause, service/consent and app policy INSIDE the continuation
immediately before OS dispatch. GestureDispatchGateTest injects end/restart/pause/cancel
between admission and dispatch gate; these are JVM state-machine tests, not proof of actual
Galaxy dispatch timing. Existing key/tap geometry and full security probes remain required.
