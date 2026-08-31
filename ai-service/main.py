from enum import Enum
from typing import Optional
from pydantic import BaseModel, Field
from fastapi import FastAPI, HTTPException, status
import logging

app = FastAPI(
    title="Mend AI Service",
    version="1.0.0",
    description="AI-powered payment recovery platform AI Service"
)

class HealthResponse(BaseModel):
    status: str
    service: str

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

class ClassificationResponse(BaseModel):
    classification: FailureClass
    confidence: float = Field(..., ge=0.0, le=1.0)
    recommendedAction: RecommendedAction
    reason: str
    modelVersion: str = "v1.0.0-rule-based"

def classify_failure(req: ClassificationRequest) -> ClassificationResponse:
    code = (req.failureCode or req.failureReason or "").lower().strip()

    if any(term in code for term in ["insufficient_funds", "low_balance", "insufficient_balance", "insufficient funds"]):
        return ClassificationResponse(
            classification=FailureClass.INSUFFICIENT_FUNDS,
            confidence=0.95,
            recommendedAction=RecommendedAction.RETRY_LATER,
            reason="Failure due to insufficient customer account funds; recommended retry after delay."
        )

    if any(term in code for term in ["expired_card", "card_expired", "expiry", "card expired"]):
        return ClassificationResponse(
            classification=FailureClass.CARD_EXPIRED,
            confidence=0.95,
            recommendedAction=RecommendedAction.CUSTOMER_ACTION_REQUIRED,
            reason="Card has expired; customer must update payment method."
        )

    if any(term in code for term in ["bank_declined", "do_not_honor", "transaction_not_permitted", "declined_by_bank", "bank declined"]):
        return ClassificationResponse(
            classification=FailureClass.BANK_DECLINED,
            confidence=0.90,
            recommendedAction=RecommendedAction.REVIEW_REQUIRED,
            reason="Issuing bank declined transaction without specific retry clearance."
        )

    if any(term in code for term in ["authentication_failed", "3d_secure_failed", "otp_failed", "auth_failed"]):
        return ClassificationResponse(
            classification=FailureClass.AUTHENTICATION_FAILED,
            confidence=0.90,
            recommendedAction=RecommendedAction.CUSTOMER_ACTION_REQUIRED,
            reason="Customer failed 3DS/OTP authentication."
        )

    if any(term in code for term in ["network_failure", "gateway_timeout", "system_error", "timeout"]):
        return ClassificationResponse(
            classification=FailureClass.NETWORK_FAILURE,
            confidence=0.85,
            recommendedAction=RecommendedAction.RETRY_IMMEDIATELY,
            reason="Transient gateway or network timeout encountered."
        )

    if any(term in code for term in ["limit_exceeded", "max_amount_exceeded", "limit exceeded"]):
        return ClassificationResponse(
            classification=FailureClass.LIMIT_EXCEEDED,
            confidence=0.90,
            recommendedAction=RecommendedAction.CUSTOMER_ACTION_REQUIRED,
            reason="Transaction exceeded card or account limit."
        )

    return ClassificationResponse(
        classification=FailureClass.UNKNOWN,
        confidence=0.30,
        recommendedAction=RecommendedAction.REVIEW_REQUIRED,
        reason="Unrecognized failure code; manual or secondary review required."
    )

@app.get("/health", response_model=HealthResponse)
def health_check():
    return HealthResponse(status="UP", service="mend-ai-service")

@app.post("/api/v1/classify", response_model=ClassificationResponse, status_code=status.HTTP_200_OK)
def classify_endpoint(request: ClassificationRequest):
    if not request.eventId or not request.eventId.strip():
        raise HTTPException(status_code=400, detail="eventId must not be empty")
    return classify_failure(request)
