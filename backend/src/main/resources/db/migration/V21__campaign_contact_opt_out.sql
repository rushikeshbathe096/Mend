-- V21__campaign_contact_opt_out.sql
-- M3 U2.4 (ADR-003 §5): a customer opted out of contact (OPT_OUT reply suggestion, applied by the deterministic policy).
-- Fail-closed compliance block CUSTOMER_OPTED_OUT while set; only a merchant admin clears it (audited).
-- ADD COLUMN, nullable, no default: metadata-only, no row rewritten.
ALTER TABLE campaigns ADD COLUMN contact_opted_out_at TIMESTAMP;
