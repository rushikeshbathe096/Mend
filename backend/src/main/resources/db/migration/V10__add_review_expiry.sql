-- V10__add_review_expiry.sql
-- Phase 19: Merchant Productization - Human Approval Workflow
-- Adds an optional expiry window to review_queue so stale merchant review items
-- can be surfaced as expired/escalated instead of lingering as PENDING forever.
ALTER TABLE review_queue ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;
