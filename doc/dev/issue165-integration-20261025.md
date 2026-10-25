# #165 integration continuation — 2026-10-25

This checkpoint supersedes the implementation-absence statements in earlier audit
notes, while preserving their historical source/CI evidence. It is not complete
#165 acceptance. Main/version/APK/phone configuration remain unchanged.

## Reused sources and actual execution path

- Base main `d2c6c449fce684e2db4f8763167a46c1a1127d33`.
- Existing PR166 HEAD `2d4302473e7c1c5f1a841f21c8ec640bff163994` and all of its commits audited.
  Both Security40185267096 and UI40185267206 failed compilation because `onMain`
  accepted only ActionResult while the new read-only Paste probe returns Boolean.
  The helper is now generic; no Android action/permission check was removed.
- Reuse PR167 HEAD `8fb2205d8fe27a058cce8681ae86cb2526845660` as a merge parent:
  current OS-selected IME ownership/observation, explicit session/epoch guards,
  native focus, selection-preserving direct input and horizontal native scroll.
  PR167's prior independent review covers only its original constrained source.
- `android.ui.action(action=input)` now routes through `TypeExecutor`'s shared
  evidence-based dispatch, retaining native-boundary security, single-consumption,
  exact result verification and no attempted/unknown-result fallback. Local
  `mobile_action(type)` retains its existing direct path, with the new focused
  cancellation check and exact direct-result validation. It does not yet expose
  the new clipboard/keyboard options through that local-agent schema.
- Explicit `method=direct|clipboard|keyboard`; auto selects advertised direct,
  then consented Paste, otherwise observed IME keys. No automatic Enter.

## Clipboard policy and limitations

`clipboard_consent=true` AND `clipboard_replace_authorized=true` are required for
native Paste. They authorize reading clipboard provenance, replacing its contents
and leaving the staged value there. General session consent alone is insufficient.

Null clipboard / denied read / unsupported item types cause zero clipboard writes.
An actual nonnull plain-text read must succeed before staging. An unpredictable
operation marker and exact text must be readable after staging and immediately
before Paste. Then a newly probed full tree must match app/window/fingerprint,
configuration, input field, selection, foreground, explicit session ID and epoch.
Revocation/cancellation stops execution; stage/dispatch uncertainty never replays.

There is **no automatic restoration**: Android supplies no atomic compare/restore,
so restoring after a read could overwrite another app's concurrent clipboard.
No old clipboard value, input payload, exception message or marker is logged.
A background accessibility service often cannot establish read access; that case
remains BLOCKED without a supported local/foreground clipboard path. No permission,
IME, foreground-focus or accessibility workaround is applied. Cross-process changes
between the last provenance check and Android consuming Paste are an OS-level
race; exact post-observation detects mismatches but cannot undo a dispatched effect.
This residual risk needs independent security review before production acceptance.

## Keyboard path

- Current default/authorized IME and actual observed semantic key nodes only.
  No fixed positions or coordinate guesses; native bounds/security/occlusion/epoch
  are freshly checked by the reused key executor.
- A bounded text request preflights all required labels in the observed layout,
  then uses the same one-shot action/observation path per key. Maximum20 keys and
  existing20-action/180s chain limits remain. Operation timeout120s bounds probes.
- Requires an end caret and unchanged app/input/IME/geometry/configuration.
  Unknown/duplicate/missing keys refuse before the first effect when possible;
  a changed layout or unexpected intermediate result stops remaining keys.
- Two-set Hangul composition predicts intermediate text, including compound vowels
  and finals. It is a prediction, never proof of IME composing state. Each result
  must match actual observed text; final requested text and caret must also match.
  Autocorrection, committed prior syllables and differing IMEs can stop safely.
- Current-layout literal keys, spaces and known single-key delete/submit/layout
  controls exist via the key API. Automatic layout switching and general composition
  recovery are not completed. Enter has separate submit authorization and never
  claims command execution from a gesture acknowledgment alone.
- Opaque/noneditable Termux canvas remains refused; it cannot be treated as a
  trustworthy editable field or authenticated prompt from a cursor alone.

## Verification and outstanding release gates

- Local Python script regression43 PASS; `git diff --check` PASS.
- Local JVM unavailable: Gradle8.11.1 download failed `Network is unreachable`.
  This is an environment blocker, not a Kotlin test PASS/FAIL result.
- Authored JVM coverage: clipboard stage failure/revocation/cancellation, no replay,
  shared TypeExecutor channel selection, observed keyboard text, incorrect partial
  Hangul stop, missing-key no-partial preflight and all11172 Hangul syllable roundtrip.
- Added native emulator clipboard provenance/competing change and real EditText
  direct/Paste mixed-text tests to the existing required UI workflow. These are
  native primitive tests, not external-app remote-control acceptance.
- Existing first integrated candidate `27a90a82120e2815b58368646d2338fecc2f65ac`
  exact-SHA Security40186221322/UI40186221325 started; results must be checked.
  Later source changes require their own exact-SHA CI; do not inherit that status.
- New independent code/security review, general layout switching, opaque Termux
  input, native remote E2E across2 external apps, physical X, matched A/B/C metrics,
  main merge, new version, existing-key signing, public APK verification, installation
  and installed-device acceptance remain NOT_TESTED/incomplete.
- #165 remains OPEN and PR166 DRAFT. PR167 branch is unmodified; #152/#161 code,
  runtime settings, signing material, phone session and main were not changed.
