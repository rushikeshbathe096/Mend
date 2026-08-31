-- V7: Create compliance_decisions table for Phase 9 Compliance + Safety Engine

CREATE TABLE compliance_decisions (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    recovery_decision_id UUID REFERENCES recovery_decisions(id) ON DELETE SET NULL,
    strategy VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    reason VARCHAR(50) NOT NULL,
    detail_message TEXT,
    policy_version VARCHAR(20) NOT NULL,
    evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_compliance_decisions_campaign_eval UNIQUE (campaign_id, policy_version, strategy, status, evaluated_at)
);

CREATE INDEX idx_compliance_decisions_campaign_id ON compliance_decisions(campaign_id);
CREATE INDEX idx_compliance_decisions_merchant_id ON compliance_decisions(merchant_id);
CREATE INDEX idx_compliance_decisions_status ON compliance_decisions(status);
