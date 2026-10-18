-- V23__decision_log.sql
-- M4 U4.2 (ADR-004 §4, AGENTS.md invariant 21): append-only decision log. One row per decision point (ASSIGNMENT,
-- STRATEGY, COMPLIANCE, DISPATCH), written in the deciding path's transaction, with the chosen action, the eligible
-- alternatives, the probability of the chosen action (basis points; a deterministic policy has 10 000) and the policy
-- versions in force. Ids only, no PII, no free text. No foreign keys: the log outlives the rows it describes.
-- New table only: no existing row is read or rewritten.

CREATE TABLE decision_log (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    intent_id UUID,
    decision_point VARCHAR(20) NOT NULL,
    chosen VARCHAR(60) NOT NULL,
    reason_code VARCHAR(60),
    alternatives JSONB NOT NULL,
    propensity_bp INT NOT NULL,
    source_id UUID,
    taxonomy_version VARCHAR(50),
    strategy_version VARCHAR(50),
    compliance_version VARCHAR(50),
    assignment_version VARCHAR(50),
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_decision_log_point CHECK (decision_point IN ('ASSIGNMENT', 'STRATEGY', 'COMPLIANCE', 'DISPATCH')),
    CONSTRAINT ck_decision_log_propensity CHECK (propensity_bp BETWEEN 0 AND 10000),
    CONSTRAINT ck_decision_log_alternatives CHECK (jsonb_typeof(alternatives) = 'array' AND alternatives ? chosen)
);

CREATE INDEX ix_decision_log_campaign ON decision_log (campaign_id, decided_at);
CREATE INDEX ix_decision_log_merchant ON decision_log (merchant_id, decided_at);
-- One ASSIGNMENT row per campaign; one STRATEGY / COMPLIANCE row per persisted decision row (source_id).
CREATE UNIQUE INDEX uq_decision_log_assignment ON decision_log (campaign_id) WHERE decision_point = 'ASSIGNMENT';
CREATE UNIQUE INDEX uq_decision_log_source ON decision_log (decision_point, source_id)
    WHERE decision_point IN ('STRATEGY', 'COMPLIANCE');

CREATE TRIGGER trg_decision_log_append_only BEFORE UPDATE OR DELETE ON decision_log
    FOR EACH ROW EXECUTE FUNCTION mend_reject_modification();
CREATE TRIGGER trg_decision_log_no_truncate BEFORE TRUNCATE ON decision_log
    FOR EACH STATEMENT EXECUTE FUNCTION mend_reject_modification();
