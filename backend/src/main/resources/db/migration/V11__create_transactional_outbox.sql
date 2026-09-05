-- Transactional outbox for durable PostgreSQL -> Redis delivery.
-- The webhook row and its outbox row are inserted in the same transaction.

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    merchant_id UUID,
    trace_id VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_outbox_webhook_event
        FOREIGN KEY (aggregate_id) REFERENCES webhook_events(id) ON DELETE CASCADE,
    CONSTRAINT fk_outbox_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE SET NULL,
    CONSTRAINT uq_outbox_aggregate_event
        UNIQUE (aggregate_type, aggregate_id, event_type)
);

CREATE INDEX idx_outbox_events_delivery
    ON outbox_events(status, next_attempt_at, created_at);
CREATE INDEX idx_outbox_events_aggregate
    ON outbox_events(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_events_merchant
    ON outbox_events(merchant_id);
