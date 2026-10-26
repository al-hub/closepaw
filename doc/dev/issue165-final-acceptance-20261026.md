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

## Delivered candidate — confirmed release evidence

- PR169 final HEAD `1a438eabe43a7a4a8645762c4157aef31035e845`.
  Independent security-focused Codex code review completed/no findings/unresolved=false:
  https://github.com/al-hub/closepaw/pull/169#issuecomment-6405958743 .
  This is not a separate formal Security Review certification.
- PR Security40252370625/UI40252370635 PASS; raw XML independently summed:
  JVM2873, Android14, failures/errors/skips0, UI PNG8.
  JVM ZIP12621308198 SHA25664976bffe09f521bcf9958631b2a6a6a60af6a6ebb35509eb6512e06948e8c71;
  UI ZIP12620969304 SHA2566ac8fd87c0a8d4cc2d81e206fa9e8b1df32619afe2284ad9332670f02b406ff2.
- Squash main/tag source `f9961d538c9639250d620da5168c8b880695c150`.
  Main and PR tree independently match `7813a99f3df9c795879bb5f03726798033b9943a`.
  Main Security40253048486 (optimized unsigned release included), UI40253048457 PASS.
  The XML counts above are same-tree PR evidence, not a claim to have re-summed main XML.
- Signed Release40253728307 PASS; v0.1.90/code91, package ai.closepaw.
  https://github.com/al-hub/closepaw/releases/tag/v0.1.90 .
  Public APK116784123bytes SHA256
  `0819d27e3b15fa55dca1cea6eaaca6d2e8c628acd7a213c0fd9896a914ccce16`.
- Public verification ZIP12621960639 digest independently recomputed:
  `babd1efc31e53783673d2e458fc38d8ef28556b5a682dcd3c4b32071be836da2`.
  Extracted verification.txt/signature.txt/manifest.txt checked: exact source/tag,
  version/code/package, apksigner v3 PASS, original certificate SHA256
  `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`.
  Annotated tag6f39f747a8468197e4d76f3dcb6416884a1cfb30 resolves to the same source.
- A separate host anonymously downloaded the public APK; locally recomputed hash
  and116784123byte size match the asset digest and verified APK; ZIP CRC passes.
  Local Android SDK/apksigner was not available: signature verification is the
  official pipeline apksigner report for the independently hash-matched bytes,
  not a claim of a second local apksigner execution.
- Exact self_update.check available_version0.1.90 used once for installation,
  taskec23e8ec-643e-49d9-8cce-780d697f5fdc. installing was confirmed; final durable
  installed/current and independent app response must be checked after reconnection.

## Installed candidate acceptance and final disposition

Durable self_update.status installed/current0.1.90/code91 and an independent
get_status reachable0.1.90/code91 confirmed after the one installation request.
New session09a192c9-9752-439b-837c-b1c93ea67978/epoch1; original config20 preserved,
only Chrome temporarily allowed in21; CAS22 restored original contents with an
independent equality=true settings query. No default-IME/OS permission changes.

| Installed0.1.90 test | Evidence | Verdict |
|---|---|---|
| Direct mixed-text insertion | a25e7b42-0218-43ad-8d9c-0307e13dba39, explicit direct/insert into the known synthetic Chrome draft; exact full text and caret32 verified; total1017ms/native715/post301 | PASS; Enter/search0 |
| Native long-press | bd5c3544-7118-491c-bd30-94d0eec7ae68 first refused screen_changed/attempted=false; fresh observation ad34caab-c9de-48ad-9989-824f3469bf95 succeeded and exposed only owned Paste/Select-All toolbar | PASS on fresh second observation; no menu item selected |
| Original empty-popup reproduction | 6a692454-a30d-4810-b271-f7b4374a1681 one Back produced linked popup3346/foreground3203; complete security scan, navigation_only=true, nodes0, actionable/node_tap=false, Home/open_app only | PASS; old screen_unreadable dead-end reproduced and restricted safely |
| Empty-popup forbidden Back | dc4f6e5d-7b0e-4525-b9cb-f6e7e45b5814 surface_navigation_only, attempted=false, unknown=false | PASS safety refusal |
| Empty-popup image request | Fresh include_image=true observation remained navigation_only, visual absent, actual image blocks0 | PASS privacy/capability boundary |
| Guarded exit from empty popup | 8461bb99-8faf-4430-a7d6-69861860975a exact catalog-selected Termux launch, post foreground com.termux/app_result_verified=true/unknown=false, total2199ms | PASS; no underlying Chrome tap |
| Missing terminal node ID | 105b0637-c04a-440f-a29b-bdffd331fc20 node_id_required/attempted=false/unknown=false | PASS refusal classification |
| Consumed observation replay | 3634f839-c6d2-42d4-8c61-6c5289f4d240 corrected node with same consumed observation returns observation_required/attempted=false/unknown=false | PASS no replay |
| Undisclosed terminal node ID | 7c2a2029-116c-4457-913d-57d13d04eba9 target_not_in_observation/attempted=false/unknown=false | PASS refusal classification |
| Chrome initial launch transient post window race | f233e72f-284a-434b-8eb9-6770dbe487c2 UNKNOWN; no replay, next fresh observation independently confirmed Chrome | UNKNOWN preserved; recovery observation PASS |

Termux official InputConnection remains advertised on actual installed90; its
successful `123` insertion was measured earlier on89 in this same work. It was not
repeated into the now nonempty terminal. Calculator visual tap/native+visual scroll
are historical89 evidence, not newly labelled90 physical PASS. Clipboard and Samsung
semantic-key refusals are unchanged and were not pointlessly repeated. No new
physical X/rotation/alternate-IME/visual-long-press/drag/A-B-C speed comparison PASS
is claimed. Performance numbers above are single samples, not a speedup result.

Final status: **#165 OPEN, Close not permitted**. PR169/release/install and its
specific changed behavior are complete. Actual Samsung IME key identity and clipboard
read/provenance compatibility are still unimplemented for this phone's observed
conditions; whole two-app A/B/C mixed-input and the remaining physical gates are
NOT_TESTED/BLOCKED. Existing engine is reused, not rewritten. See bounded task
metadata in [evidence](evidence/issue165/final-20261026.json).

Next worker must reuse the exact successful Termux/native/visual/delivery evidence,
not reimplement capture or speculate about missing terminal binding. Design a scoped
IME-window visual/semantic proof (selected IME ownership, fresh secure snapshot,
geometry/occlusion, verified field, key purpose and exact effect/no replay) before
admitting nonmarked/nonclickable Samsung key views. Clipboard requires a supported
readable ownership path across staging and Paste; a null/temporary foreground detour
alone cannot establish that. Do not weaken either guard to close the issue. Finish
mixed input, alternate IME/rotation/X and matched performance only after those paths
are supportable, then reassess Close. No additional release is needed merely to
repeat the already accepted diagnostic/popup checks.

Operation cleanup: the AI-created session is ended after acceptance. Termux retains
unsubmitted123; Chrome's synthetic draft has only the additional ` 가A1!`. No terminal
Enter, message send, search, clipboard write or deletion occurred in this work.


## Subsequent v0.1.91 continuation

[IME compatibility delivery and disposition](issue165-ime-compatibility-20261026.md) records PR170, verified v0.1.91/code92 delivery/install, and actual device_locked acceptance refusals. The above0.1.89/0.1.90 evidence retains its original source/version. #165 remains OPEN; a released minimal visual-key implementation is not actual Samsung input success.
