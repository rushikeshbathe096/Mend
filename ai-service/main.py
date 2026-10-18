"""MEND ai-service (M3, ADR-003): one AI task, customer-reply parsing, off the payment path.

Endpoints: GET /health and POST /api/v1/parse_reply. Nothing here can move money or change a campaign: the backend
validates every suggestion and a deterministic policy owns all effects.
"""
import logging
import os
from typing import Optional

from fastapi import FastAPI, Header, HTTPException, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

import reply_parser

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("mend-ai-service")

app = FastAPI(
    title="MEND reply-parsing service",
    version="3.0.0",
    description="Parses a customer's reply into a schema-checked suggestion. Never on the payment path.",
)


class HealthResponse(BaseModel):
    status: str
    service: str
    model_configured: bool
    prompt_version: str


def require_internal_auth(internal_token: Optional[str]) -> None:
    """Require service authentication whenever an internal token is configured."""
    configured_token = os.getenv("MEND_AI_INTERNAL_TOKEN")
    if configured_token and internal_token != configured_token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid internal service token")


@app.get("/health", response_model=HealthResponse)
def health_check():
    # whether a model key is configured, nothing more: no provider guess, no fallback
    return HealthResponse(status="UP", service="mend-ai-service", model_configured=reply_parser.model_configured(),
                          prompt_version=reply_parser.PROMPT_VERSION)


class ParseReplyRequest(BaseModel):
    """A customer reply (PII: never logged). The backend caps the text at 2000 characters; so does this endpoint."""
    text: str = Field(..., min_length=1, max_length=2000)


@app.post("/api/v1/parse_reply")
def parse_reply_endpoint(request: ParseReplyRequest, x_mend_internal_token: Optional[str] = Header(default=None)):
    """M3 (ADR-003 §5): the model's suggestion, passed through unvalidated; the backend validates it strictly."""
    require_internal_auth(x_mend_internal_token)
    try:
        result = reply_parser.parse_reply(request.text)
    except reply_parser.ModelUnavailable:
        return JSONResponse(status_code=503, content={"error": "model_unavailable"})
    except reply_parser.ModelError as e:
        logger.warning("parse_reply model error: %s", e)
        return JSONResponse(status_code=502, content={"error": "model_error", "detail": str(e)})
    return {
        "suggestion": result.suggestion,
        "output_error": result.output_error,
        "model_id": result.model_id,
        "prompt_version": result.prompt_version,
    }
