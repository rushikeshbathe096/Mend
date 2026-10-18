"""parse_reply (M3 U2.4): no key -> model_unavailable, the model id from the provider response, output passed through."""
import json

import httpx
import pytest
from fastapi.testclient import TestClient

import reply_parser
from main import app

client = TestClient(app)


def gemini_response(text, model_version="gemini-2.5-flash-001"):
    body = {"candidates": [{"content": {"parts": [{"text": text}]}}]}
    if model_version is not None:
        body["modelVersion"] = model_version
    return body


def fake_http(handler):
    return httpx.Client(transport=httpx.MockTransport(handler))


@pytest.fixture(autouse=True)
def no_key(monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    monkeypatch.delenv("MEND_AI_INTERNAL_TOKEN", raising=False)


def test_no_api_key_is_model_unavailable_and_no_output():
    response = client.post("/api/v1/parse_reply", json={"text": "please stop messaging me"})
    assert response.status_code == 503
    assert response.json() == {"error": "model_unavailable"}


def test_no_api_key_raises_without_calling_the_provider():
    def handler(request):
        raise AssertionError("provider must not be called without a key")
    with pytest.raises(reply_parser.ModelUnavailable):
        reply_parser.parse_reply("I already paid", http=fake_http(handler))


def test_model_id_comes_from_the_provider_response(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "test-key-not-real")
    seen = {}

    def handler(request):
        seen["key"] = request.headers.get("x-goog-api-key")
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=gemini_response('{"intent":"ALREADY_PAID","promised_date":null,"abstain":false}'))

    result = reply_parser.parse_reply("I already paid yesterday", http=fake_http(handler))
    assert result.suggestion == {"intent": "ALREADY_PAID", "promised_date": None, "abstain": False}
    assert result.model_id == "gemini-2.5-flash-001"
    assert result.prompt_version == reply_parser.PROMPT_VERSION
    assert seen["key"] == "test-key-not-real"
    assert "I already paid yesterday" in seen["body"]["contents"][0]["parts"][0]["text"]


def test_missing_model_version_is_unknown_not_invented(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "test-key-not-real")
    result = reply_parser.parse_reply("stop", http=fake_http(
        lambda r: httpx.Response(200, json=gemini_response('{"intent":"OPT_OUT","promised_date":null,"abstain":false}', None))))
    assert result.model_id is None


def test_output_is_passed_through_never_coerced(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "test-key-not-real")
    # an invented intent and an extra field reach the backend as sent; the backend validator rejects them
    raw = '{"intent":"REFUND_NOW","promised_date":"tomorrow","abstain":"no","confidence":0.99}'
    result = reply_parser.parse_reply("x", http=fake_http(lambda r: httpx.Response(200, json=gemini_response(raw))))
    assert result.suggestion == json.loads(raw)
    assert result.output_error is None


@pytest.mark.parametrize("text,error", [("not json at all", "not_json"), ('["OPT_OUT"]', "not_object")])
def test_non_object_output_is_reported_as_such(monkeypatch, text, error):
    monkeypatch.setenv("GEMINI_API_KEY", "test-key-not-real")
    result = reply_parser.parse_reply("x", http=fake_http(lambda r: httpx.Response(200, json=gemini_response(text))))
    assert result.suggestion is None
    assert result.output_error == error


def test_provider_failure_is_a_model_error(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "test-key-not-real")
    with pytest.raises(reply_parser.ModelError):
        reply_parser.parse_reply("x", http=fake_http(lambda r: httpx.Response(500, json={})))

    def timeout(request):
        raise httpx.ReadTimeout("slow", request=request)
    with pytest.raises(reply_parser.ModelError):
        reply_parser.parse_reply("x", http=fake_http(timeout))


def test_endpoint_rejects_empty_and_oversized_text():
    assert client.post("/api/v1/parse_reply", json={"text": ""}).status_code == 422
    assert client.post("/api/v1/parse_reply", json={"text": "a" * 2001}).status_code == 422


def test_endpoint_requires_the_internal_token_when_configured(monkeypatch):
    monkeypatch.setenv("MEND_AI_INTERNAL_TOKEN", "internal-test-token")
    assert client.post("/api/v1/parse_reply", json={"text": "hello"}).status_code == 401
    response = client.post("/api/v1/parse_reply", json={"text": "hello"}, headers={"X-Mend-Internal-Token": "internal-test-token"})
    assert response.status_code == 503  # authenticated, but no model configured
