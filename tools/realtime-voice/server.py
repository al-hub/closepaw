#!/usr/bin/env python3
"""Local-only WebRTC session broker and ClosePaw tool endpoint."""

from __future__ import annotations

import argparse
import json
import os
import secrets
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from realtime_adapter import RealtimeBridgeAdapter, realtime_session_config


HOST = "127.0.0.1"
DEFAULT_PORT = 3000
OPENAI_REALTIME_URL = "https://api.openai.com/v1/realtime/calls"
INDEX_PATH = Path(__file__).with_name("index.html")
MAX_SDP_BYTES = 256 * 1024
MAX_TOOL_BYTES = 64 * 1024


def encode_multipart(sdp: str, session: dict) -> tuple[bytes, str]:
    boundary = "----closepaw-" + secrets.token_hex(16)
    parts = []

    def field(name: str, value: str) -> None:
        parts.extend(
            [
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                value.encode(),
                b"\r\n",
            ]
        )

    field("sdp", sdp)
    field("session", json.dumps(session, separators=(",", ":")))
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), boundary


def create_realtime_call(
    sdp: str,
    *,
    api_key: str,
    opener=urllib.request.urlopen,
) -> str:
    body, boundary = encode_multipart(sdp, realtime_session_config())
    request = urllib.request.Request(
        OPENAI_REALTIME_URL,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
    )
    with opener(request, timeout=30) as response:
        return response.read().decode()


class App:
    def __init__(self, api_key: str, adapter: RealtimeBridgeAdapter | None = None) -> None:
        self.api_key = api_key
        self.adapter = adapter or RealtimeBridgeAdapter()


class Handler(BaseHTTPRequestHandler):
    server_version = "ClosePawRealtime/1"

    def log_message(self, fmt, *args):
        return

    @property
    def app(self) -> App:
        return self.server.app  # type: ignore[attr-defined]

    def _read(self, limit: int) -> bytes:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ValueError("invalid_content_length")
        if length <= 0 or length > limit:
            raise ValueError("invalid_content_length")
        return self.rfile.read(length)

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status: int, value: dict) -> None:
        self._send(
            status,
            json.dumps(value, separators=(",", ":")).encode(),
            "application/json",
        )

    def do_GET(self):
        if self.path == "/":
            self._send(200, INDEX_PATH.read_bytes(), "text/html; charset=utf-8")
            return
        if self.path == "/api/health":
            self._json(200, {"status": "ok", "service": "closepaw-realtime-voice"})
            return
        self._json(404, {"error": "not_found"})

    def do_POST(self):
        if self.path == "/api/session":
            self._session()
            return
        if self.path == "/api/tool":
            self._tool()
            return
        self._json(404, {"error": "not_found"})

    def _session(self):
        if self.headers.get("Content-Type", "").split(";", 1)[0] != "application/sdp":
            self._json(415, {"error": "content_type_must_be_application_sdp"})
            return
        try:
            sdp = self._read(MAX_SDP_BYTES).decode()
        except (UnicodeDecodeError, ValueError):
            self._json(400, {"error": "invalid_sdp"})
            return
        if not sdp.strip():
            self._json(400, {"error": "invalid_sdp"})
            return
        try:
            answer = create_realtime_call(sdp, api_key=self.app.api_key)
        except urllib.error.HTTPError as exc:
            self._json(exc.code, {"error": "realtime_session_failed"})
            return
        except (OSError, urllib.error.URLError):
            self._json(502, {"error": "realtime_session_failed"})
            return
        self._send(201, answer.encode(), "application/sdp")

    def _tool(self):
        if self.headers.get("Content-Type", "").split(";", 1)[0] != "application/json":
            self._json(415, {"error": "content_type_must_be_application_json"})
            return
        try:
            payload = json.loads(self._read(MAX_TOOL_BYTES).decode())
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            self._json(400, {"error": "invalid_json"})
            return
        if not isinstance(payload, dict):
            self._json(400, {"error": "invalid_json"})
            return

        required = ("session_id", "call_id", "name", "arguments")
        if any(key not in payload for key in required):
            self._json(400, {"error": "missing_tool_fields"})
            return
        result = self.app.adapter.execute(
            session_id=payload["session_id"],
            call_id=payload["call_id"],
            function_name=payload["name"],
            arguments=payload["arguments"],
        )
        self._json(200, result)


def parse_args():
    parser = argparse.ArgumentParser(description="ClosePaw Realtime Voice Adapter")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not api_key:
        print("OPENAI_API_KEY is required")
        return 2
    server = ThreadingHTTPServer((HOST, args.port), Handler)
    server.app = App(api_key)  # type: ignore[attr-defined]
    print(f"Open http://localhost:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
