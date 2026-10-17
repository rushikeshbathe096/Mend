-- V18__synthetic_recovery_attribution.sql
-- Bug sweep item 5: a mock-mode SyntheticCaptured recovery is attributed SYNTHETIC (simulated money), so analytics can
-- report it separately and never as MEND. Only the CHECK changes: no row is rewritten. Campaigns recovered synthetically
-- before V18 keep NULL (attribution unknown); their attribution is not reconstructed (it would be inferred, not evidenced).
ALTER TABLE campaigns DROP CONSTRAINT ck_campaigns_recovery_attribution;
ALTER TABLE campaigns ADD CONSTRAINT ck_campaigns_recovery_attribution
    CHECK (recovery_attribution IS NULL OR recovery_attribution IN ('MEND', 'ORIGINAL_PAYMENT_LATE_CAPTURE', 'SYNTHETIC'));
