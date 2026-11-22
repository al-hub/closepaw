# Issue #147 — compliant side-by-side Debug distribution and signing gate

Status: **DESIGN/PREFLIGHT ONLY** (2026-11-22 KST). No Google Play Console registration or upload has been confirmed; no Play-distributed Debug APK has been installed. No release, app source, production signing key, Android security setting, or device data change is authorized by this document.

## 2026-11-22 follow-up: Play Console access and Accessibility policy check

**Policy check result: HIGH-RISK / NO APPROVAL.** This is a technical assessment of Google's documented rules and the repository's published behavior, not a review decision issued by Google.

- Exact release-source [README.md](https://github.com/al-hub/closepaw/blob/dd89604a111de9c33d04c21f97ede5c903db5d41/README.md) describes an autonomous, natural-language, cross-app **AI phone-use agent**, including a ReAct loop, agent skills and physical accessibility-driven UI operation. It explicitly calls ClosePaw an `autonomous AI agent` near the safety disclaimer. [AndroidManifest.xml](https://github.com/al-hub/closepaw/blob/dd89604a111de9c33d04c21f97ede5c903db5d41/app/src/main/AndroidManifest.xml) exposes an AccessibilityService; [agent_accessibility_config.xml](https://github.com/al-hub/closepaw/blob/dd89604a111de9c33d04c21f97ede5c903db5d41/app/src/main/res/xml/agent_accessibility_config.xml) enables gesture dispatch and screenshot/window-content access. These are source facts, not a Google policy verdict.
- The current [Google AccessibilityService API policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=ko) prohibits Accessibility API uses allowing apps to autonomously initiate, plan and execute actions or decisions; it distinguishes static human-defined rule-based automation. An `isAccessibilityTool=true` exception is only for verified disability-oriented accessibility tools, not a general assistant/automation app. User initiation and user-visible pause/consent help with safety but alone do **not** establish that the app's dynamic decision-making fits the allowed exception.
- For non-accessibility-tool apps, in-app **prominent disclosure and explicit consent** plus an Accessibility declaration and review are required. These obligations do not override the independent **autonomous-operation prohibition**.
- The public README points to a `ai.closepaw` closed-testing group/link, but those are unverified public pointers; they prove neither a logged-in user's Play Console owner/uploader role nor registration/testing eligibility of `ai.closepaw.capturecandidate`. Private Play Console account/app state could not be read with available authenticated tools. Direct Play listing/testing pages could not be independently opened from public browsing in this check.
- [Official Internal App Sharing](https://support.google.com/googleplay/android-developer/answer/9844679?hl=ko) does support debuggable APKs, repeated version codes and a stable Google-managed test signer, but uploaded artifacts are distinct from test-track releases, and testers must have permission to access the listing. **Technical upload capability is not policy compliance or install approval.**

**Updated decision:** internal app sharing is now a *conditional*, **not recommended-for-execution-yet** path. Do not upload the present AI-agent APK under a claimed policy exemption or alter disclosure to misrepresent its behavior. First obtain accountable Play developer access and a written policy-fit determination for the actual autonomous operations. If that cannot be supported, choose a non-Play compliant developer QA distribution and a dedicated stable QA signing identity (separate from the production signer); investigate the Play Protect classification through Google's official developer guidance/appeal instead of bypassing the OS block. This does not authorize current blocked APK installation by ADB/shell.

**What remains unverified:** authorized Console account/app registration, any actual Google policy review/approval, Google-managed QA test certificate for this package, OS-approved installation, independent Debug connectivity and #147 physical typing. **No Play upload, GitHub Secrets change, stable key provisioning, app-code modification, new Release or phone action performed.**

## Goal and immutable baseline

Keep production `ai.closepaw` at installed `v0.1.144/code145` (source `dd89604a111de9c33d04c21f97ede5c903db5d41`, release APK SHA256 `48a972ae9cbc8076a506e999e8fc0ae2c666f5c49b2b2b244a947a132b437952`). Test side by side only as `ai.closepaw.capturecandidate`, version `0.1.144-capturecandidate/code145`, exact same application source. The QA build [run 44974673644](https://github.com/al-hub/closepaw/actions/runs/44974673644), [artifact 14735360607](https://github.com/al-hub/closepaw/actions/runs/44974673644/artifacts/14735360607), APK SHA256 `0121d6b26c8ef2d996e0a48938fd60326f337aa90b5ba5572d4aa499a9919fb9` is verified **before** distribution, but on-device File Viewer installation was denied by Play Protect.

## Actual historical results (different scopes)

| Date | Evidence | Proven | Not proven |
| --- | --- | --- | --- |
| 2026-11-15 | [#236 same-phone Termux wireless ADB install](https://github.com/al-hub/closepaw/issues/236#issuecomment-6890295537); [Chrome CAPTURE_PASS](https://github.com/al-hub/closepaw/issues/236#issuecomment-6892663628), [Notes CAPTURE_PASS](https://github.com/al-hub/closepaw/issues/236#issuecomment-6893264358) | Separate `0.1.110-capturecandidate/code111` package installed on Galaxy, local QA captures worked without overwriting production | File Viewer/Web install under Play Protect, current build installation, #147 typing sequence |
| 2026-11-15 | [#236 signer verification](https://github.com/al-hub/closepaw/issues/236#issuecomment-6889235865), [candidate-only prior uninstall](https://github.com/al-hub/closepaw/issues/236#issuecomment-6889254310) | Earlier candidate debug signer `99c0925556d761d54eeda6ef2e4948f49ae7473118020d0366ce9494786aab55`; changed signer required candidate-only replacement handling | Stable signing across runner builds |
| 2026-11-11/20 | [#228 production signed-update](https://github.com/al-hub/closepaw/issues/228), [#252 production typing PASS](https://github.com/al-hub/closepaw/issues/252) | Existing stable *production* signing cert, independent public APK verification and production self-update/resume | Side-by-side Debug distribution |
| 2026-11-22 | [#147 QA build](https://github.com/al-hub/closepaw/issues/147#issuecomment-7072776826), [Play Protect install block](https://github.com/al-hub/closepaw/issues/147#issuecomment-7074012328) | Exact source Debug APK built, package and signature verified; File Viewer install blocked | Debug installed/connected/input smoke test |

**Do not attempt shell/ADB installs to override an explicit Play Protect block.** Historical developer ADB success is not an approved workaround for the current security decision. Do not turn off Play Protect, change package/signature to impersonate production, or reuse a user-approved device accessibility session for a different package.

## Conditional evaluation lane: Google Play Internal App Sharing (policy and access gate open)

Official [Google help](https://support.google.com/googleplay/android-developer/answer/9844679?hl=en):
- Accepts **debuggable APK** and repeated **versionCode** values; re-signs every upload with a **per-app persistent Google-managed internal-app-sharing test certificate**. Upload source APK signer may differ each CI run; the **Play-delivered signer** must remain constant for the same registered app.
- This is distinct from the **Internal testing track** and ordinary production Play App Signing. Do not confuse test certificate with production keystore fingerprint or assume that the original upload APK SHA256 survives Play re-signing.
- Requires the actual Play Console developer access, an app/appropriate tester access for `ai.closepaw.capturecandidate`, internal-app-sharing uploader/tester permissions, and opt-in in the Play Store. A prior README link to `ai.closepaw` testing is **not proof** that this separate package is registered or accessible to this account.
- Do one **manual approved upload of the already verified candidate-app.apk** only after checking Google Play Accessibility API compliance and authorized Play Console access. Save returned Play download link, certificate fingerprint and delivered artifact SHA256. Verify the actual device package, code, build source identifier, and signing certificate independently after install.
- API automation is possible **after** authorization and manual proof: official [internalappsharingartifacts.uploadapk](https://developers.google.com/android-publisher/api-ref/rest/v3/internalappsharingartifacts/uploadapk) returns `downloadUrl`, certificate fingerprint, artifact sha256. Configure least privilege API credentials in GitHub Secrets, never repository files or chat; never create placeholders that look like working credentials.
- Internal app sharing links expire 60 days, with up to 100 downloads per link. Do not use these links as durable release distribution or public customer updates.

**Material Play policy gate (do not skip):** Google [AccessibilityService policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en) has strict restrictions on autonomous accessibility-driven actions and mandates declarations and prominent disclosure/consent where applicable. ClosePaw is an AI device automation app, so a full policy-compliance review is needed before use of a Google Play path. Do not inaccurately mark it as an assistive accessibility tool to obtain an exemption. Internal app sharing availability or successful upload must not be interpreted as policy approval.

## Signing and package-identity decision

1. **Google Play-delivered QA lane:** the Google-managed internal-app-sharing certificate is the stable installer/update identity; keep `ai.closepaw.capturecandidate` and verify its certificate fingerprint on every device delivery. CI's ephemeral Debug signer is upload-only and **not** device-install/update identity.
2. **Outside-Play lane (separate future decision):** if a compliant distribution channel demands signed standalone Debug APK updates, provision a **dedicated, reusable QA keystore**, isolated from production key `CLOSEPAW_*`, in access-controlled GitHub Actions Secrets; fail closed if key/cert mismatch. Store only the expected certificate **public fingerprint** in configuration and test it with `apksigner`. Key backup/rotation/update path needs approval. This does *not* by itself resolve Play Protect blocking.
3. Never sign debuggable QA APK with the production release keystore solely for convenience; this expands the production key trust boundary and does not cure sensitive-permission sideload policy. Never commit QA private signing material.
4. On a subsequent QA install, signed package must match existing `ai.closepaw.capturecandidate` certificate. If mismatch, do **not** automatically uninstall/reset a prior candidate: preserve its separate data and seek an authorized migration plan. Production package and data are always outside scope.

## Gate checklist / acceptance

- [x] Exact-source QA build; verified unsigned/provisional Debug signature, APK SHA256, `ai.closepaw.capturecandidate` package and code.
- [x] Historical same-phone Termux/A DB-only separate Debug install and local capture evidence located. It is **not** evidence of current Play compliance.
- [x] Official Internal App Sharing certificate persistence and API automation mechanism documented.
- [ ] Developer confirms authorized Play Console for this exact candidate package and valid internal-app-sharing uploader/tester configuration.
- [x] Public policy/source preflight: current autonomous AI-agent description carries **high compliance risk** under AccessibilityService policy; **not approved** for Play upload.
- [ ] Formal policy-fit resolution for actual AccessibilityService autonomy, user disclosure, and REQUEST_INSTALL_PACKAGES completed.
- [ ] Official compliant upload of the **same exact-source** APK, Play-issued signer/download URL obtained, and explicit APK source mapping recorded.
- [ ] OS-approved Play app install completed on Galaxy; preserve `ai.closepaw`, independently verify candidate source/package/code/certificate.
- [ ] Prove candidate-bound independent accessibility/service/control connector. The production MCP endpoint is **not** candidate evidence.
- [ ] Only then one #147 physical six-key and one language/Shift smoke test; no full 3-trial mixed or speed benchmark.
- [ ] First subsequent updated QA build reuses the same Play test certificate and installs over prior candidate without deleting data.

Fallback only after a documented failure: Google [Play Protect developer guidance](https://developers.google.com/android/play-protect/warning-dev-guidance) and appeal route; investigate alternate policy-compliant channel before creating any additional keystore or invoking device installation.

**Result today:** design/preflight evidence only, **not** an operational deployment/signing system. No device touch, OS approval, Console upload, QA private key, source code change or production release.
