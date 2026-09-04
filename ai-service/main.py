import os
import json
import logging
from enum import Enum
from typing import Optional, Dict, Any
from pydantic import BaseModel, Field
from fastapi import FastAPI, HTTPException, status
import httpx

from agent.router import router as agent_router

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("mend-ai-service")

app = FastAPI(
    title="Mend Bounded AI Diagnostic Service",
    version="2.0.0",
    description="AI-powered revenue failure diagnostic and recovery strategy recommendation agent for Mend"
)

app.include_router(agent_router)

class HealthResponse(BaseModel):
    status: str
    service: str
    model_provider: str
    llm_enabled: bool

class FailureClass(str, Enum):
    INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS"
    BANK_DECLINED = "BANK_DECLINED"
    CARD_EXPIRED = "CARD_EXPIRED"
    AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED"
    NETWORK_FAILURE = "NETWORK_FAILURE"
    LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    UNKNOWN = "UNKNOWN"

class RecommendedAction(str, Enum):
    RETRY_LATER = "RETRY_LATER"
    RETRY_IMMEDIATELY = "RETRY_IMMEDIATELY"
    CUSTOMER_ACTION_REQUIRED = "CUSTOMER_ACTION_REQUIRED"
    NO_ACTION = "NO_ACTION"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"

class ClassificationRequest(BaseModel):
    eventId: str = Field(..., description="Unique event identifier (UUID)")
    eventType: str = Field(..., description="Event type string, e.g. payment.failed")
    failureCode: Optional[str] = Field(None, description="Payment failure code or reason key")
    failureReason: Optional[str] = Field(None, description="Raw failure reason or description")
    provider: Optional[str] = Field("RAZORPAY", description="Payment gateway or provider name")
    merchantId: Optional[str] = Field(None, description="Merchant UUID string")
    attemptCount: Optional[int] = Field(0, description="Previous failure attempt count")
    metadata: Optional[Dict[str, Any]] = Field(None, description="Additional payment context metadata")

class ClassificationResponse(BaseModel):
    classification: FailureClass
    confidence: float = Field(..., ge=0.0, le=1.0)
    recommendedAction: RecommendedAction
    reason: str
    modelVersion: str = "v1.0.0-bounded-heuristic"
    evidence: Optional[Dict[str, Any]] = Field(None, description="Safe diagnostic evidence and risk signals")

PROHIBITED_KEYWORDS = [
    "bypass_safety", "force_refund", "direct_charge_without_compliance",
    "unauthorized_transfer", "ignore_limits", "override_opt_out"
]

def check_prohibited_actions(text: str) -> bool:
    if not text:
        return False
    lower = text.lower()
    return any(keyword in lower for keyword in PROHIBITED_KEYWORDS)

