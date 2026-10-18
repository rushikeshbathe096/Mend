"""reply_parser edge cases (M3 U2.6): malformed provider answers are reported, never completed or guessed."""
import json

import httpx
import pytest

import reply_parser

FAKE_KEY = "test-key-not-real"


@pytest.fixture(autouse=True)
def key(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", FAKE_KEY)


def call(payload=None, status=200, raw=None):
    def handler(request):
        if raw is not None:
            return httpx.Response(status, content=raw)
        return httpx.Response(status, json=payload)
    return reply_parser.parse_reply("hello", http=httpx.Client(transport=httpx.MockTransport(handler)))


def test_model_configured_follows_the_key(monkeypatch):
    assert reply_parser.model_configured() is True
    monkeypatch.delenv("GEMINI_API_KEY")
    assert reply_parser.model_configured() is False


@pytest.mark.parametrize("payload", [
    {},
    {"candidates": []},
    {"candidates": [{"content": {"parts": []}}]},
    {"candidates": [{"content": {"parts": [{"text": 7}]}}]},
    {"candidates": [{"finishReason": "SAFETY"}]},
])
def test_missing_model_text_is_no_text(payload):
    result = call(payload)
    assert result.suggestion is None
    assert result.output_error == "no_text"


@pytest.mark.parametrize("raw", [b"[1]", b'"str"', b"42", b"null"])
def test_provider_body_that_is_json_but_not_an_object_is_a_model_error(raw):
    with pytest.raises(reply_parser.ModelError, match="provider_response_not_object"):
        call(raw=raw)


def test_provider_body_that_is_not_json_is_a_model_error():
    with pytest.raises(reply_parser.ModelError, match="provider_response_not_json"):
        call(raw=b"<html>gateway</html>")


@pytest.mark.parametrize("model_version", [None, 42, ["x"], {"v": 1}])
def test_non_string_model_version_is_unknown(model_version):
    payload = {"candidates": [{"content": {"parts": [{"text": '{"intent":"OTHER","promised_date":null,"abstain":false}'}]}}]}
    if model_version is not None:
        payload["modelVersion"] = model_version
    assert call(payload).model_id is None


@pytest.mark.parametrize("intent", reply_parser.INTENTS)
def test_prompt_names_every_intent_and_asks_for_json_at_temperature_zero(intent):
    seen = {}

    def handler(request):
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": "{}"}]}}]})
    reply_parser.parse_reply("hello", http=httpx.Client(transport=httpx.MockTransport(handler)))
    assert f'"{intent}"' in seen["body"]["contents"][0]["parts"][0]["text"]
    assert seen["body"]["generationConfig"] == {"responseMimeType": "application/json", "temperature": 0}


def test_an_injected_client_is_not_closed_by_the_parser():
    http = httpx.Client(transport=httpx.MockTransport(
        lambda r: httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": "{}"}]}}]})))
    reply_parser.parse_reply("hello", http=http)
    assert not http.is_closed
