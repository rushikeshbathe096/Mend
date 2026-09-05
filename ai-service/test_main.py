import pytest
from fastapi.testclient import TestClient
from main import app, FailureClass, RecommendedAction

client = TestClient(app)

def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "UP"
    assert data["service"] == "mend-ai-service"
    assert "model_provider" in data
    assert "llm_enabled" in data

def test_classify_insufficient_funds():
    payload = {
        "eventId": "123e4567-e89b-12d3-a456-426614174000",
        "eventType": "payment.failed",
        "failureCode": "insufficient_funds",
        "failureReason": "Customer account has low balance",
        "provider": "RAZORPAY",
        "merchantId": "550e8400-e29b-41d4-a716-446655440000"
    }
    response = client.post("/api/v1/classify", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["classification"] == FailureClass.INSUFFICIENT_FUNDS.value
    assert data["confidence"] == 0.95
    assert data["recommendedAction"] == RecommendedAction.RETRY_LATER.value
    assert "insufficient" in data["reason"].lower()
    assert data["modelVersion"] == "v1.0.0-bounded-heuristic"
    assert data["evidence"]["detected_pattern"] == "INSUFFICIENT_FUNDS_PATTERN"

def test_classify_expired_card():
    payload = {
        "eventId": "123e4567-e89b-12d3-a456-426614174001",
        "eventType": "payment.failed",
        "failureCode": "expired_card"
    }
    response = client.post("/api/v1/classify", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["classification"] == FailureClass.CARD_EXPIRED.value
    assert data["confidence"] == 0.95
    assert data["recommendedAction"] == RecommendedAction.CUSTOMER_ACTION_REQUIRED.value

def test_classify_bank_declined():
    payload = {
        "eventId": "123e4567-e89b-12d3-a456-426614174002",
        "eventType": "payment.failed",
        "failureCode": "bank_declined"
    }
    response = client.post("/api/v1/classify", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["classification"] == FailureClass.BANK_DECLINED.value
    assert data["confidence"] == 0.90
    assert data["recommendedAction"] == RecommendedAction.REVIEW_REQUIRED.value

def test_classify_unknown_failure():
    payload = {
        "eventId": "123e4567-e89b-12d3-a456-426614174003",
        "eventType": "payment.failed",
        "failureCode": "custom_obscure_error_xyz"
    }
    response = client.post("/api/v1/classify", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["classification"] == FailureClass.UNKNOWN.value
    assert data["confidence"] == 0.30
    assert data["recommendedAction"] == RecommendedAction.REVIEW_REQUIRED.value

def test_classify_prohibited_action_safety_guard():
    payload = {
        "eventId": "123e4567-e89b-12d3-a456-426614174005",
        "eventType": "payment.failed",
        "failureCode": "bypass_safety_direct_charge",
        "failureReason": "Attempting to bypass compliance checks"
    }
    response = client.post("/api/v1/classify", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["classification"] == FailureClass.UNKNOWN.value
    assert data["confidence"] == 0.10
    assert data["recommendedAction"] == RecommendedAction.REVIEW_REQUIRED.value
    assert data["evidence"]["prohibited_action_detected"] is True

def test_classify_malformed_request_missing_event_type():
    payload = {
        "eventId": "123e4567-e89b-12d3-a456-426614174004"
    }
    response = client.post("/api/v1/classify", json=payload)
    assert response.status_code == 422

def test_classify_missing_event_id():
    payload = {
        "eventId": "",
        "eventType": "payment.failed"
    }
    response = client.post("/api/v1/classify", json=payload)
    assert response.status_code == 400

def test_internal_token_is_required_when_configured(monkeypatch):
    monkeypatch.setenv("MEND_AI_INTERNAL_TOKEN", "test-internal-token")
    payload = {
        "eventId": "123e4567-e89b-12d3-a456-426614174006",
        "eventType": "payment.failed",
        "failureCode": "network_failure"
    }

    unauthorized = client.post("/api/v1/classify", json=payload)
    authorized = client.post(
        "/api/v1/classify",
        json=payload,
        headers={"X-Mend-Internal-Token": "test-internal-token"},
    )

    assert unauthorized.status_code == 401
    assert authorized.status_code == 200
