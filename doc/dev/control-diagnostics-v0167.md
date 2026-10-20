# v0.1.67 control feedback and diagnostics

## User-authorized scope

Follow the v0.1.66 camera-excluded acceptance. Preserve Notes/Clock HOLD and camera EXCLUDED. Implement improvements, pass CI, publish with the existing signing identity, update the phone, verify reconnection/version, and prepare the next human acceptance. Do not report all defects fixed or all tests passed based on a release alone.

## Implemented changes

- `android.control.feedback_test` is an explicitly invoked five-second, non-mutating indicator test. It shares the screen mutex, invalidates stale observations, checks the initial protected screen, consent/service/lock state during the interval and removes the indicator in a non-cancellable finally block. It never clicks/types, invokes a camera or claims human touch verification. It can be stopped by physical lock; generic UI action cancellation remains unsupported.
- Normal actions and observations keep no minimum display dwell. The diagnostic operation exists solely to make human touch and physical lock interruption reproducible.
- App catalog re-queries visible packages and current policy on each use, reuses display labels for at most30 seconds and checks cancellation between label loads. Removed packages are evicted. This is a repeated-lookup optimization, not evidence that a cold device query timeout is fully resolved.
- Timeouts are classified using operation identity, not presence of a `filter` argument. A filtered screen read can no longer be mislabeled as an app-catalog timeout.
- An explicit transient post-action observation gets one extra fresh guarded read after at most100ms without another event. Protected/locked states still stop immediately. No action is replayed and the full fingerprint/security policy is unchanged.
- Diagnostics now expose up to64 metadata events through `get_diagnostics.generic_control`: task start/end/release, indicator attach/remove/failure, capture probe/tree timing, app query/label-cache counts, native validation changed dimensions and OCR usable-body status. No screen text, raw fingerprints, package names, screenshot bytes, address/account data or exception messages enter this new ring. Task IDs correlate serialized operation stages.

## Next acceptance protocol

1. Ensure an unlocked harmless calculator screen. Discover `android.control.feedback_test`, execute once. While the border/text remains visible, manually press2. After the task returns `duration_completed` and `indicator_removed=true`, independently observe calculator input and confirm whether the user touched while the indicator was present. Neither machine output alone proves human timing.
2. Execute the same feedback test with a new request ID. While its indicator is visible, physically lock the phone. Expect `state=interrupted`, `reason_code=device_locked`, `indicator_removed=true`. Unlock and independently verify screen read recovery. This tests the bounded indicator operation's lock interruption; it does not prove cancellation of every app gesture or service termination.
3. Query apps by calculator label twice and inspect catalog query time, label loads/cache hits, task wall vs device time. Retain timeout evidence without repeating unsafe actions.
4. Reproduce Samsung Internet body read on a known public page; compare Accessibility/OCR/body metadata. Do not weaken quality thresholds to convert a missing body into success.
5. Observe/refuse a dynamic-screen action once, review changed-dimension metadata. Do not disable freshness guards or automatically resend.

## Known residuals

Samsung Internet content_missing, intermittent tunnel/network timeouts, true vs false screen-change refusals, cold app catalog timeout and full round-trip latency are unresolved until device evidence establishes cause and improvement. This release adds evidence needed to isolate them. Multi-page Gemini collection remains separate from the single-page24-line acceptance. Notes/Clock remain HOLD. Camera remains EXCLUDED.

## Validation / delivery

Regression coverage adds label-cache visibility, filtered-observation timeout identity, transient recovery without another event, test-only feedback invalidation and cancellation cleanup. Local Gradle execution could not download its distribution in this environment; GitHub Security CI is the validation authority. Append exact CI/release/installed-version outcomes after completion. Target version0.1.67/code68.
