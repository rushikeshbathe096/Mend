-- V4__add_event_id_to_classification_results.sql
-- Phase 6: AI Classification
-- Updates classification_results table to link directly to webhook_events and enforce idempotency constraint

ALTER TABLE classification_results ALTER COLUMN campaign_id DROP NOT NULL;

ALTER TABLE classification_results ADD COLUMN IF NOT EXISTS event_id UUID REFERENCES webhook_events(id) ON DELETE CASCADE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_classification_results_event_id ON classification_results(event_id) WHERE event_id IS NOT NULL;
