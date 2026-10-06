# ClosePaw Realtime Voice Adapter

Local WebRTC voice client for the first real ChatGPT/OpenAI Realtime ↔ ClosePaw closed loop.

## Flow

```
microphone
  -> OpenAI Realtime (WebRTC)
  -> run_task function call
  -> POST /api/tool
  -> RealtimeBridgeAdapter
  -> Bridge Core
  -> PC/KUM adapter
  -> structured BridgeResult
  -> function_call_output
  -> response.create
  -> spoken model response
```

The browser owns microphone, speaker, WebRTC, and the Realtime data channel.
The local Python server owns the OpenAI API key, Realtime session creation, and
device-tool execution. It binds only to `127.0.0.1`.

## Run

```bash
export OPENAI_API_KEY='...'
python3 tools/realtime-voice/server.py
```

Open `http://localhost:3000`, select **Start**, and say:

> 내 노트북에서 KUM 상태 확인해줘.

The expected first E2E path is:

1. Realtime emits `run_task(device=laptop, capability=kum, ...)`.
2. Browser POSTs the function call to the local tool endpoint.
3. Bridge Core routes it to the read-only PC/KUM adapter.
4. Browser sends the structured result as `function_call_output`.
5. Browser sends `response.create`.
6. Realtime judges the result and speaks the answer.

## Security boundaries

- The OpenAI API key remains on the local server and is never sent to browser JavaScript.
- Server binds to loopback only.
- The first capability is KUM status only.
- The model does not provide shell source. The KUM adapter maps the request to fixed read-only probes.
- Existing Bridge Core session/correlation rules remain authoritative.

## Test

```bash
python3 -m pytest -q tools/realtime-voice/tests
```

This adapter deliberately depends on the existing transport-neutral
`tools/chatgpt-bridge` core rather than duplicating its command/result protocol.
