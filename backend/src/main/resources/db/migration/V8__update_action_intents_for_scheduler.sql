-- V8: Update action_intents table for Phase 10 Scheduler and Action Intent Orchestration

ALTER TABLE action_intents
    ADD COLUMN IF NOT EXISTS merchant_id UUID REFERENCES merchants(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS source_strategy VARCHAR(50),
    ADD COLUMN IF NOT EXISTS compliance_decision_id UUID REFERENCES compliance_decisions(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS scheduled_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS claim_token VARCHAR(255),
    ADD COLUMN IF NOT EXISTS worker_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITH TIME ZONE;

-- Create indexes for efficient scheduler querying and tenant isolation
CREATE INDEX IF NOT EXISTS idx_action_intents_scheduler_poll ON action_intents(status, scheduled_at);
CREATE INDEX IF NOT EXISTS idx_action_intents_merchant_id ON action_intents(merchant_id);
CREATE INDEX IF NOT EXISTS idx_action_intents_claimed ON action_intents(status, claimed_at);
CREATE INDEX IF NOT EXISTS idx_action_intents_compliance ON action_intents(compliance_decision_id);
