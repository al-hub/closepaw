# Generic non-node freshness v0.1.59

Status: test-first RED in progress. Installed baseline v0.1.58/code59. No new delivery claimed.

The earlier depth32 failure is corrected in v0.1.58: complete device observations reached depth47.
App open was still refused twice before execution with screen_changed. Code confirms that Home/app launch
unnecessarily depend on the changing chat-client message tree, although neither action targets those nodes.
The exact runtime changed component was not returned, so distinguish this design finding from directly observed metadata.

Contract: Home/exact previously selected package launch require fresh foreground app/window identity, current local consent,
secure-window/app/auth checks, expiry, single-use observation, shared lock and durable dedup. Ordinary content refresh
within that same allowed window is not a semantic target change for those two actions.
Back/click/long-press/type/scroll continue to require complete observation and exact content fingerprint.
Do not relax protected-window refusal, unknown outcome handling, selected package policy or any node-targeted freshness check.
Expose validation_scope to make the action boundary explicit; retain existing input schemas and endpoint.

Three added tests first reproduce global-navigation content-refresh refusal while retaining all node/Back hash checks.
Run RED before uploading implementation, then GREEN/full lint/build, review/merge/main CI/signed delivery/public verification
and exactly one remote update. Re-test app launch using fresh actual screen evidence and preserve all drafts/questions.
The user has not specified a target model; inspect actual choices before requesting a selection. P2 ACTIVE.
