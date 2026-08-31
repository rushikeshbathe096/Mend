-- V6: Create recovery_decisions table for Phase 8 Recovery Strategy Engine

CREATE TABLE recovery_decisions (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    classification_result_id UUID REFERENCES classification_results(id) ON DELETE SET NULL,
    strategy VARCHAR(50) NOT NULL,
    reason TEXT NOT NULL,
    priority VARCHAR(20) NOT NULL,
    confidence NUMERIC(3, 2),
    policy_version VARCHAR(20) NOT NULL,
    evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_recovery_decisions_campaign_version UNIQUE (campaign_id, policy_version, strategy, evaluated_at)
);

CREATE INDEX idx_recovery_decisions_campaign_id ON recovery_decisions(campaign_id);
CREATE INDEX idx_recovery_decisions_merchant_id ON recovery_decisions(merchant_id);
