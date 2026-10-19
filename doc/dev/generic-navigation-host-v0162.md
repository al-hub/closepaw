# Composerless Gemini navigation host — v0.1.62 candidate

Status: RED regression in progress; installed baseline0.1.61/code62.

## Direct device evidence (2026-10-19 KST)

After the user unlocked the phone, generic Gemini list/select/launch succeeded. Generic sidebar open/close changed the actual screen (device totals1,098ms/1,038ms).
Registered read on the open sidebar still returned target_not_foreground with:
activity_anchor_present=true, activity_anchor_visible=true, sidebar_pane_visible=false, chat_pane_visible=false, traversal_complete=true, visited_nodes176, max_depth33, protected_input=false, identity_verified=false.
The previous activity-anchor visibility hypothesis is ruled out for this sample. v0.1.61 is not recorded as a real sidebar-read fix.

After closing the sidebar, the existing text-free gemini.ui.inspect verified the current root and returned actual native structural metadata: the exact activity anchor and assistant_robin_side_nav_host_overlapping_v2 were both visible in the same active/focused application root. Its content and chat pane markers were visible on the composer screen. This confirms the shared navigation host identifier on the installed UI; host visibility on the open sidebar still requires validation after correction.

## Narrow correction

Recognize the exact native navigation host as another visible Gemini pane witness, paired with the exact structural activity anchor and exact Google host package. Retain active/focused application root, complete bounded traversal, protected-input checks and all downstream screenshot/app/security gates. Do not accept anchor-only, host-only, hidden-host, other-package or generic Search fixtures. Expose navigation_host_visible among text-free read diagnostics. No model-specific handler, coordinate action or question/write relaxation.

First add regression tests against unchanged implementation; then full CI/lint, signed release/public APK verification, one remote update and available device checks. Model selection is still unaccepted: the earlier model-title native click had no visible effect and exposed no expand/collapse; do not automatically repeat it. P2/Voice remains open. No private screen/account/conversation content is published.

RED evidence: Security CI39209129381/job117918368588 compiled and ran2,548 tests with exactly1 expected assertion failure: actualNavigationHostCanIdentifyTheComposerlessSidebar. Anchor-only/host-only/hidden-host/foreign-package rejection checks passed.
