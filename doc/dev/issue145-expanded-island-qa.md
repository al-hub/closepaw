# #145 expanded island rendering evidence

The existing UI suite rendered the legacy offscreen status label and tested real
island lifecycle/dragging, but did not capture the expanded Compose progress panel.
Add a test-only case to the existing ControlOverlayWindowTest suite: use the real
AgentService/WindowManager island, short-tap its attached root, drive synthetic
waiting/completed/failed/interrupted Gemini progress, assert expansion/attachment/
active-session and bounded settled geometry, and export four cropped screenshots
from the actual emulator display. End the test-created session and assert cleanup.
The existing four light/dark/font renders remain mandatory; CI now requires eight
PNG outputs. Independent screenshot review is needed to judge actual visible text;
checking source strings or the detailVisible flag alone is not rendering acceptance.

No production Kotlin, operation, security policy, version, APK or phone state changes.
Do not publish/reinstall an unchanged app. Installed Galaxy remains0.1.83/code84,
immutable signed source430f42290d2f8bdc3d10c0c451d677ae7f6f6407.

Results: NOT_TESTED until exact-source CI and independent artifact review are recorded.
This emulator evidence must not be labeled Galaxy physical rendering, an external
Gemini roundtrip, or failure/cancellation of an actual Gemini generation. Prior
one-shot roundtrip PASS and earlier FAIL/unknown samples remain unchanged. #145
and #147 stay OPEN pending their remaining requirements; #146/#150 remain CLOSED.


## Verified results (2026-10-24)

[PR157](https://github.com/al-hub/closepaw/pull/157) exact head7d5a7eafde46dbaebbb8d356410509f66de78143:
Security40100437644 and UI40100437720 success. Independently downloaded raw XML:
JVM2743, UI11, failures/errors/skips0; eight PNGs. The added named test is present.
PR UI artifact12562905572 SHA256d8efa81848a32b21f0a4f1bff2b21de5126ac2f88959179566a7864d5d44d7d3;
PR JVM XML12561789418 SHA256236f9b2f31b78df68050684e9897f4e438ecf1d736f45ef92aad5a2b74e85804.

Independent visual review of the four actual overlay crops found all six headings
and their values visible: goal/target/completed/current/next/status. Waiting preserves
submission/completion uncertainty; completed shows verified scope and next:none;
failed/interrupted show no automatic resend, and interrupted explicitly says Gemini
generation cancellation is unconfirmed. The actual short tap expands the island;
its height changes for the different wrapped details and cleanup detaches it.
**PASS: emulator actual overlay rendering for these four synthetic states.** This
is neither Galaxy physical acceptance nor actual Gemini failure/cancellation execution.

Merged main sourcee6b19eedd3fee6a5ea68e1e656352d6564b0eb6f:
Security40100977135 and UI40100977086 success. Independently parsed main UI XML11,
failure/error/skip0, PNG8. main UI artifact12562747930 SHA256
a30580f0ac95096301a92080c8e9d4ddb560878886b034a424c0b992cfa00953.

Automatic Signed Release40101449801 **FAILED**, job120894270407 at
'Validate main and CI; classify release state': 'Immutable tag exists at another
commit; manual review required'. The tag guard runs before existing-release asset
skip, so a same-version test-only merge is rejected. This is not a signed-release
PASS or a missing new APK. No production app/version changed; v0.1.83 tag/APK/source
remain immutable. No retry, key change, tag move, republish or phone reinstall was
performed. Record this explicitly rather than masking the workflow failure or
increasing the app version for test-only code.

The newly passed Gemini device sample and original failed/unknown samples retain
separate statuses. No phone UI session/question or historical physical test was
repeated in this work. #145 remains OPEN: Galaxy expanded-view observation and
intermittent target_changed diagnosis remain incomplete. #147 performance remains
OPEN/unproven; #146/#150 stay CLOSED. Prefer a single expanded-view observation
during the next normal authorized use over repeating completed roundtrip/X/rotation
checks solely to obtain the panel screenshot.