def bounded_heuristic_agent(req: ClassificationRequest) -> ClassificationResponse:
    code = (req.failureCode or "").lower().strip()
    reason_text = (req.failureReason or "").lower().strip()
    combined = f"{code} {reason_text}"

    # Safety Boundary Guard: Check for prohibited action attempts
    if check_prohibited_actions(combined):
        logger.warning(f"Prohibited action keyword detected in request for eventId='{req.eventId}'")
        return ClassificationResponse(
            classification=FailureClass.UNKNOWN,
            confidence=0.10,
            recommendedAction=RecommendedAction.REVIEW_REQUIRED,
            reason="Prohibited or suspicious instruction detected in failure context. Mandating safety review.",
            modelVersion="v1.0.0-bounded-safety-guard",
            evidence={
                "prohibited_action_detected": True,
                "risk_signal": "HIGH_RISK_INSTRUCTION",
                "attempt_count": req.attemptCount or 0
            }
        )

    attempt_count = req.attemptCount or 0

    if any(term in combined for term in ["insufficient_funds", "low_balance", "insufficient_balance", "insufficient funds"]):
        action = RecommendedAction.RETRY_LATER if attempt_count < 3 else RecommendedAction.CUSTOMER_ACTION_REQUIRED
        return ClassificationResponse(
            classification=FailureClass.INSUFFICIENT_FUNDS,
            confidence=0.95,
            recommendedAction=action,
            reason=f"Failure diagnosed as insufficient customer account balance. Recommend retry after delay (attempts={attempt_count}).",
            modelVersion="v1.0.0-bounded-heuristic",
            evidence={
                "detected_pattern": "INSUFFICIENT_FUNDS_PATTERN",
                "risk_signal": "LOW",
                "recommended_delay_hours": 24,
                "attempt_count": attempt_count
            }
        )

    if any(term in combined for term in ["expired_card", "card_expired", "expiry", "card expired"]):
        return ClassificationResponse(
            classification=FailureClass.CARD_EXPIRED,
            confidence=0.95,
            recommendedAction=RecommendedAction.CUSTOMER_ACTION_REQUIRED,
            reason="Card has expired; customer must update payment details via dunning flow.",
            modelVersion="v1.0.0-bounded-heuristic",
            evidence={
                "detected_pattern": "CARD_EXPIRED_PATTERN",
                "risk_signal": "MEDIUM",
                "requires_dunning": True,
                "attempt_count": attempt_count
            }
        )

    if any(term in combined for term in ["bank_declined", "do_not_honor", "transaction_not_permitted", "declined_by_bank", "bank declined"]):
        return ClassificationResponse(
            classification=FailureClass.BANK_DECLINED,
            confidence=0.90,
            recommendedAction=RecommendedAction.REVIEW_REQUIRED,
            reason="Issuing bank declined transaction without specific clearance.",
            modelVersion="v1.0.0-bounded-heuristic",
            evidence={
                "detected_pattern": "BANK_DECLINED_PATTERN",
                "risk_signal": "HIGH",
                "attempt_count": attempt_count
            }
        )

    if any(term in combined for term in ["authentication_failed", "3d_secure_failed", "otp_failed", "auth_failed"]):
        return ClassificationResponse(
            classification=FailureClass.AUTHENTICATION_FAILED,
            confidence=0.90,
            recommendedAction=RecommendedAction.CUSTOMER_ACTION_REQUIRED,
            reason="Customer failed 3DS/OTP authentication. Requires interactive customer re-auth.",
            modelVersion="v1.0.0-bounded-heuristic",
            evidence={
                "detected_pattern": "AUTHENTICATION_FAILED_PATTERN",
                "risk_signal": "MEDIUM",
                "attempt_count": attempt_count
            }
        )

    if any(term in combined for term in ["network_failure", "gateway_timeout", "system_error", "timeout"]):
        return ClassificationResponse(
            classification=FailureClass.NETWORK_FAILURE,
            confidence=0.85,
            recommendedAction=RecommendedAction.RETRY_IMMEDIATELY if attempt_count < 2 else RecommendedAction.RETRY_LATER,
            reason="Transient gateway or network timeout encountered.",
            modelVersion="v1.0.0-bounded-heuristic",
            evidence={
                "detected_pattern": "NETWORK_FAILURE_PATTERN",
                "risk_signal": "LOW",
                "transient": True,
                "attempt_count": attempt_count
            }
        )

    if any(term in combined for term in ["limit_exceeded", "max_amount_exceeded", "limit exceeded"]):
        return ClassificationResponse(
            classification=FailureClass.LIMIT_EXCEEDED,
            confidence=0.90,
            recommendedAction=RecommendedAction.CUSTOMER_ACTION_REQUIRED,
            reason="Transaction exceeded card or account spending limit.",
            modelVersion="v1.0.0-bounded-heuristic",
            evidence={
                "detected_pattern": "LIMIT_EXCEEDED_PATTERN",
                "risk_signal": "MEDIUM",
                "attempt_count": attempt_count
            }
        )

    return ClassificationResponse(
        classification=FailureClass.UNKNOWN,
        confidence=0.30,
        recommendedAction=RecommendedAction.REVIEW_REQUIRED,
        reason="Unrecognized failure signature; manual or secondary review required.",
        modelVersion="v1.0.0-bounded-heuristic",
        evidence={
            "detected_pattern": "UNKNOWN_PATTERN",
            "risk_signal": "HIGH",
            "attempt_count": attempt_count
        }
    )

