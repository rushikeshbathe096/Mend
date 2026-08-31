-- V3__add_publish_status_to_webhook_events.sql
-- Phase 5: Redis Event Pipeline publication tracking

ALTER TABLE webhook_events
    ADD COLUMN IF NOT EXISTS publish_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_webhook_events_publish_status ON webhook_events(publish_status);
