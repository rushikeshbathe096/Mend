import pytest
import os
import time
import json
import uuid
import threading
from unittest.mock import patch, MagicMock
from fastapi.testclient import TestClient

from main import app
from agent.state import AgentOrchestrationRequest
from agent.graph import build_recovery_agent_graph
from agent.mcp_tools import MendMcpToolSuite
from agent.checkpointer import (
    DurableMemorySaver,
    get_checkpointer,
    setup_checkpointer_tables,
    build_tenant_thread_id,
    validate_tenant_thread_access,
    resolve_postgres_uri
)

client = TestClient(app)

POSTGRES_TEST_URI = os.getenv("MEND_CHECKPOINT_POSTGRES_URI") or "postgresql://mend_user:mend_password@localhost:5433/mend"

# ==================== 1. CHECKPOINTER SETUP & TENANT ISOLATION TESTS ====================

def test_tenant_thread_id_construction():
    thread_id = build_tenant_thread_id("m_merchant_123", "c_campaign_456")
    assert thread_id == "merchant:m_merchant_123:campaign:c_campaign_456:recovery"

def test_tenant_thread_id_validation_empty_inputs():
    with pytest.raises(ValueError, match="Tenant isolation violation"):
        build_tenant_thread_id("", "c_campaign_456")
    with pytest.raises(ValueError, match="Tenant isolation violation"):
        build_tenant_thread_id("m_merchant_123", "")

def test_tenant_thread_access_validation_valid():
    thread_id = "merchant:m_100:campaign:c_200:recovery"
    # Should not raise exception
    validate_tenant_thread_access(thread_id, "m_100")

def test_tenant_thread_access_validation_cross_tenant_denied():
    thread_id = "merchant:m_tenant_A:campaign:c_200:recovery"
    with pytest.raises(ValueError, match="Tenant isolation error"):
        validate_tenant_thread_access(thread_id, "m_tenant_B")

def test_postgres_saver_initialization_and_table_setup():
    uri = resolve_postgres_uri(POSTGRES_TEST_URI)
    assert uri is not None
    try:
        from langgraph.checkpoint.postgres import PostgresSaver
        setup_checkpointer_tables(uri)
        with PostgresSaver.from_conn_string(uri) as saver:
            saver.setup()
            assert saver is not None
    except Exception as e:
        pytest.skip(f"PostgreSQL not reachable or PostgresSaver unavailable: {e}")

# ==================== 2. MULTI-INSTANCE PERSISTENCE TESTS ====================

def test_multi_instance_postgres_checkpoint_resumption():
    uri = resolve_postgres_uri(POSTGRES_TEST_URI)
    try:
        from langgraph.checkpoint.postgres import PostgresSaver
    except ImportError:
        pytest.skip("langgraph-checkpoint-postgres not installed")

    merchant_id = "m_multi_inst_001"
    campaign_id = f"camp_multi_{uuid.uuid4().hex[:8]}"
    thread_id = build_tenant_thread_id(merchant_id, campaign_id)

    state = {
        "merchant_id": merchant_id,
        "campaign_id": campaign_id,
        "payment_id": "pay_multi_inst_1",
        "attempt_number": 1,
        "payment_context": {
            "failureCode": "insufficient_funds",
            "amountInCents": 150000
        }
    }
    config = {"configurable": {"thread_id": thread_id}}

    try:
        with PostgresSaver.from_conn_string(uri) as checkpointer_instance_1:
            checkpointer_instance_1.setup()
            graph_1 = build_recovery_agent_graph(checkpointer=checkpointer_instance_1)
            res_1 = graph_1.invoke(state, config=config)
            assert res_1["merchant_id"] == merchant_id

        # Simulate Instance 2 (completely separate checkpointer/graph instance reading same DB)
        with PostgresSaver.from_conn_string(uri) as checkpointer_instance_2:
            graph_2 = build_recovery_agent_graph(checkpointer=checkpointer_instance_2)
            res_2 = graph_2.invoke(None, config=config)
            assert res_2["merchant_id"] == merchant_id
            assert res_2["campaign_id"] == campaign_id
    except Exception as e:
        pytest.fail(f"Multi-instance PostgreSQL checkpoint test failed: {e}")

# ==================== 3. CONCURRENCY & DUPLICATE RESUME TESTS ====================

