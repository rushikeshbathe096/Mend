-- V5__campaign_idempotency_and_relations.sql
-- Phase 7: Campaign + State Machine
-- Enforces uniqueness constraints for idempotent campaign creation per merchant and payment

CREATE UNIQUE INDEX IF NOT EXISTS uq_campaigns_merchant_payment 
ON campaigns (merchant_id, payment_id) 
WHERE payment_id IS NOT NULL;
