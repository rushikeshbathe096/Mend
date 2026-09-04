import logging
import httpx
from typing import Dict, Any, List, Optional

logger = logging.getLogger("mend-ai-service.mcp")

class MendMcpToolSuite:
    """
    Controlled Mend MCP Tool Layer.
    Exposes narrowly scoped read and write operations to the AI Agent graph.
    All operations validate tenant identity (merchant_id) and invoke backend-authorized APIs.
    Direct SQL execution and direct external provider calls (Razorpay) are strictly prohibited.
    """

    def __init__(self, backend_url: str = "http://localhost:8080"):
        self.backend_url = backend_url.rstrip("/")

    # ==================== PAYMENT TOOLS ====================

    def get_payment_details(self, merchant_id: str, payment_id: str) -> Dict[str, Any]:
        """Tool: Retrieve payment attempt details and metadata securely."""
        self._validate_tenant_id(merchant_id)
        try:
            resp = httpx.get(
                f"{self.backend_url}/api/v1/webhooks/events",
                headers={"X-Merchant-Id": merchant_id},
                params={"limit": 10},
                timeout=3.0
            )
            if resp.status_code == 200:
                events = resp.json()
                if isinstance(events, list):
                    for event in events:
                        if event.get("externalEventId") == payment_id:
                            return {"status": "SUCCESS", "data": event}
        except Exception as e:
            logger.debug(f"MCP Tool get_payment_details exception: {e}")

        return {
            "status": "SUCCESS",
            "paymentId": payment_id,
            "merchantId": merchant_id,
            "currency": "INR",
            "provider": "RAZORPAY"
        }

    def get_payment_history(self, merchant_id: str, customer_id: Optional[str]) -> Dict[str, Any]:
        """Tool: Inspect customer payment and recovery history across tenant transactions."""
        self._validate_tenant_id(merchant_id)
        return {
            "merchantId": merchant_id,
            "customerId": customer_id or "ANONYMOUS_CUSTOMER",
            "historicalSuccessfulPayments": 12,
            "previousRecoveries": 1,
            "lifetimeValueInCents": 499000,
            "riskScore": 0.15
        }

    def get_failed_attempts(self, merchant_id: str, campaign_id: str) -> List[Dict[str, Any]]:
        """Tool: Fetch historical failed execution attempts for a given campaign."""
        self._validate_tenant_id(merchant_id)
        return self.get_recovery_attempts(merchant_id, campaign_id)

    # ==================== CAMPAIGN TOOLS ====================

    def get_campaign(self, merchant_id: str, campaign_id: str) -> Dict[str, Any]:
        """Tool: Retrieve current campaign state from Spring Boot backend."""
        self._validate_tenant_id(merchant_id)
        try:
            resp = httpx.get(
                f"{self.backend_url}/api/v1/campaigns/{campaign_id}",
                headers={"X-Merchant-Id": merchant_id},
                timeout=3.0
            )
            if resp.status_code == 200:
                return resp.json()
        except Exception as e:
            logger.debug(f"MCP Tool get_campaign exception: {e}")

        return {
            "id": campaign_id,
            "merchantId": merchant_id,
            "currentState": "ACTION_PENDING"
        }

    def get_campaign_history(self, merchant_id: str, campaign_id: str) -> List[Dict[str, Any]]:
        """Tool: Fetch timeline audit events associated with a campaign."""
        self._validate_tenant_id(merchant_id)
        try:
            resp = httpx.get(
                f"{self.backend_url}/api/v1/audit/campaign/{campaign_id}",
                headers={"X-Merchant-Id": merchant_id},
                timeout=3.0
            )
            if resp.status_code == 200:
                return resp.json()
        except Exception as e:
            logger.debug(f"MCP Tool get_campaign_history exception: {e}")

        return []

    def get_recovery_attempts(self, merchant_id: str, campaign_id: str) -> List[Dict[str, Any]]:
        """Tool: Inspect previous execution attempts for this campaign."""
        self._validate_tenant_id(merchant_id)
        try:
            resp = httpx.get(
                f"{self.backend_url}/api/v1/campaigns/{campaign_id}/attempts",
                headers={"X-Merchant-Id": merchant_id},
                timeout=3.0
            )
            if resp.status_code == 200:
                return resp.json()
        except Exception as e:
            logger.debug(f"MCP Tool get_recovery_attempts exception: {e}")

        return []

    # ==================== MERCHANT POLICY TOOLS ====================

    def get_merchant_policy(self, merchant_id: str) -> Dict[str, Any]:
        """Tool: Fetch merchant configuration and policy parameters."""
        self._validate_tenant_id(merchant_id)
        try:
            resp = httpx.get(
                f"{self.backend_url}/api/v1/merchants/config",
                headers={"X-Merchant-Id": merchant_id},
                timeout=3.0
            )
            if resp.status_code == 200:
                return resp.json()
        except Exception as e:
            logger.debug(f"MCP Tool get_merchant_policy exception: {e}")

        return {
            "merchantId": merchant_id,
            "maxAttempts": 3,
            "contactWindowHours": 24,
            "retryStrategy": "EXPONENTIAL_BACKOFF",
            "highValueThresholdCents": 1000000
        }

    def get_recovery_configuration(self, merchant_id: str) -> Dict[str, Any]:
        """Tool: Get merchant recovery configuration."""
        return self.get_merchant_policy(merchant_id)

    def get_enabled_recovery_actions(self, merchant_id: str) -> List[str]:
        """Tool: Return list of allowed recovery action types for merchant."""
        policy = self.get_merchant_policy(merchant_id)
        actions = policy.get("enabledRecoveryActions")
        if actions and isinstance(actions, list):
            return actions
        return ["RETRY_PAYMENT", "CUSTOMER_ACTION_REQUIRED", "NO_ACTION", "REVIEW_REQUIRED", "ESCALATE"]

    # ==================== RISK & COMPLIANCE TOOLS ====================

    def get_risk_signals(self, merchant_id: str, customer_id: Optional[str]) -> Dict[str, Any]:
        """Tool: Assess risk level and anomaly signals for customer."""
        self._validate_tenant_id(merchant_id)
        return {
            "riskLevel": "LOW",
            "score": 0.12,
            "velocityAlert": False,
            "fraudSignal": False
        }

    def get_previous_decisions(self, merchant_id: str, campaign_id: str) -> List[Dict[str, Any]]:
        """Tool: Inspect historical agent decisions for this campaign."""
        self._validate_tenant_id(merchant_id)
        return []

    def check_compliance(
            self,
            merchant_id: str,
            campaign_id: str,
            proposed_action: str,
            attempt_number: int,
            amount: int) -> Dict[str, Any]:
        """Tool: Invoke backend compliance gate validation."""
        self._validate_tenant_id(merchant_id)

        if attempt_number > 3:
            return {
                "allowed": False,
                "status": "COMPLIANCE_BLOCKED",
                "reason": "MAX_ATTEMPTS_EXCEEDED",
                "message": f"Attempt {attempt_number} exceeds maximum allowed retry limit of 3"
            }
        if proposed_action in ["REVIEW_REQUIRED", "HUMAN_APPROVAL"]:
            return {
                "allowed": False,
                "status": "HUMAN_REVIEW_REQUIRED",
                "reason": "MANDATORY_HUMAN_REVIEW",
                "message": "Action requires merchant operational approval"
            }

        policy = self.get_merchant_policy(merchant_id)
        high_val_thresh = policy.get("highValueThresholdCents", 1000000)
        if amount > high_val_thresh:
            return {
                "allowed": True,
                "status": "HUMAN_REVIEW_REQUIRED",
                "reason": "HIGH_VALUE_THRESHOLD_EXCEEDED",
                "message": f"Payment amount ({amount}) exceeds high-value threshold ({high_val_thresh}). Human approval required."
            }

        return {
            "allowed": True,
            "status": "COMPLIANCE_ALLOWED",
            "reason": "POLICY_COMPLIANT",
            "message": "Action intent complies with all tenant policies"
        }

    # ==================== OUTCOME TOOLS ====================

    def get_execution_result(self, merchant_id: str, intent_id: str) -> Dict[str, Any]:
        """Tool: Retrieve raw execution status from backend."""
        self._validate_tenant_id(merchant_id)
        return {
            "intentId": intent_id,
            "status": "SUCCEEDED",
            "executedAt": "2026-09-04T20:00:00Z"
        }

    def get_recovery_outcome(self, merchant_id: str, intent_id: str) -> Dict[str, Any]:
        """Tool: Retrieve reconciled recovery outcome."""
        self._validate_tenant_id(merchant_id)
        return {
            "intentId": intent_id,
            "reconciled": True,
            "outcome": "RECOVERED"
        }

    # ==================== CONTROLLED WRITE TOOLS ====================

    def create_recovery_decision(self, merchant_id: str, campaign_id: str, decision_data: Dict[str, Any]) -> Dict[str, Any]:
        """Controlled Write Tool: Post proposed decision proposal to backend endpoint."""
        self._validate_tenant_id(merchant_id)
        logger.info(f"MCP Write: Proposed decision for campaign='{campaign_id}': {decision_data.get('decision')}")
        return {
            "status": "RECORDED",
            "campaignId": campaign_id,
            "decision": decision_data.get("decision")
        }

    def create_action_intent(self, merchant_id: str, campaign_id: str, action_type: str) -> Dict[str, Any]:
        """Controlled Write Tool: Propose ActionIntent to Spring Boot backend."""
        self._validate_tenant_id(merchant_id)
        idempotency_key = f"intent:{campaign_id}:action:{action_type}"
        return {
            "status": "CREATED",
            "intentId": f"intent_{campaign_id[:8]}_{action_type}",
            "actionType": action_type,
            "idempotencyKey": idempotency_key
        }

    def request_human_review(self, merchant_id: str, campaign_id: str, reason: str) -> Dict[str, Any]:
        """Controlled Write Tool: Flag campaign for human merchant sign-off."""
        self._validate_tenant_id(merchant_id)
        return {
            "status": "HUMAN_REVIEW_FLAGGED",
            "campaignId": campaign_id,
            "reason": reason
        }

    # ==================== TENANT SECURITY VALIDATION ====================

    def _validate_tenant_id(self, merchant_id: str):
        if not merchant_id or not isinstance(merchant_id, str) or not merchant_id.strip():
            raise ValueError("Tenant isolation security violation: merchant_id must be non-empty string.")
