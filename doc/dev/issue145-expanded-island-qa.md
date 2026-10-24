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
