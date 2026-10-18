-- V24__eval_snapshots.sql
-- M4 U4.3 (ADR-004 §5): immutable records of evaluation snapshots. The rows are PII-free metadata; the snapshot files
-- (campaigns.jsonl, decisions.jsonl, campaigns.csv, manifest.json) live under mend.eval.snapshot.dir. A snapshot that
-- fails validation is stored as INVALID with its defects; nothing is repaired. New table only: no existing row is touched.

CREATE TABLE eval_snapshots (
    id UUID PRIMARY KEY,
    schema_version VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    window_from TIMESTAMPTZ NOT NULL,
    window_to TIMESTAMPTZ NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    horizon_days INT NOT NULL,
    filters JSONB NOT NULL,
    provider_mode VARCHAR(30) NOT NULL,
    row_count INT NOT NULL,
    decision_count INT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(10) NOT NULL,
    errors JSONB NOT NULL,
    CONSTRAINT ck_eval_snapshots_status CHECK (status IN ('VALID', 'INVALID')),
    CONSTRAINT ck_eval_snapshots_window CHECK (window_from < window_to),
    CONSTRAINT ck_eval_snapshots_counts CHECK (row_count >= 0 AND decision_count >= 0),
    CONSTRAINT ck_eval_snapshots_sha CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_eval_snapshots_status_errors CHECK ((status = 'VALID') = (jsonb_array_length(errors) = 0))
);

CREATE INDEX ix_eval_snapshots_created ON eval_snapshots (created_at);
CREATE INDEX ix_eval_snapshots_params ON eval_snapshots (window_from, window_to, as_of, sha256);

CREATE TRIGGER trg_eval_snapshots_append_only BEFORE UPDATE OR DELETE ON eval_snapshots
    FOR EACH ROW EXECUTE FUNCTION mend_reject_modification();
CREATE TRIGGER trg_eval_snapshots_no_truncate BEFORE TRUNCATE ON eval_snapshots
    FOR EACH STATEMENT EXECUTE FUNCTION mend_reject_modification();
