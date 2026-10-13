# Extensible MCP operations: v0.1.41 acceptance

Implementation: fixed MCP discovery/query/execute/get_task/cancel_task tools; dynamic operation registry; schema-version/hash guards; local execution opt-in; persistent task ledger and daemon job links; authenticated asynchronous Termux jobs. Existing read_app/status/diagnostics remain available. Gemini support remains screen reading, without input or send.

## Update once

1. Install the signed v0.1.41 APK after Release assets and checksum are verified.
2. ClosePaw Settings → Termux → Setup/reinstall bridge. This version contains daemon v2 and jobs API v1. Existing F-Droid/RUN_COMMAND setup can deploy it; a Play variant may require the existing manual setup/pairing command. Keep the same pairing token.
3. ClosePaw Settings → ChatGPT Voice: enable **Allow ChatGPT Termux execution** if execution is wanted, then Stop/Connect. Read operations do not require this switch.
4. For Quick Tunnel, update the existing MCP connection to the new private endpoint shown by ClosePaw. The endpoint has a persistent per-install 256-bit bearer path; do not post it in issues/screenshots. The previously public `/mcp` path is not accepted. The Secure Tunnel runtime uses the same private local path automatically while retaining its configured tunnel ID; no new public URL is claimed for that transport.
5. Refresh/reconnect the existing plugin once to acquire the five fixed tools. Verify the actual host tool list; APK installation alone does not prove that the host has refreshed its tool schemas. Do not recreate a second plugin merely to avoid updating the original connection.

Subsequent operation additions under this contract do not alter the MCP tool schemas. Cloudflare Quick Tunnel hostname changes remain independent of schema stability; Secure Tunnel hosting/connection configuration has its own lifecycle.

## Device acceptance

- `get_status`: version 0.1.41, code42, operation contract1.
- `discover_operations`: list app.screen.read, app.targets.list, linux.shell.exec. Then detail lookup with operation_id returns input_schema, operation_version and schema_hash.
- `query(app.targets.list)`: installed/readiness states for Chrome, Samsung Internet and Gemini.
- `query(app.screen.read)` with app gemini: visible captured text only, do not supplement with external search. Legacy read_app retains the screenshot response.
- `execute(linux.shell.exec)` with command `printf 'kv-preflight\n'; command -v kv`, unique request_id, current version/hash and JSON-encoded input: returns task_id. get_task returns confirmed stdout/stderr/exit_code. Its offset and limit count Unicode code points, so Korean text and emoji are not split.
- Once KV's actual command, working directory and noninteractive behavior are confirmed, request “ClosePaw로 터목스에서 KV 실행하고 결과 확인해줘”. Reuse the established task ID for retries/status, not a new request ID. Do not guess a KV subcommand.
- Failure check: `printf 'out\n'; printf 'err\n' >&2; exit 7`; stdout, stderr and exit_code7 remain distinct from HTTP errors.
- Long job: `sleep 10; printf 'finished\n'`; disconnect the MCP request, then get_task retrieves the same job output. Restarting just the Android MCP service should reobserve the persisted daemon job without another submission. Restarting/killing Termux can cause an interrupted/unknown result; no automatic replay.
- Duplicate ID/same input returns same task; changed input returns conflict.
- Cancel: request cancellation, then verify the daemon-confirmed cancelled state. A cancel request is not proof the process stopped.
- Execution switch disabled: query of linux.shell.exec rejected, execute rejected; existing jobs are not killed by changing the switch.

## Expansion acceptance

With the same McpJsonRpcHandler and tools/list definitions, register a previously absent operation having different structured input; discover its detailed schema, invoke it through input_json and verify returned data. The integration unit test covers this behavior; a real ChatGPT host acceptance must also be recorded after its first contract refresh.

## Limits

No interactive PTY/stdin session; KV requiring a terminal menu cannot be driven by this release. No arbitrary app typing/sending, PC operations, schedules or subscriptions are declared supported. Jobs have a one-hour time limit and bounded output. Registries and ledgers are local and single-owner; execution is disabled by default. The private connection grants access to sensitive task output, and enabling shell execution grants command authority to holders of that connection. New scopes or incompatible contracts still need a connection update.

TDD evidence: facade contract commit was published first and failed on the missing implementation in CI. Daemon endpoint/persistence/paging regressions were observed red and corrected. Android Gradle tests could not run locally because the wrapper distribution download is network-blocked; use CI as the Android validation authority.
