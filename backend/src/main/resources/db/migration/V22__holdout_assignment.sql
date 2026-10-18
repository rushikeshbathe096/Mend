-- V22__holdout_assignment.sql
-- M4 U4.1 (ADR-004 §1-§3): per-merchant randomized holdout. A campaign is assigned TREATMENT or CONTROL once, at its first
-- eligibility (CLASSIFIED -> ELIGIBLE), from HMAC-SHA256(merchant salt, invoice_key). CONTROL gets no recovery intent and
-- no provider call; everything else (late capture, replies, opt-out, reviews) is unchanged.
-- New tables, one defaulted column (metadata-only in PostgreSQL 11+), triggers. No existing row is rewritten.
-- The new tables have no foreign keys on purpose: they are append-only records (ids only) that outlive row deletion.

-- Holdout rate in basis points (0 = off, the default; 2000 = 0.2; at most 5000 = 0.5). Integer: no floating point.
ALTER TABLE merchant_config ADD COLUMN holdout_rate_bp INT NOT NULL DEFAULT 0;
ALTER TABLE merchant_config ADD CONSTRAINT ck_merchant_config_holdout_rate_bp CHECK (holdout_rate_bp BETWEEN 0 AND 5000);

-- Per-merchant random salts, generated at runtime (never in a file). A rotation inserts a new version; a salt never changes.
-- purpose ASSIGNMENT keys the holdout hash; EVAL_ID keys the evaluation-snapshot id pseudonyms (U4.3).
CREATE TABLE merchant_experiment_salts (
    merchant_id UUID NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    salt_version INT NOT NULL,
    salt BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, purpose, salt_version),
    CONSTRAINT ck_experiment_salts_purpose CHECK (purpose IN ('ASSIGNMENT', 'EVAL_ID')),
    CONSTRAINT ck_experiment_salts_version CHECK (salt_version >= 1),
    CONSTRAINT ck_experiment_salts_length CHECK (octet_length(salt) = 32)
);

-- One assignment per campaign, immutable. bucket = HMAC value (unsigned 64-bit) mod 10 000; CONTROL iff bucket < rate.
-- source HASH: hashed at this campaign's first eligibility. INVOICE_REUSE: a later campaign of an already-assigned invoice
-- copies that invoice's arm, bucket and rate (one arm per invoice, also after a rate change).
CREATE TABLE holdout_assignments (
    campaign_id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    arm VARCHAR(10) NOT NULL,
    bucket INT NOT NULL,
    holdout_rate_bp INT NOT NULL,
    salt_version INT NOT NULL,
    hash_version VARCHAR(50) NOT NULL,
    source VARCHAR(20) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_holdout_assignments_source CHECK (source IN ('HASH', 'INVOICE_REUSE')),
    CONSTRAINT ck_holdout_assignments_arm CHECK (arm IN ('TREATMENT', 'CONTROL')),
    CONSTRAINT ck_holdout_assignments_bucket CHECK (bucket BETWEEN 0 AND 9999),
    CONSTRAINT ck_holdout_assignments_rate CHECK (holdout_rate_bp BETWEEN 0 AND 5000),
    CONSTRAINT ck_holdout_assignments_consistent CHECK ((arm = 'CONTROL') = (bucket < holdout_rate_bp))
);

CREATE INDEX ix_holdout_assignments_merchant ON holdout_assignments (merchant_id, assigned_at);

-- Append-only: UPDATE, DELETE and TRUNCATE are rejected (ADR-004 §1, AGENTS.md invariant 21).
CREATE FUNCTION mend_reject_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'APPEND_ONLY: % on % is not allowed', TG_OP, TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_experiment_salts_append_only BEFORE UPDATE OR DELETE ON merchant_experiment_salts
    FOR EACH ROW EXECUTE FUNCTION mend_reject_modification();
CREATE TRIGGER trg_experiment_salts_no_truncate BEFORE TRUNCATE ON merchant_experiment_salts
    FOR EACH STATEMENT EXECUTE FUNCTION mend_reject_modification();
CREATE TRIGGER trg_holdout_assignments_append_only BEFORE UPDATE OR DELETE ON holdout_assignments
    FOR EACH ROW EXECUTE FUNCTION mend_reject_modification();
CREATE TRIGGER trg_holdout_assignments_no_truncate BEFORE TRUNCATE ON holdout_assignments
    FOR EACH STATEMENT EXECUTE FUNCTION mend_reject_modification();

-- Defence in depth for invariant 22: no recovery intent can be inserted for a CONTROL campaign, whatever the code path.
CREATE FUNCTION mend_reject_control_intent() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM holdout_assignments WHERE campaign_id = NEW.campaign_id AND arm = 'CONTROL') THEN
        RAISE EXCEPTION 'HOLDOUT_CONTROL: campaign % is in the holdout CONTROL arm; MEND takes no recovery action', NEW.campaign_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_action_intents_no_control BEFORE INSERT ON action_intents
    FOR EACH ROW EXECUTE FUNCTION mend_reject_control_intent();