def query_llm_agent(req: ClassificationRequest) -> Optional[ClassificationResponse]:
    gemini_key = os.getenv("GEMINI_API_KEY")
    openai_key = os.getenv("OPENAI_API_KEY")

    if not gemini_key and not openai_key:
        return None

    prompt = f"""You are Mend's Bounded AI Revenue Failure Diagnostic Agent.
Analyze the following payment failure context and determine:
1. classification: strictly one of [INSUFFICIENT_FUNDS, BANK_DECLINED, CARD_EXPIRED, AUTHENTICATION_FAILED, NETWORK_FAILURE, LIMIT_EXCEEDED, UNKNOWN]
2. confidence: float between 0.00 and 1.00
3. recommendedAction: strictly one of [RETRY_LATER, RETRY_IMMEDIATELY, CUSTOMER_ACTION_REQUIRED, NO_ACTION, REVIEW_REQUIRED]
4. reason: structured diagnostic rationale string
5. modelVersion: "gemini-2.5-flash"
6. evidence: JSON object with detected signals

Context:
- Event ID: {req.eventId}
- Event Type: {req.eventType}
- Provider: {req.provider}
- Failure Code: {req.failureCode}
- Failure Reason: {req.failureReason}
- Previous Attempt Count: {req.attemptCount or 0}

Strict Boundary Requirements:
- Do NOT issue payment charges or bypass compliance.
- Return ONLY valid JSON matching this schema:
{{
  "classification": "INSUFFICIENT_FUNDS",
  "confidence": 0.95,
  "recommendedAction": "RETRY_LATER",
  "reason": "Reason string",
  "modelVersion": "gemini-2.5-flash",
  "evidence": {{"risk": "LOW"}}
}}
"""

    try:
        if gemini_key:
            url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={gemini_key}"
            payload = {
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": {"response_mime_type": "application/json"}
            }
            resp = httpx.post(url, json=payload, timeout=4.0)
            if resp.status_code == 200:
                data = resp.json()
                text_content = data["candidates"][0]["content"]["parts"][0]["text"]
                parsed = json.loads(text_content)
                return ClassificationResponse(
                    classification=FailureClass(parsed["classification"]),
                    confidence=float(parsed["confidence"]),
                    recommendedAction=RecommendedAction(parsed["recommendedAction"]),
                    reason=parsed.get("reason", "Gemini diagnosis complete"),
                    modelVersion="gemini-2.5-flash",
                    evidence=parsed.get("evidence", {})
                )
    except Exception as e:
        logger.error(f"LLM Agent call failed for eventId='{req.eventId}': {e}. Falling back to bounded heuristic engine.")

    return None

@app.get("/health", response_model=HealthResponse)
def health_check():
    gemini_key = bool(os.getenv("GEMINI_API_KEY"))
    openai_key = bool(os.getenv("OPENAI_API_KEY"))
    llm_active = gemini_key or openai_key
    provider = "GEMINI" if gemini_key else ("OPENAI" if openai_key else "BOUNDED_HEURISTIC")
    return HealthResponse(
        status="UP",
        service="mend-ai-service",
        model_provider=provider,
        llm_enabled=llm_active
    )

@app.post("/api/v1/classify", response_model=ClassificationResponse, status_code=status.HTTP_200_OK)
def classify_endpoint(request: ClassificationRequest):
    if not request.eventId or not request.eventId.strip():
        raise HTTPException(status_code=400, detail="eventId must not be empty")

    # 1. Attempt LLM Agent if configured
    llm_res = query_llm_agent(request)
    if llm_res is not None:
        return llm_res

    # 2. Bounded Heuristic Diagnostic Engine
    return bounded_heuristic_agent(request)
