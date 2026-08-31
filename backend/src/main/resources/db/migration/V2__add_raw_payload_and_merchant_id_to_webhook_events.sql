-- V2__add_raw_payload_and_merchant_id_to_webhook_events.sql
-- Phase 4: Webhook Gateway enhancements

ALTER TABLE webhook_events
    ADD COLUMN IF NOT EXISTS raw_payload TEXT,
    ADD COLUMN IF NOT EXISTS merchant_id UUID;

ALTER TABLE webhook_events
    ADD CONSTRAINT fk_webhook_events_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_webhook_events_merchant_id ON webhook_events(merchant_id);
