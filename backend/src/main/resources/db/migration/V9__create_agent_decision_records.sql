CREATE TABLE agent_decision_records (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    payment_id VARCHAR(255),
    decision VARCHAR(100) NOT NULL,
    selected_action VARCHAR(100),
    confidence NUMERIC(5, 4),
    reasoning TEXT,
    evidence TEXT,
    next_step VARCHAR(100),
    stop_reason VARCHAR(255),
    model_version VARCHAR(100),
    requires_human_approval BOOLEAN NOT NULL DEFAULT FALSE,
    compliance_status VARCHAR(100),
    execution_status VARCHAR(100),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_decisions_campaign ON agent_decision_records(campaign_id);
CREATE INDEX idx_agent_decisions_merchant ON agent_decision_records(merchant_id);
