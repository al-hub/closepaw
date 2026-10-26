# #165 integrated follow-up — 2026-10-26

This is an execution record, not an overall acceptance claim. The live contract is
GitHub issue #165; missing mandatory device evidence keeps it OPEN.

## Restored state and independently reviewed merges

Base main: `8e040d07a87a4bd832b9411c68c3892f8cb25413`.

| PR | Exact reviewed HEAD | Security CI | Premium UX UI | Merge SHA |
| --- | --- | --- | --- | --- |
| #173 | e5cfe49580c1deb46758c141ef74636a5d84ab75 | 40322973237 success | 40322973249 success | f6826144aff3d96d17e840857701a52a8eba7ac6 |
| #174 | b4c10012490def1e7b645b0f016731978f683606 | 40324467336 success | 40324467277 success | 23f3d14e70fbda444ca98b90e4a9a587db13b01b |
| #175 | 2e121eeb2c33ffb55132928382dbfeb37cf89b81 | 40324756837 success | 40324756875 success | 64357eb6226c5297cae021dd161143c99b85ba84 |

A separate agent independently reviewed all three complete diffs and their
security-sensitive callers. No introduced findings; changed-file sets disjoint;
`git diff --check` passed. This is independent static agent review, not a GitHub
human collaborator approval or runtime/device test. The previously failing
connector-reviewer and restricted PC-agent paths were not retried.

No intermediate version/APK/install was performed for these merges.

## Integrated application gestures from display images

PR #176 fixes a confirmed rejection in both GenericControl and AndroidControlPort:
display proofs previously authorized only visual_key, so app visual taps,
long-presses and scroll/drag could not use the full-display observation.

The shared VisualActionPolicy preserves app/keyboard scope separation. Display
application targets must fit the application bounds, exclude the IME, and map
entirely into certified pixels. A gesture's enclosing region must be certified.
Native recapture/hash/source/geometry/rotation/editor-generation checks and
display-change checks remain. Existing final root/window ownership, covering
window/path, runtime interact, session epoch, X and cancellation checks remain.
The operation discovery description is updated to match this behavior.

Two meaningful regressions were added; the obsolete display-key-only test was
updated. A different agent independently reviewed this implementation and found
no blocking findings. Exact initial functional tree:
`fb1fcc342bae188b5dbabb180cee3dfccdac7e94`.

## Validation and delivery checkpoint

- Local release/trust-boundary Python unittest suite: 46 PASS.
- Local `git diff --check`: PASS.
- Local Gradle bootstrap: BLOCKED, `services.gradle.org` network unreachable.
  There is no local JVM/Android PASS claim. GitHub Security/UI CI is required.
- Local pytest suites: NOT_RUN, pytest is absent. Required suites run in CI.
- Change-impact plan selected complete JVM coverage and security/cancellation/
  geometry/lifecycle/performance groups. Mandatory CI was not narrowed.
- Release candidate: v0.1.94 / code95, not yet a delivery claim.
- Independently queried initial phone: v0.1.93 / code94.
- Initial control session: ended, epoch12. No old session silently resumed.

## Scope distinctions retained

The official Termux accessibility InputConnection insertion and separately
authorized terminal submission/output checks already exist. Older handoff
statements calling Termux canvas entirely unimplemented are historical.

Semantic keyboard layout switching exists. The visual-only key route supports
character/space/backspace; it does not authorize visual-only layout/submission
keys or an unobserved bulk visual keyboard planner. Direct native text insertion,
semantic keyboard input and explicitly observed individual visual keys are
distinct paths.

Unreadable/null Android clipboard remains a refusal before staging; it is not an
empty-clipboard proof. No default-IME replacement, privilege workaround, blind
clipboard overwrite, automatic restore or UNKNOWN-effect replay is authorized.

TalkBack, fold-specific and long-duration reliability remain in their original
follow-up issues. Physical OEM rotation, X, IME composition and real image
delivery cannot be upgraded from static review or JVM/UI test success alone.

## Final checkpoint — policy decision required

PR #176 exact HEAD `cc637d8b09d75dc5f68075a204194dd1f3866166`:

- Security CI **PASS**, run 40330064211, job 121597290670. JVM tests,
  lint, debug build and acceptance-report gate completed successfully.
- Premium UX UI **PASS**, run 40330064181, job 121597419063. Emulator log
  confirms all 14 instrumented tests finished; workflow success separately read.
- The local impact inventory declares 330 classes / 2,899 tests. This is the
  expected inventory, not an independent count of downloaded raw JVM XML.
- PR unsigned-release was intentionally skipped. This is not a release PASS.

**Mandatory common-read policy remains BLOCKED.** Samsung Internet and HoneyBoard
are absent from bundled NORMAL tiers; unlisted packages default to CAUTIOUS and
need an existing explicit read/interact grant. This is a policy refusal, not proof
that Android cannot capture those windows.

A package-independent contextual-read proposal was reviewed: current OS-selected
browser/IME, exact owner, active session and safe editor, read only, all explicit
deny/CAUTIOUS/BLOCKED overrides retained. Independent review correctly rejected
automatic admission of otherwise unclassified apps under the current contract:
OS selection establishes identity/ownership, not trusted/non-sensitive source
classification. Public RoleManager.isRoleHeld also checks only the calling app;
privileged role-holder APIs must not be assumed available. No contextual-admission
code, package exception or device configuration change was made.

PR #176 remains OPEN and unmerged because its prepared version bump would trigger
production delivery before this mandatory development gap is resolved. User
instructions require one final integrated APK after all development/automatic
validation; an intermediate release was therefore not published. The old review
execution-path problem is resolved and is not the present blocker.

| Gate | Final result |
| --- | --- |
| #173/#174/#175 review, CI, merge | PASS |
| #176 implementation, static review, Security/UI CI | PASS |
| Generic read without individual grants for unclassified browser/IME | BLOCKED |
| #176 merge / final main CI / signed release / public APK verification | NOT_TESTED — held for mandatory policy gap |
| New APK installation and new physical acceptance | NOT_TESTED — no new APK |
| Actual two-app display/IME image delivered to ChatGPT | NOT_TESTED on candidate |
| Physical mixed input, Termux command/output, rotation, X and security matrix | NOT_TESTED on candidate; original-version evidence not upgraded |
| Clipboard when Android cannot establish readable provenance | BLOCKED; no bypass |
| #165 completion | OPEN |

Minimum decision for continuation: define whether otherwise unclassified current
OS-selected browser/IME may receive read-only eligibility during an explicit
session, or require a separately verifiable trust classifier before admission.
The former changes the current unknown-fail-closed contract and needs explicit
policy authorization; the latter needs concrete trust criteria, not a guessed
package list. Neither option grants interact, changes default apps, permits
sensitive content, or removes FLAG_SECURE/ownership/pixel/freshness/X checks.
