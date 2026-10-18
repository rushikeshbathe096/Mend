-- V26__reply_label_examples.sql
-- M4 U4.7 (ADR-004 §8): labelled examples for the GATED retrieval experiment of reply parsing (off by default:
-- mend.reply.retrieval.enabled=false). PostgreSQL full-text search only (no vector DB, no new service).
-- Only SYNTHETIC or consented examples: never a customer reply (CHECK on source; no code copies from customer_replies).
-- New table only; append-only (a dataset is versioned, never edited).
CREATE TABLE reply_label_examples (
    id UUID PRIMARY KEY,
    dataset_version VARCHAR(50) NOT NULL,
    source VARCHAR(20) NOT NULL,
    text VARCHAR(2000) NOT NULL,
    intent VARCHAR(30) NOT NULL,
    promised_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', text)) STORED,
    CONSTRAINT ck_reply_label_examples_source CHECK (source IN ('SYNTHETIC', 'CONSENTED')),
    CONSTRAINT ck_reply_label_examples_intent CHECK (intent IN ('OPT_OUT', 'ALREADY_PAID', 'DISPUTE', 'PROMISE_TO_PAY', 'OTHER')),
    CONSTRAINT ck_reply_label_examples_date CHECK (promised_date IS NULL OR intent = 'PROMISE_TO_PAY'),
    CONSTRAINT uq_reply_label_examples_text UNIQUE (dataset_version, text)
);

CREATE INDEX ix_reply_label_examples_tsv ON reply_label_examples USING GIN (tsv);

CREATE TRIGGER trg_reply_label_examples_append_only BEFORE UPDATE OR DELETE ON reply_label_examples
    FOR EACH ROW EXECUTE FUNCTION mend_reject_modification();
CREATE TRIGGER trg_reply_label_examples_no_truncate BEFORE TRUNCATE ON reply_label_examples
    FOR EACH STATEMENT EXECUTE FUNCTION mend_reject_modification();
