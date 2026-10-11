-- M2 U8: campaign-level correlation needs the real Razorpay payment id of the ORIGINAL failed payment. payment_id keeps its
-- meaning and uniqueness (for webhook-created campaigns it is the composite "<eventType>_<entityId>", M1 summary §2).
-- provider_payment_id is that real pay_ id, or NULL when unknown. Not unique: a manual campaign ("pay_x") and a
-- webhook-created one ("payment.failed_pay_x") can carry the same original payment.
--
-- recovery_attribution: MEND (captured on a MEND recovery action) or ORIGINAL_PAYMENT_LATE_CAPTURE (the original payment
-- was captured later). NULL = recovered before V15 or not recovered.
--
-- Planned as V14 in the M2 plan; V14 was taken by the U6 review fix, so this is V15 (V13/V14 are never edited).
-- Locking: ADD COLUMN (nullable, no default) is metadata-only; the backfill rewrites matching rows under ROW EXCLUSIVE.

ALTER TABLE campaigns ADD COLUMN provider_payment_id VARCHAR(64);
ALTER TABLE campaigns ADD COLUMN recovery_attribution VARCHAR(40);
ALTER TABLE campaigns ADD CONSTRAINT ck_campaigns_recovery_attribution
    CHECK (recovery_attribution IS NULL OR recovery_attribution IN ('MEND', 'ORIGINAL_PAYMENT_LATE_CAPTURE'));
ALTER TABLE campaigns ADD CONSTRAINT ck_campaigns_provider_payment_id
    CHECK (provider_payment_id IS NULL OR provider_payment_id ~ '^pay_[A-Za-z0-9]{1,60}$');

-- Backfill only what is certain: a raw pay_ id, or the composite "payment.<event>_pay_<id>".
UPDATE campaigns
   SET provider_payment_id = substring(payment_id from '(pay_[A-Za-z0-9]{1,60})$')
 WHERE payment_id ~ '^(payment\.[a-z_]+_)?pay_[A-Za-z0-9]{1,60}$';

CREATE INDEX ix_campaigns_merchant_provider_payment_id
    ON campaigns (merchant_id, provider_payment_id) WHERE provider_payment_id IS NOT NULL;
