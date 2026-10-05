# U8 — Correlation: campaign-level events never finalize intents

## TASK
1. In `DefaultPaymentReconciliationService`, split correlation into **intent-specific** (notes `mend_intent_id`/legacy `action_intent_id`, `responseReference` match on plink/pay id, intent idempotency key) and **campaign-level** (campaign paymentId / subscriptionId — old strategies 4 and 5).
2. Campaign-level match:
   - original payment FAILED (e.g. the event that created the campaign, or a repeat) → no-op on intents (INFO `original_payment_event`). Enable the M1 `@Disabled("M2: correlation of original-payment failures")` test.
   - original payment CAPTURED (late capture) → campaign RECOVERED (money really arrived; attribution `ORIGINAL_PAYMENT_LATE_CAPTURE`, not MEND), in-flight intents → CANCELLED via transitions, and for each live link: if the payment-link cancel endpoint is **verified from the official Razorpay docs page and recorded in test mode** (extend `scripts/record_razorpay_contracts.sh` with `--cancel-link`; ask the owner to run it), call it through a new port method; otherwise create review item `DUPLICATE_PAYMENT_RISK` with the plink id. Do not guess the endpoint.
3. `findByResponseReference` → `findAllByResponseReference`; >1 match → no-op + ERROR log + review item (should be impossible after U6).
4. Fix the composite `campaign.paymentId` impact only where correlation needs a real `pay_` id: store the real payment entity id in a new nullable `campaigns.provider_payment_id` (migration **V14**) at creation; campaign-level correlation uses it. Do not change `payment_id` semantics or uniqueness.

## FILES TO TOUCH
Reconciliation service, repository, campaign creation (field set), V14, provider port (only if cancel is verified), tests, fixture script (only `--cancel-link` mode).

## STOP CONDITIONS
Cancel endpoint can't be verified from docs → do the review-item fallback, don't stop. If >0 existing tests rely on campaign-level correlation finalizing an intent, list them and stop before changing them.

## ACCEPTANCE CRITERIA
Skipped count becomes 0 (M1 tracking test enabled). Late-capture test: campaign RECOVERED with non-MEND attribution, link intent CANCELLED, cancel call or review item present.
