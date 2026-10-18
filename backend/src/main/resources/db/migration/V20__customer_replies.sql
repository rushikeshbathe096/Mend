-- V20__customer_replies.sql
-- M3 U2.4 (ADR-003 §5): the one AI task. A customer reply (PII, capped at 2000 characters) is parsed asynchronously into
-- a *suggestion*; a deterministic policy owns every effect. Retention: 90 days (purge job is M5-owned).
-- New tables only: no existing row is read or rewritten.

CREATE TABLE customer_replies (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    text VARCHAR(2000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(100),
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE CASCADE,
    FOREIGN KEY (campaign_id) REFERENCES campaigns(id) ON DELETE CASCADE,
    CONSTRAINT ck_customer_replies_status CHECK (status IN ('PENDING', 'PARSED', 'FAILED')),
    CONSTRAINT ck_customer_replies_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_customer_replies_text CHECK (char_length(text) >= 1)
);

CREATE INDEX ix_customer_replies_due ON customer_replies (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_customer_replies_campaign ON customer_replies (campaign_id, created_at);

-- One suggestion per reply. intent/promised_date/abstain are set only for a valid suggestion; an invalid model output is
-- stored as invalid with the validation error, never coerced. model_id is exactly what the provider reported (or NULL).
CREATE TABLE reply_suggestions (
    id UUID PRIMARY KEY,
    reply_id UUID NOT NULL UNIQUE,
    merchant_id UUID NOT NULL,
    valid BOOLEAN NOT NULL,
    intent VARCHAR(30),
    promised_date DATE,
    abstain BOOLEAN,
    validation_error VARCHAR(200),
    model_id VARCHAR(100),
    prompt_version VARCHAR(50),
    effect VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (reply_id) REFERENCES customer_replies(id) ON DELETE CASCADE,
    FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE CASCADE,
    CONSTRAINT ck_reply_suggestions_intent CHECK (intent IS NULL OR intent IN ('OPT_OUT', 'ALREADY_PAID', 'DISPUTE', 'PROMISE_TO_PAY', 'OTHER')),
    CONSTRAINT ck_reply_suggestions_valid CHECK ((valid AND intent IS NOT NULL AND abstain IS NOT NULL AND validation_error IS NULL)
        OR (NOT valid AND intent IS NULL AND promised_date IS NULL AND abstain IS NULL AND validation_error IS NOT NULL))
);
