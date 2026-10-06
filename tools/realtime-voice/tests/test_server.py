import io
import json

from server import encode_multipart, create_realtime_call


class FakeResponse:
    def __init__(self, body=b"answer-sdp"):
        self._body = body

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


def test_encode_multipart_contains_sdp_and_session_without_api_key():
    body, boundary = encode_multipart("offer-sdp", {"type": "realtime", "model": "gpt-realtime-2.1"})

    text = body.decode()
    assert boundary in text
    assert 'name="sdp"' in text
    assert "offer-sdp" in text
    assert 'name="session"' in text
    assert "gpt-realtime-2.1" in text
    assert "sk-" not in text


def test_create_realtime_call_keeps_api_key_in_authorization_header():
    captured = {}

    def opener(request, timeout):
        captured["authorization"] = request.headers["Authorization"]
        captured["content_type"] = request.headers["Content-type"]
        captured["timeout"] = timeout
        captured["body"] = request.data
        return FakeResponse()

    answer = create_realtime_call("offer-sdp", api_key="test-key", opener=opener)

    assert answer == "answer-sdp"
    assert captured["authorization"] == "Bearer test-key"
    assert captured["content_type"].startswith("multipart/form-data; boundary=")
    assert b"test-key" not in captured["body"]
    assert captured["timeout"] == 30
