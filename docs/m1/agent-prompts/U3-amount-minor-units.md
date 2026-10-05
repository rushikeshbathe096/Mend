# U3 — Resolve the original payment amount in minor units

## CONTEXT
Invariant 6.1: money in integer minor units. Payment links require integer `amount` in paise (≥ 100). `WebhookAmountResolver.resolveAmount` returns rupees as `BigDecimal`, used by analytics/other callers.

## CURRENT IMPLEMENTATION
`service/WebhookAmountResolver.java` — finds the stored webhook event by `(merchantId, externalEventId=paymentId)`, reads `payload.payment.entity.amount` (paise), divides by 100.

## TASK
Add a minor-unit path without changing existing behaviour.

## FILES TO TOUCH
- `service/WebhookAmountResolver.java`: add `Optional<MoneyMinor> resolveOriginalAmount(UUID merchantId, String paymentId)` reading `amount` (as `long`, must be integral and > 0) **and** `currency` from the same payment entity. Refactor the JSON-walking into a private method shared by both public methods.
- `src/test/java/com/mend/service/WebhookAmountResolverTest.java` (create if absent)

## FILES NOT TO TOUCH
Existing `resolveAmount` / `extractAmountFromWebhook` signatures and results; any caller of them.

## DESIGN
- `amount` node must be an integral number (`canConvertToLong()` and `isIntegralNumber()`); a decimal or string → empty.
- `currency` missing → empty (do not default to INR — that would invent data).
- Tenant-scoped lookup only (keep the existing comment's guarantee).

## INVARIANTS
No floating point. No unscoped lookup.

## FAILURE SEMANTICS
Any parse problem → `Optional.empty()` + DEBUG log. Callers treat empty as `Rejected(LOCAL_PRECONDITION)` (U6).

## SECURITY REQUIREMENTS
Do not log the raw payload.

## TEST REQUIREMENTS
Integral paise → value; decimal `100.5` → empty; string `"100"` → empty; missing currency → empty; other merchant's event → empty; existing `resolveAmount` results unchanged (regression assert).

## ACCEPTANCE CRITERIA
Suite green, existing tests unchanged.

## DO NOT IMPLEMENT
Storing amount on `Campaign` (schema change — decide in M2/M4), changing analytics.
