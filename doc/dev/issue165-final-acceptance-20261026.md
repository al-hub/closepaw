# #165 final acceptance work — 2026-10-26

## Scope and immutable baseline

Source/tag `be573fb89cf8ac6017032f8ffd2f73b9c96a443f`, installed 0.1.89/code90
independently confirmed through ClosePaw get_status. PR166 is merged/CLOSED;
PR168 supplies this baseline. #165 remains the single OPEN acceptance owner.
Read AI.md, AGENTS.md, WORKS_HANDOFF, validation-ready, QA/release runbooks,
all #165 comments and PR166 review/merge history before testing.

Existing image-block delivery, release identity, CI and installation evidence in
[#165](https://github.com/al-hub/closepaw/issues/165#issuecomment-6404701826)
is reused with its original source/version. No new PASS is inferred from old CI.
Private images and screen/clipboard contents are not public evidence artifacts.

## Actual installed-baseline acceptance

New authorized session `6bded934-cef9-4ad9-b5ce-146390b64db9`, epoch7.
Configuration18 was preserved; temporary test configuration19 added only the
requested HoneyBoard/Chrome/Samsung Internet access. CAS apply20 restored the
original complete configuration, and an independent settings query confirmed
content equality. Default IME, Android permissions and service state unchanged.
No terminal Enter, command execution, clipboard writes, search or message submission.

| Test | Actual evidence | Verdict |
|---|---|---|
| Termux official InputConnection insert | Fresh node7/terminal_input_supported, explicit terminal_authorized, one `123` commit; task485c4d7f-c8a0-4feb-9c80-57fa4754ae7e reports exact echo/app_result_verified, attempted+acknowledged, unknown=false | PASS |
| Application-window visual tap | Actual443x960 calculator image read, history button image rectangle selected; e3a91870-071f-4b17-8eb0-9f5e3e9566d7 gesture_completed and history ListView in post | PASS |
| Accessibility scroll up | Calculator history ListView, d2011125-3b97-4fc1-9f79-25cca416d0e3, native acknowledgement and changed visible content | PASS |
| Visual scroll | Fresh calculator image, path confined to observed history list;106f148c-d478-4594-9014-a3f37e7f1d23 gesture_completed and changed content | PASS for gesture/screen transition; no exact endpoint assertion |
| Native long press | Chrome synthetic draft field, e2209e44-27f7-4ffe-a16f-a172024a7c72, native acknowledgement, owned floating toolbar with Paste/Select All observed | PASS for menu appearance; no menu item selected |
| Samsung keyboard discovery with allowed app access | Termux scan128 visited/120 visible, clickable0/text_entry_keys0/recognized0; Chrome target_verified=true, text_entry_keys0/recognized0, literal labels duplicated on nonclickable ViewGroup/TextView | BLOCKED for current semantic-key contract |
| Clipboard | Same baseline historical clipboard_empty_or_read_restricted, zero writes; refusal/source unchanged | REUSED_EVIDENCE/BLOCKED, no new attempt |
| Chrome direct input | Baseline prior exact direct insertion/effect evidence | REUSED_EVIDENCE; not a new attempt |
| Calculator initial app switch | Native acknowledged, post secure_window_probe_failed, then separate fresh observe proved calculator foreground; no replay | Original UNKNOWN preserved, later foreground independently confirmed |
| Chrome toolbar dismissal | One Back05cd2f50-360b-43cc-870f-ab73e274cb52, post_observation_unavailable; fresh observation selects remaining OS-linked popup3340 with no exposed nodes and screen_unreadable | UNKNOWN; no repeated Back/click-through |
| Visual long press/drag, alternate IME, rotation, full mixed-key/clipboard E2E, new physical X | No sufficient actual evidence | NOT_TESTED |

Single-sample Android monotonic timings (not Voice/network E2E or statistical comparison):
Termux951ms(native572/post377), visual tap1387ms, native scroll1035ms,
visual scroll1631ms, native long press4402ms.
Termux retains unsubmitted `123`. Chrome retains the earlier synthetic draft.
Calculator formula/history were not deleted. A post-Back unreadable owned Chrome
popup remains a separate limitation; never target underlying obscured app nodes.

## Confirmed defects and minimal correction

1. `GenericControlActionPolicy` compared revision19 with a denied frame's default
   revision0 before inspecting its actual native refusal. Preserve the refusal
   first, then compare revisions only for admitted frames. Both outcomes refuse;
   no security check, dispatch, stale-frame or replay policy is relaxed.
2. All node actions require a disclosed `node_id`, but the remote descriptor
   omitted input/terminal_input/terminal_submit/focus/key from its requirement text.
   Missing IDs and undisclosed IDs collapsed into `target_unavailable`. Return
   `node_id_required` / `target_not_in_observation`, preserving consumption and
   zero effects. Disabled/password nodes still return `target_unavailable`.

The old Termux request payload was not retained in the handoff. Do not invent
whether its ID was missing or stale. The old reason originates at node admission,
not the terminal binding path. This work's successful official API insertion
rules out an unconditional native InputConnection failure on this phone.

3. A completely scanned OS-linked empty popup stranded the observation path with
   screen_unreadable. Retain its exact foreground/owned-popup identity in an
   explicit navigation_only frame. Only existing guarded Home/exact-launch
   navigation is available; no Back, image, node actions or underlying targets.
   Protected/blocked/secure/incomplete/unknown-root frames cannot use this path.

Tests-first PR169 regression cases cover reason priority, actual revision changes,
missing/undisclosed IDs, no native effect, no UNKNOWN and no observation replay.
Empty-popup tests cover advertised exit-only capabilities, guarded navigation and
refusal of Back, gestures and all underlying node writes.
Candidate version0.1.90/code91 is reserved for these executable changes.
Exact CI/review/release/install outcomes must be appended only after confirmation.

Tests-first commit `bfd1d905caa4e6fbd6014fb68746da33f02164ed`, Security
[40251729397](https://github.com/al-hub/closepaw/actions/runs/40251729397):
2871 JVM tests ran, exactly the two new regression tests failed as expected.
Job121344899718 logs prove native secure_window_changed was masked by
configuration_revision_changed and missing node_id returned target_unavailable.
This is RED evidence, never a passing candidate or release gate.

## Remaining compatibility work and close gate

- Samsung IME needs a new proven key identity path, not removal of isTextEntryKey
  or clickable checks. A generic separately captured/authorized IME-window visual
  proof plus per-key purpose/effect validation is a possible design, not an
  implemented feature. App-window images must never target the IME window.
- Clipboard needs supported readable provenance at staging AND immediately before
  Paste. A background service null cannot prove an empty clipboard. A temporary
  foreground staging UI alone does not prove readability after returning to the
  target app. Default-IME replacement, privileged permissions and unchecked writes
  are not authorized solutions.
- The candidate empty linked popup recovery needs exact-source CI/review and
  installed-device acceptance. It never selects underlying obscured app nodes.
- Finish two-app A/B/C mixed text, separate terminal command approval/output,
  rotation/alternate IME, X/no-replay and matched performance acceptance after
  compatibility work. The present functional blockers prevent #165 Close.

Primary Android contracts:
- https://developer.android.com/about/versions/10/privacy/changes#clipboard-data
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#isTextEntryKey()
- https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection
