# P0 minimal diagnostic candidate 0.1.99/code100

This candidate reuses the existing #197 branch, its consistent single-sample classification, #196 clock tests, and the two projection source/test files already validated in #199. It does not include the P2 monitoring runtime or the terminal capability reporting changes. No new capture admission or timestamp conversion is introduced. It is not a completed #165 release.

The installed 0.1.98 APK exposes timing buckets but not the paired raw samples needed to compare a controlled screen change. The candidate records at most the first/last source timestamp, acquisition/receipt elapsed time, uptime and consent generation per attempt in the existing bounded diagnostics. No pixel, screen text, token or package metadata is added. Freshness, post-acquire, final-delivery, ownership, geometry, revocation and masking checks remain enforced.

Independent review and exact-head Security/UI success are required before merge. Existing main Security/UI and the existing signing workflow must pass before this diagnostic version is published. Final P0/P1/P2 acceptance is not implied. A new final integration release must use a later version if this diagnostic version is published.

After a reviewed signed build is installed, verify installed/current version and the active projection/control/lock state. On the single approved Galaxy, compare a privacy-free screen change before/after using the first/last samples, task IDs and consent generation. Do not repeat the old unchanged failure. No lock/suspend experiment unless these samples remain insufficient. Never infer a universal Android clock contract from this device, switch to uptime by age matching, increase the freshness threshold, or disable freshness. Request actual install/OS sharing actions only when the app requires them.

The repository's historical independent review path used separately executed AI review agents (#177 comments). Such agents are currently prohibited by the user's active instruction. A real independent human reviewer or an explicit change to that instruction is needed; an author comment or this implementation author's self-audit is not independent review.