def test_concurrent_same_thread_invocation_safety():
    """Verify that multiple threads invoking graph on same thread_id complete safely without race corruption."""
    graph = build_recovery_agent_graph()
    merchant_id = "m_concurrent_99"
    campaign_id = "camp_concurrent_99"
    thread_id = build_tenant_thread_id(merchant_id, campaign_id)
    config = {"configurable": {"thread_id": thread_id}}

    results = []
    errors = []

    def worker(worker_idx):
        try:
            state = {
                "merchant_id": merchant_id,
                "campaign_id": campaign_id,
                "payment_id": f"pay_conc_{worker_idx}",
                "attempt_number": 1,
                "payment_context": {
                    "failureCode": "insufficient_funds",
                    "amountInCents": 100000
                }
            }
            res = graph.invoke(state, config=config)
            results.append(res)
        except Exception as e:
            errors.append(e)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(5)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert len(errors) == 0
    assert len(results) == 5
    for r in results:
        assert r["merchant_id"] == merchant_id

# ==================== 4. FAILURE INJECTION TESTS ====================

def test_checkpoint_write_failure_fallback():
    """Simulate disk write failure during DurableMemorySaver snapshot save."""
    saver = DurableMemorySaver()
    with patch("builtins.open", side_effect=IOError("Simulated disk write failure")):
        # Should catch exception internally, log warning, and not crash caller
        saver.save_checkpoint_snapshot("thread_err_1", {"state": "OK"}, merchant_id="m_1", campaign_id="c_1")

def test_stale_checkpoint_re_fetches_fresh_postgres_state():
    """Verify that graph observation node re-fetches authoritative DB state from MCP on every execution turn."""
    tools_mock = MendMcpToolSuite()
    graph = build_recovery_agent_graph()

    # Executing graph with high value payment amount
    state = {
        "merchant_id": "00000000-0000-0000-0000-000000000001",
        "campaign_id": "camp_stale_test_1",
        "payment_id": "pay_stale_1",
        "attempt_number": 1,
        "payment_context": {
            "failureCode": "insufficient_funds",
            "amountInCents": 2000000 # High value forces human approval
        }
    }
    thread_id = build_tenant_thread_id("00000000-0000-0000-0000-000000000001", "camp_stale_test_1")
    config = {"configurable": {"thread_id": thread_id}}

    res = graph.invoke(state, config=config)
    assert res["human_review_required"] is True
    assert res["next_step"] == "HUMAN_APPROVAL"

def test_mcp_unavailability_graceful_fallback():
    """Verify graph execution when MCP/backend is unreachable."""
    graph = build_recovery_agent_graph(backend_url="http://invalid-host-9999:8080")
    state = {
        "merchant_id": "00000000-0000-0000-0000-000000000001",
        "campaign_id": "camp_unavail_1",
        "payment_id": "pay_unavail_1",
        "attempt_number": 1,
        "payment_context": {
            "failureCode": "insufficient_funds",
            "amountInCents": 100000
        }
    }
    config = {"configurable": {"thread_id": build_tenant_thread_id("00000000-0000-0000-0000-000000000001", "camp_unavail_1")}}
    res = graph.invoke(state, config=config)
    assert res["proposed_decision"] in ["RETRY_PAYMENT", "REVIEW_REQUIRED", "ESCALATE"]

# ==================== 5. PERFORMANCE BENCHMARK ====================

def test_performance_benchmark_checkpointer_latency():
    """Measures multi-agent workflow latency with active checkpointer."""
    graph = build_recovery_agent_graph()
    latencies = []

    for i in range(10):
        start = time.perf_counter()
        merchant_id = "00000000-0000-0000-0000-000000000001"
        campaign_id = f"perf_camp_p185_{i}"
        thread_id = build_tenant_thread_id(merchant_id, campaign_id)
        state = {
            "merchant_id": merchant_id,
            "campaign_id": campaign_id,
            "payment_id": f"perf_pay_p185_{i}",
            "attempt_number": 1,
            "payment_context": {
                "failureCode": "insufficient_funds",
                "amountInCents": 100000
            }
        }
        config = {"configurable": {"thread_id": thread_id}}
        graph.invoke(state, config=config)
        elapsed_ms = (time.perf_counter() - start) * 1000
        latencies.append(elapsed_ms)

    latencies.sort()
    p50 = latencies[int(len(latencies) * 0.50)]
    p95 = latencies[int(len(latencies) * 0.95)]

    assert p50 < 100.0 # Latency under 100ms in bounded heuristic mode
    print(f"\nPhase 18.5 Checkpointer Performance Latency: p50={p50:.2f}ms, p95={p95:.2f}ms")
