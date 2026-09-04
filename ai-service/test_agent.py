import pytest
from fastapi.testclient import TestClient
from main import app
from agent.state import AgentOrchestrationRequest, ActionDecisionEnum, RiskLevelEnum, AgentNextStepEnum
from agent.graph import build_recovery_agent_graph
from agent.mcp_tools import MendMcpToolSuite

client = TestClient(app)

def test_mcp_tools():
    tools = MendMcpToolSuite()
    p_ctx = tools.get_payment_details("m_test_1", "pay_test_1")
    assert p_ctx["status"] == "SUCCESS"

    c_ctx = tools.get_payment_history("m_test_1", "cust_123")
    assert c_ctx["historicalSuccessfulPayments"] == 12

    comp = tools.check_compliance("m_test_1", "camp_1", "RETRY_PAYMENT", 1, 500000)
    assert comp["allowed"] is True
    assert comp["status"] == "COMPLIANCE_ALLOWED"

    comp_high = tools.check_compliance("m_test_1", "camp_1", "RETRY_PAYMENT", 1, 1500000)
    assert comp_high["status"] == "HUMAN_REVIEW_REQUIRED"

def test_mcp_tool_tenant_isolation():
    tools = MendMcpToolSuite()
    with pytest.raises(ValueError, match="Tenant isolation security violation"):
        tools.get_payment_details("", "pay_test_1")

def test_agent_graph_execution_with_persistence():
    graph = build_recovery_agent_graph()
    state = {
        "merchant_id": "00000000-0000-0000-0000-000000000001",
        "campaign_id": "test-camp-id-123",
        "payment_id": "pay_test_999",
        "attempt_number": 1,
        "payment_context": {
            "failureCode": "insufficient_funds",
            "failureReason": "low account balance",
            "amountInCents": 200000
        }
    }
    config = {"configurable": {"thread_id": "thread_test_123"}}
    result = graph.invoke(state, config=config)

    assert result["merchant_id"] == "00000000-0000-0000-0000-000000000001"
    assert result["proposed_decision"] in ["RETRY_PAYMENT", "CUSTOMER_ACTION_REQUIRED", "REVIEW_REQUIRED", "ESCALATE"]
    assert result["next_step"] in ["FINISHED", "EXECUTE", "HUMAN_APPROVAL", "ESCALATE"]

def test_agent_orchestration_endpoint_insufficient_funds():
    req_data = {
        "merchantId": "00000000-0000-0000-0000-000000000001",
        "campaignId": "camp_insufficient_1",
        "paymentId": "pay_insufficient_1",
        "failureCode": "insufficient_funds",
        "failureReason": "Card balance insufficient",
        "amountInCents": 150000,
        "attemptCount": 1,
        "traceId": "trace_test_abc123"
    }
    resp = client.post("/api/v1/agent/orchestrate", json=req_data)
    assert resp.status_code == 200
    json_resp = resp.json()
    assert json_resp["merchantId"] == req_data["merchantId"]
    assert json_resp["decision"] == "RETRY_PAYMENT"
    assert json_resp["riskLevel"] == "LOW"
    assert json_resp["requiresHumanApproval"] is False
    assert json_resp["traceId"] == "trace_test_abc123"

def test_agent_orchestration_endpoint_high_value_human_approval():
    req_data = {
        "merchantId": "00000000-0000-0000-0000-000000000001",
        "campaignId": "camp_highval_1",
        "paymentId": "pay_highval_1",
        "failureCode": "insufficient_funds",
        "failureReason": "Card balance insufficient",
        "amountInCents": 2500000, # INR 25,000 > INR 10,000 threshold
        "attemptCount": 1
    }
    resp = client.post("/api/v1/agent/orchestrate", json=req_data)
    assert resp.status_code == 200
    json_resp = resp.json()
    assert json_resp["requiresHumanApproval"] is True
    assert json_resp["nextStep"] == "HUMAN_APPROVAL"

def test_agent_orchestration_endpoint_max_attempts_exceeded():
    req_data = {
        "merchantId": "00000000-0000-0000-0000-000000000001",
        "campaignId": "camp_maxatt_1",
        "paymentId": "pay_maxatt_1",
        "failureCode": "insufficient_funds",
        "failureReason": "Low balance",
        "attemptCount": 4
    }
    resp = client.post("/api/v1/agent/orchestrate", json=req_data)
    assert resp.status_code == 200
    json_resp = resp.json()
    assert json_resp["decision"] == "ESCALATE"
    assert json_resp["nextStep"] in ["ESCALATE", "FINISHED"]

def test_agent_orchestration_card_expired_customer_action():
    req_data = {
        "merchantId": "00000000-0000-0000-0000-000000000001",
        "campaignId": "camp_card_exp_1",
        "paymentId": "pay_card_exp_1",
        "failureCode": "card_expired",
        "failureReason": "The card has expired",
        "amountInCents": 50000,
        "attemptCount": 1
    }
    resp = client.post("/api/v1/agent/orchestrate", json=req_data)
    assert resp.status_code == 200
    json_resp = resp.json()
    assert json_resp["decision"] == "CUSTOMER_ACTION_REQUIRED"

def test_agent_orchestration_missing_merchant_id():
    req_data = {
        "merchantId": "",
        "campaignId": "camp_invalid_1",
        "paymentId": "pay_invalid_1"
    }
    resp = client.post("/api/v1/agent/orchestrate", json=req_data)
    assert resp.status_code == 400
    assert "merchantId" in resp.json()["detail"]
